package com.fluxin.apktoolmd.util

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.List
import java.util.Map

/**
 * Binary XML (AXML) reader/writer based on MP-Manager's AndroidBinXmlParser (AOSP apksig).
 *
 * Supports parsing AndroidManifest.xml binary XML and injecting
 * <uses-permission> and <service> elements before </manifest>.
 *
 * Binary XML format reference:
 *   https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/libs/utils/ResXMLParser.cpp
 *   https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/libs/androidfw/ResourceTypes.h
 */
object BinaryXmlWriter {

    // Chunk types
    private const val CHUNK_TYPE_STRING_POOL = 0x00100001
    private const val CHUNK_TYPE_RES_XML = 0x00100101
    private const val CHUNK_START_ELEMENT = 0x00100102
    private const val CHUNK_END_ELEMENT = 0x00100103
    private const val CHUNK_RESOURCE_MAP = 0x00080001

    private const val FLAG_UTF8 = 0x00000008

    // Attribute value types
    private const val ATTR_TYPE_STRING = 3
    private const val ATTR_TYPE_INT_DEC = 0x10
    private const val ATTR_TYPE_INT_HEX = 0x11
    private const val ATTR_TYPE_INT_BOOLEAN = 0x12

    private const val NO_NAMESPACE = 0xFFFFFFFFL

    // XML element chunk header size (before attributes)
    private const val START_ELEMENT_HEADER_SIZE = 16 // type(2)+size(2)+line(4)+startNs(4)+name(4)+flags(4)+attrCount(4)+attrClass(4)
    private const val END_ELEMENT_SIZE = 16

    /**
     * Patch AndroidManifest.xml binary XML to inject:
     *   <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
     *   <service android:name="com.fluxin.apktoolmd.modmenu.ModMenuOverlayService" android:exported="true" />
     *
     * Returns patched bytes, or null if patching fails.
     */
    fun patchManifest(xmlBytes: ByteArray): ByteArray? {
        if (xmlBytes.size < 64) return null

        try {
            return patchManifestInternal(xmlBytes)
        } catch (e: Exception) {
            return null
        }
    }

    private fun patchManifestInternal(xmlBytes: ByteArray): ByteArray? {
        val buf = ByteBuffer.wrap(xmlBytes)
        buf.order(ByteOrder.LITTLE_ENDIAN)

        // Find XML chunk
        var xmlChunkStart = -1
        var xmlChunkSize = 0
        var pos = 0
        while (pos + 8 <= buf.capacity()) {
            val type = buf.getInt(pos) & 0xFFFFFFFFL
            val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(pos + 4) & 0xFFFFFFFFL
            if (chunkSize < 8 || pos + chunkSize > buf.capacity()) break
            if (type == CHUNK_TYPE_RES_XML) {
                xmlChunkStart = pos
                xmlChunkSize = chunkSize.toInt()
                break
            }
            pos += chunkSize.toInt()
        }

        if (xmlChunkStart < 0) return null

        val xmlContents = ByteArray(xmlChunkSize - 8)
        buf.position(xmlChunkStart + 8)
        buf.get(xmlContents)

        // Parse string pool to find existing strings
        val stringPool = parseStringPool(xmlContents)
        if (stringPool == null) return null

        // Strings we need
        val stringsToAdd = mutableListOf<String>()
        fun ensureString(s: String) {
            if (!stringPool.containsKey(s)) {
                stringsToAdd.add(s)
            }
        }
        ensureString("uses-permission")
        ensureString("android:name")
        ensureString("android.permission.SYSTEM_ALERT_WINDOW")
        ensureString("service")
        ensureString("android:exported")
        ensureString("true")
        ensureString("com.fluxin.apktoolmd.modmenu.ModMenuOverlayService")

        // Build new string pool entries
        val newStringData = buildNewStringData(stringsToAdd, stringPool)

        // Find </manifest> end element position within xmlContents
        val manifestEndPos = findManifestEndElement(xmlContents, stringPool)
        if (manifestEndPos < 0) return null

        // Build new element chunks
        val newElementChunks = buildElementChunks(stringPool)

        // Assemble new XML: [original up to manifestEnd] + [new chunks] + [rest from manifestEnd]
        val result = ByteArrayOutputStream()
        result.write(xmlContents, 0, manifestEndPos)
        result.write(newElementChunks)
        result.write(xmlContents, manifestEndPos, xmlContents.size - manifestEndPos)

        // Rebuild string pool with new strings
        val newStringPool = rebuildStringPool(xmlContents, stringPool, stringsToAdd, newStringData)

        // Replace string pool in result
        val stringPoolStart = findStringPoolStart(xmlContents)
        if (stringPoolStart < 0) return null

        val finalResult = ByteArrayOutputStream()
        finalResult.write(xmlContents, 0, stringPoolStart)
        finalResult.write(newStringPool)
        // Skip old string pool in result
        val oldStringPoolEnd = findStringPoolEnd(xmlContents, stringPoolStart)
        finalResult.write(xmlContents, oldStringPoolEnd, manifestEndPos - oldStringPoolEnd)
        finalResult.write(newElementChunks)
        finalResult.write(xmlContents, manifestEndPos, xmlContents.size - manifestEndPos)

        return finalResult.toByteArray()
    }

