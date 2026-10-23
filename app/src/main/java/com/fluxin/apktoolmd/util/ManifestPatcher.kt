package com.fluxin.apktoolmd.util

import java.io.File
import java.io.RandomAccessFile

/**
 * A minimal patcher for the binary AndroidManifest.xml.
 *
 * The mod menus (LGL / R3DNETWORK) load their library from Java: a small
 * dex is added to the package and the manifest's <application> element
 * points at their Application class, whose onCreate() does
 * System.loadLibrary().  The game's own libraries are not touched at all.
 *
 * This writes that one attribute:
 *
 *     <application android:name="com.fluxin.apktoolmd.loader.ModLoader">
 *
 * The binary format (AXML) is a string pool, a resource map and then a
 * flat stream of XML chunks - the chunks are read one after the other,
 * so inserting an attribute only means rewriting the <application>
 * chunk and growing the string pool by one string.
 */
object ManifestPatcher {

    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML = 0x0003
    private const val RES_XML_START_ELEMENT = 0x0102
    private const val RES_XML_END_ELEMENT = 0x0103
    private const val UTF8_FLAG = 1 shl 8
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val ATTR_NAME = "name"
    private const val TYPE_STRING = 0x03
    private const val NO_ENTRY = 0xFFFFFFFFL

    /** the resource id of android:name */
    private const val ANDROID_NAME_RES_ID = 0x01010003

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
                ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or
                ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun put16(v: Int, b: ByteArray, off: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun put32(v: Long, b: ByteArray, off: Int) {
        b[off] = (v.toInt() and 0xFF).toByte()
        b[off + 1] = ((v shr 8).toInt() and 0xFF).toByte()
        b[off + 2] = ((v shr 16).toInt() and 0xFF).toByte()
        b[off + 3] = ((v shr 24).toInt() and 0xFF).toByte()
    }

    private fun w16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun w32(v: Long): ByteArray = byteArrayOf(
        (v.toInt() and 0xFF).toByte(),
        ((v shr 8).toInt() and 0xFF).toByte(),
        ((v shr 16).toInt() and 0xFF).toByte(),
        ((v shr 24).toInt() and 0xFF).toByte()
    )

    private fun align4(n: Int): Int = (n + 3) and 3.inv()

    /**
     * Point the manifest's <application> at [className].
     *
     * @return null on success, otherwise the reason it failed
     */
    fun setApplicationClass(manifest: File, className: String): String? {
        val data = try {
            manifest.readBytes()
        } catch (e: Throwable) {
            return "cannot read the manifest: $e"
        }
        if (data.size < 16) return "the manifest is too small"

        // --- the string pool ---
        if (u16(data, 0) != RES_XML) return "not a binary manifest"
        val poolOff = 8
        if (u16(data, poolOff) != RES_STRING_POOL) return "no string pool"
        val poolSize = u32(data, poolOff + 4).toInt()
        val stringCount = u32(data, poolOff + 8).toInt()
        val styleCount = u32(data, poolOff + 12).toInt()
        val flags = u32(data, poolOff + 16).toInt()
        val stringsStart = u32(data, poolOff + 20).toInt()
        val stylesStart = u32(data, poolOff + 24).toInt()
        val utf8 = (flags and UTF8_FLAG) != 0

        // read the strings
        val strings = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val off = u32(data, poolOff + 28 + i * 4).toInt()
            strings.add(readString(data, poolOff + stringsStart + off, utf8))
        }

        fun indexOf(s: String): Int = strings.indexOf(s)

        // we need the android namespace, the attribute name and our class
        var nsIdx = indexOf(ANDROID_NS)
        if (nsIdx < 0) return "the android namespace is not in the pool"
        var nameIdx = indexOf(ATTR_NAME)
        if (nameIdx < 0) return "the attribute name is not in the pool"
        var classIdx = strings.indexOf(className)
        val newString = classIdx < 0
        if (newString) {
            classIdx = stringCount
        }

        // --- find the <application> element ---
        var pos = poolOff + poolSize
        // the resource map chunk
        if (pos + 8 <= data.size && u16(data, pos) == 0x0180) {
            pos += u32(data, pos + 4).toInt()
        }
        var appStart = -1
        var appSize = 0
        while (pos + 8 <= data.size) {
            val type = u16(data, pos)
            if (type != RES_XML_START_ELEMENT && type != RES_XML_END_ELEMENT &&
                type != 0x0100 && type != 0x0101 && type != 0x0104
            ) break
            val size = u32(data, pos + 4).toInt()
            if (type == RES_XML_START_ELEMENT) {
                // the element name is at pos + 20 (line, comment, ns, name)
                val nameIdxHere = u32(data, pos + 20).toInt()
                if (nameIdxHere in strings.indices && strings[nameIdxHere] == "application") {
                    appStart = pos
                    appSize = size
                    break
                }
            }
            pos += size
        }
        if (appStart < 0) return "no <application> element"

        // --- the attribute count of the element ---
        // the header is 8 bytes, then line(4) comment(4) ns(4) name(4)
        // attributeStart(2) attributeSize(2) attributeCount(2) idIndex(2)...
        val attrCountOff = appStart + 24
        val attrCount = u16(data, attrCountOff)
        val attrSize = u16(data, attrCountOff + 4)
        if (attrSize != 20) return "unexpected attribute size $attrSize"

        // does it already have an android:name?
        var existing = -1
        for (i in 0 until attrCount) {
            val a = appStart + 36 + i * 20
            val aName = u32(data, a + 4).toInt()
            val aNs = u32(data, a).toInt()
            if (aName == nameIdx && aNs == nsIdx) {
                existing = i
                break
            }
        }

        // --- build the new string pool ---
        val newPool: ByteArray
        if (newString) {
            newPool = addString(data, poolOff, poolSize, stringCount, styleCount, utf8, stringsStart, className)
        } else {
            newPool = data.copyOfRange(poolOff, poolOff + poolSize)
        }
        val newPoolSize = newPool.size

        // --- build the new <application> chunk ---
        val chunk = data.copyOfRange(appStart, appStart + appSize)
        val newAttrCount = attrCount + (if (existing >= 0) 0 else 1)
        put16(newAttrCount, chunk, attrCountOff)

        if (existing >= 0) {
            // just replace the value
            val a = 36 + existing * 20
            put32(NO_ENTRY, chunk, a + 8)                        // rawValue
            put16(8, chunk, a + 12)                              // the typed value size
            chunk[a + 14] = 0                                    // res0
            chunk[a + 15] = TYPE_STRING.toByte()                  // dataType
            put32(classIdx.toLong(), chunk, a + 16)               // data
        } else {
            // append the attribute: the chunk grows by 20 bytes
            val grown = chunk.copyOf(chunk.size + 20)
            val a = 36 + attrCount * 20
            put32(nsIdx.toLong(), grown, a)
            put32(nameIdx.toLong(), grown, a + 4)
            put32(NO_ENTRY, grown, a + 8)
            put16(8, grown, a + 12)
            grown[a + 14] = 0
            grown[a + 15] = 0
            grown[a + 16] = TYPE_STRING.toByte()
            grown[a + 17] = 0
            put32(classIdx.toLong(), grown, a + 18 - 2)          // the data is at a+16 (size2,res0,type,data)
            // (the layout above: size u16 at a+12, res0 u8 at a+14, dataType u8 at a+15, data u32 at a+16)
        }
        // the size of the chunk
        val newChunkSize = grownSize(chunk, existing >= 0)
        put32(newChunkSize.toLong(), chunk, 4)

        // --- assemble the file ---
        val out = java.io.ByteArrayOutputStream(data.size + 64)
        // the xml header
        out.write(data.copyOfRange(0, 8))
        // the new string pool
        out.write(newPool)
        // everything between the pool and the application element
        out.write(data.copyOfRange(poolOff + poolSize, appStart))
        // the patched element
        out.write(chunk)
        // the rest
        out.write(data.copyOfRange(appStart + appSize, data.size))
        val result = out.toByteArray()
        // the size of the whole xml chunk
        put32((result.size - 8).toLong(), result, 4)
        try {
            manifest.writeBytes(result)
        } catch (e: Throwable) {
            return "cannot write the manifest: $e"
        }
        // --- self check: read it back and look for our attribute ---
        val check = data.copyOfRange(0, 0)
        val verify = verifyApplicationClass(manifest, className)
        if (!verify) return "the patched manifest does not contain the attribute"
        return null
    }