    /** Parse string pool to map string -> index. */
    private fun parseStringPool(xml: ByteArray): Map<String, Int>? {
        val buf = ByteBuffer.wrap(xml)
        buf.order(ByteOrder.LITTLE_ENDIAN)

        // Find string pool chunk
        var pos = 0
        while (pos + 8 <= buf.capacity()) {
            val type = buf.getInt(pos) & 0xFFFFFFFFL
            val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(pos + 4) & 0xFFFFFFFFL
            if (chunkSize < 8 || pos + chunkSize > buf.capacity()) break
            if (type == CHUNK_TYPE_STRING_POOL) {
                // Parse string pool
                val spBuf = ByteBuffer.allocate(chunkSize.toInt())
                spBuf.order(ByteOrder.LITTLE_ENDIAN)
                spBuf.put(xml, pos + 8, chunkSize.toInt() - 8)
                spBuf.position(0)

                val spHeaderSize = spBuf.getInt() & 0xFFFFFFFFL
                val stringCount = spBuf.getInt() & 0xFFFFFFFFL
                val styleCount = spBuf.getInt() & 0xFFFFFFFFL
                val flags = spBuf.getInt() & 0xFFFFFFFFL
                val stringsOffset = spBuf.getInt() & 0xFFFFFFFFL
                val stylesOffset = spBuf.getInt() & 0xFFFFFFFFL

                val isUtf8 = (flags and FLAG_UTF8) != 0
                val stringDataStart = spHeaderSize.toInt()
                val stringIdxStart = stringDataStart + stringCount.toInt() * 4

                val result = LinkedHashMap<String, Int>()
                for (i in 0 until stringCount.toInt()) {
                    val strOffset = spBuf.getInt(stringIdxStart + i * 4) & 0xFFFFFFFFL
                    val str = readStringFromPool(spBuf, stringDataStart.toInt() + strOffset.toInt(), isUtf8)
                    if (str != null) {
                        result[str] = i
                    }
                }
                return result
            }
            pos += chunkSize.toInt()
        }
        return null
    }

    private fun readStringFromPool(buf: ByteBuffer, offset: Int, isUtf8: Boolean): String? {
        if (offset + 2 > buf.capacity()) return null
        val len = buf.getShort(offset).toInt() and 0xFFFF
        if (len == 0) return ""
        if (offset + 2 + len > buf.capacity()) return null
        val bytes = ByteArray(len)
        buf.position(offset + 2)
        buf.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    /** Find the position of </manifest> end element within XML content. */
    private fun findManifestEndElement(xml: ByteArray, stringPool: Map<String, Int>): Int {
        val buf = ByteBuffer.wrap(xml)
        buf.order(ByteOrder.LITTLE_ENDIAN)

        var pos = 0
        while (pos + 8 <= buf.capacity()) {
            val type = buf.getInt(pos) & 0xFFFFFFFFL
            val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(pos + 4) & 0xFFFFFFFFL
            if (chunkSize < 8 || pos + chunkSize > buf.capacity()) break

            if (type == CHUNK_END_ELEMENT) {
                // Read name index
                val contentsPos = pos + 8
                if (contentsPos + 8 <= buf.capacity()) {
                    buf.position(contentsPos)
                    buf.getInt() // skip lineNumber
                    buf.getInt() // skip endNs
                    val nameId = buf.getInt() & 0xFFFFFFFFL
                    val name = getStringFromPoolByIndex(xml, nameId.toInt())
                    if (name == "manifest") {
                        return pos
                    }
                }
            }
            pos += chunkSize.toInt()
        }
        return -1
    }

    private fun getStringFromPoolByIndex(xml: ByteArray, index: Int): String? {
        // Find string pool and lookup
        val buf = ByteBuffer.wrap(xml)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos + 8 <= buf.capacity()) {
            val type = buf.getInt(pos) & 0xFFFFFFFFL
            val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(pos + 4) & 0xFFFFFFFFL
            if (chunkSize < 8 || pos + chunkSize > buf.capacity()) break
            if (type == CHUNK_TYPE_STRING_POOL) {
                val spBuf = ByteBuffer.allocate(chunkSize.toInt() - 8)
                spBuf.order(ByteOrder.LITTLE_ENDIAN)
                spBuf.put(xml, pos + 8, chunkSize.toInt() - 8)
                spBuf.position(0)
                spBuf.getInt() // headerSize
                val stringCount = spBuf.getInt() & 0xFFFFFFFFL
                val styleCount = spBuf.getInt() & 0xFFFFFFFFL
                val flags = spBuf.getInt() & 0xFFFFFFFFL
                val stringsOffset = spBuf.getInt() & 0xFFFFFFFFL
                val isUtf8 = (flags and FLAG_UTF8) != 0
                val stringDataStart = spHeaderSize.toInt()
                val stringIdxStart = stringDataStart + stringCount.toInt() * 4

                if (index >= 0 && index < stringCount) {
                    spBuf.position(stringIdxStart + index * 4)
                    val strOffset = spBuf.getInt() & 0xFFFFFFFFL
                    return readStringFromPool(spBuf, stringDataStart.toInt() + strOffset.toInt(), isUtf8)
                }
                return null
            }
            pos += chunkSize.toInt()
        }
        return null
    }