    /** Is the manifest's <application> pointing at our class? */
    fun verifyApplicationClass(manifest: File, className: String): Boolean {
        val data = try {
            manifest.readBytes()
        } catch (e: Throwable) {
            return false
        }
        if (data.size < 16) return false
        val poolOff = 8
        if (u16(data, poolOff) != RES_STRING_POOL) return false
        val poolSize = u32(data, poolOff + 4).toInt()
        val stringCount = u32(data, poolOff + 8).toInt()
        val flags = u32(data, poolOff + 16).toInt()
        val stringsStart = u32(data, poolOff + 20).toInt()
        val utf8 = (flags and UTF8_FLAG) != 0
        val strings = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val off = u32(data, poolOff + 28 + i * 4).toInt()
            strings.add(readString(data, poolOff + stringsStart + off, utf8))
        }
        val nsIdx = strings.indexOf(ANDROID_NS)
        val nameIdx = strings.indexOf(ATTR_NAME)
        val classIdx = strings.indexOf(className)
        if (nsIdx < 0 || nameIdx < 0 || classIdx < 0) return false
        var pos = poolOff + poolSize
        if (pos + 8 <= data.size && u16(data, pos) == 0x0180) pos += u32(data, pos + 4).toInt()
        while (pos + 8 <= data.size) {
            val type = u16(data, pos)
            if (type != RES_XML_START_ELEMENT && type != RES_XML_END_ELEMENT &&
                type != 0x0100 && type != 0x0101 && type != 0x0104
            ) break
            val size = u32(data, pos + 4).toInt()
            if (type == RES_XML_START_ELEMENT) {
                val el = u32(data, pos + 20).toInt()
                if (el in strings.indices && strings[el] == "application") {
                    val attrCount = u16(data, pos + 28)
                    for (i in 0 until attrCount) {
                        val a = pos + 36 + i * 20
                        if (u32(data, a).toInt() == nsIdx && u32(data, a + 4).toInt() == nameIdx &&
                            u32(data, a + 16).toInt() == classIdx
                        ) return true
                    }
                }
            }
            pos += size
        }
        return false
    }

    private fun grownSize(chunk: ByteArray, replaced: Boolean): Int =
        if (replaced) chunk.size else chunk.size + 20

    private fun readString(data: ByteArray, off: Int, utf8: Boolean): String {
        return try {
            if (utf8) {
                var p = off
                // the utf16 length
                p += if (data[p].toInt() and 0x80 != 0) 2 else 1
                // the utf8 length
                var len = data[p].toInt() and 0xFF
                if (len and 0x80 != 0) {
                    len = ((len and 0x7F) shl 8) or (data[p + 1].toInt() and 0xFF)
                    p += 2
                } else {
                    p += 1
                }
                String(data, p, len, Charsets.UTF_8)
            } else {
                val len = u16(data, off)
                String(data, off + 2, len * 2, Charsets.UTF_16LE)
            }
        } catch (e: Throwable) {
            ""
        }
    }

    /** appends a string to the pool and returns the new pool */
    private fun addString(
        data: ByteArray, poolOff: Int, poolSize: Int, stringCount: Int,
        styleCount: Int, utf8: Boolean, stringsStart: Int, value: String
    ): ByteArray {
        val pool = data.copyOfRange(poolOff, poolOff + poolSize)
        // the string data goes after the styles (the styles are at the end)
        val dataEnd = if (styleCount > 0) {
            u32(data, poolOff + 24).toInt() - poolOff
        } else {
            poolSize
        }
        // build the encoded string
        val encoded: ByteArray
        val utf16Len: Int
        if (utf8) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            utf16Len = value.length
            val b = java.io.ByteArrayOutputStream()
            b.write(w16(utf16Len))
            b.write(w16(bytes.size))
            b.write(bytes)
            b.write(0)
            encoded = b.toByteArray()
        } else {
            val bytes = value.toByteArray(Charsets.UTF_16LE)
            utf16Len = value.length
            val b = java.io.ByteArrayOutputStream()
            b.write(w16(utf16Len))
            b.write(bytes)
            b.write(w16(0))
            encoded = b.toByteArray()
        }
        // the new pool: the header + the offsets + the data
        val newStringCount = stringCount + 1
        val offsetsSize = (newStringCount + styleCount) * 4
        val headerSize = 28
        val newStringsStart = headerSize + offsetsSize
        val newData = java.io.ByteArrayOutputStream()
        // the old string data
        newData.write(pool, stringsStart, dataEnd - stringsStart)
        val newStringOffset = dataEnd - stringsStart
        newData.write(encoded)
        // the styles (they move with the data)
        val styles = if (styleCount > 0) {
            pool.copyOfRange(u32(data, poolOff + 24).toInt() - poolOff, poolSize)
        } else {
            ByteArray(0)
        }
        newData.write(styles)

        val out = java.io.ByteArrayOutputStream()
        // the header
        out.write(pool, 0, 8)
        out.write(w32((headerSize + offsetsSize + newData.size()).toLong()))   // the size
        out.write(w32(newStringCount.toLong()))
        out.write(w32(styleCount.toLong()))
        out.write(w32(u32(data, poolOff + 16)))          // the flags
        out.write(w32(newStringsStart.toLong()))
        out.write(w32(if (styleCount > 0) (newStringsStart + newData.size() - styles.size).toLong() else 0L))
        // the string offsets
        for (i in 0 until stringCount) {
            out.write(w32(u32(data, poolOff + 28 + i * 4)))
        }
        out.write(w32(newStringOffset.toLong()))
        // the style offsets
        for (i in 0 until styleCount) {
            out.write(w32(u32(data, poolOff + 28 + stringCount * 4 + i * 4)))
        }
        out.write(newData.toByteArray())
        return out.toByteArray()
    }
}