    /** Find string pool chunk start position within XML content. */
    private fun findStringPoolStart(xml: ByteArray): Int {
        val buf = ByteBuffer.wrap(xml)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos + 8 <= buf.capacity()) {
            val type = buf.getInt(pos) & 0xFFFFFFFFL
            val chunkSize = buf.getInt(pos + 4) & 0xFFFFFFFFL
            if (chunkSize < 8 || pos + chunkSize > buf.capacity()) break
            if (type == CHUNK_TYPE_STRING_POOL) {
                return pos
            }
            pos += chunkSize.toInt()
        }
        return -1
    }

    /** Find end of string pool chunk. */
    private fun findStringPoolEnd(xml: ByteArray, start: Int): Int {
        val buf = ByteBuffer.wrap(xml)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.position(start + 4)
        val chunkSize = buf.getInt() & 0xFFFFFFFFL
        return start + chunkSize.toInt()
    }

    /** Build new string data for additional strings. */
    private fun buildNewStringData(strings: List<String>, existingPool: Map<String, Int>): ByteArray {
        val baos = ByteArrayOutputStream()
        for (s in strings) {
            val bytes = s.toByteArray(StandardCharsets.UTF_8)
            baos.write(bytes.length.toByte())
            baos.write((bytes.length shr 8).toByte())
            baos.write(bytes)
            baos.write(0) // null terminator
        }
        return baos.toByteArray()
    }

    /** Rebuild string pool with new strings appended. */
    private fun rebuildStringPool(xml: ByteArray, oldPool: Map<String, Int>, newStrings: List<String>, newStringData: ByteArray): ByteArray? {
        val buf = ByteBuffer.wrap(xml)
        buf.order(ByteOrder.LITTLE_ENDIAN)

        var pos = 0
        while (pos + 8 <= buf.capacity()) {
            val type = buf.getInt(pos) & 0xFFFFFFFFL
            val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(pos + 4) & 0xFFFFFFFFL
            if (chunkSize < 8 || pos + chunkSize > buf.capacity()) break
            if (type == CHUNK_TYPE_STRING_POOL) {
                // Read old pool header
                buf.position(pos + 8)
                val spHeaderSize = buf.getInt() & 0xFFFFFFFFL
                val oldStringCount = buf.getInt() & 0xFFFFFFFFL
                val styleCount = buf.getInt() & 0xFFFFFFFFL
                val flags = buf.getInt() & 0xFFFFFFFFL
                val oldStringsOffset = buf.getInt() & 0xFFFFFFFFL
                val stylesOffset = buf.getInt() & 0xFFFFFFFFL

                val newStringCount = oldStringCount.toInt() + newStrings.size
                val newStringsOffset = spHeaderSize.toInt() + oldStringCount.toInt() * 4

                val out = ByteArrayOutputStream()

                // Write header
                out.write(intToBytesLE(CHUNK_TYPE_STRING_POOL))
                val newChunkSize = spHeaderSize.toInt() + newStringCount * 4 + newStringData.size + (if (styleCount > 0) styleCount.toInt() * 4 else 0)
                out.write(intToBytesLE(newChunkSize))
                out.write(intToBytesLE(newStringCount))
                out.write(intToBytesLE(styleCount))
                out.write(intToBytesLE(flags))
                out.write(intToBytesLE(newStringsOffset))
                out.write(intToBytesLE(stylesOffset))

                // Copy old string index table
                buf.position(pos + 8 + spHeaderSize.toInt())
                val oldIndexData = ByteArray(oldStringCount.toInt() * 4)
                buf.get(oldIndexData)
                out.write(oldIndexData)

                // Write new string index offsets
                var currentOffset = newStringsOffset.toInt() + newStringData.size
                for (s in newStrings) {
                    out.write(intToBytesLE(currentOffset))
                    currentOffset += s.toByteArray(StandardCharsets.UTF_8).size + 2 // length(2) + data + null(1) - actually length prefix is 2 bytes
                }

                // Copy old string data
                buf.position(pos + 8 + oldStringsOffset.toInt())
                val oldStringDataSize = (stylesOffset - oldStringsOffset).toInt()
                if (oldStringDataSize > 0) {
                    val oldData = ByteArray(oldStringDataSize)
                    buf.get(oldData)
                    out.write(oldData)
                }

                // Write new string data
                out.write(newStringData)

                return out.toByteArray()
            }
            pos += chunkSize.toInt()
        }
        return null
    }

    /** Build binary XML chunks for new elements. */
    private fun buildElementChunks(stringPool: Map<String, Int>): ByteArray {
        val baos = ByteArrayOutputStream()

        // Get string indices
        fun idx(s: String): Int = stringPool[s] ?: -1

        // <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
        buildStartElementChunk(
            baos,
            idx("uses-permission"),
            listOf(
                Pair(idx("android:name"), Pair(ATTR_TYPE_STRING, idx("android.permission.SYSTEM_ALERT_WINDOW")))
            )
        )
        buildEndElementChunk(baos, idx("uses-permission"))

        // <service android:name="com.fluxin.apktoolmd.modmenu.ModMenuOverlayService" android:exported="true" />
        buildStartElementChunk(
            baos,
            idx("service"),
            listOf(
                Pair(idx("android:name"), Pair(ATTR_TYPE_STRING, idx("com.fluxin.apktoolmd.modmenu.ModMenuOverlayService"))),
                Pair(idx("android:exported"), Pair(ATTR_TYPE_STRING, idx("true")))
            )
        )
        buildEndElementChunk(baos, idx("service"))

        return baos.toByteArray()
    }

    private fun buildStartElementChunk(baos: ByteArrayOutputStream, nameIdx: Int, attributes: List<Pair<Int, Pair<Int, Int>>>) {
        val attrCount = attributes.size
        val chunkSize = 16 + attrCount * 20 // 16 byte header + 20 bytes per attribute

        baos.write(intToBytesLE(CHUNK_START_ELEMENT))
        baos.write(intToBytesLE(chunkSize))
        baos.write(intToBytesLE(0)) // lineNumber
        baos.write(intToBytesLE(NO_NAMESPACE.toInt())) // startNs
        baos.write(intToBytesLE(nameIdx)) // name
        baos.write(intToBytesLE(0)) // flags
        baos.write(intToBytesLE(attrCount)) // attributeCount
        baos.write(intToBytesLE(0xFFFFFFFF)) // attributeId
        baos.write(intToBytesLE(0xFFFFFFFF)) // classAttribute
        baos.write(intToBytesLE(0xFFFFFFFF)) // styleAttribute

        for ((attrNameIdx, (attrType, attrValue)) in attributes) {
            baos.write(intToBytesLE(NO_NAMESPACE.toInt())) // ns
            baos.write(intToBytesLE(attrNameIdx)) // name
            baos.write(intToBytesLE(attrValue)) // rawValue (string ref)
            baos.write(intToBytesLE(attrType)) // type
            baos.write(intToBytesLE(attrValue)) // data
        }
    }

    private fun buildEndElementChunk(baos: ByteArrayOutputStream, nameIdx: Int) {
        baos.write(intToBytesLE(CHUNK_END_ELEMENT))
        baos.write(intToBytesLE(16)) // chunkSize
        baos.write(intToBytesLE(0)) // lineNumber
        baos.write(intToBytesLE(NO_NAMESPACE.toInt())) // endNs
        baos.write(intToBytesLE(nameIdx)) // name
    }

    private fun intToBytesLE(v: Int): ByteArray =
        byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte()
        )
}
