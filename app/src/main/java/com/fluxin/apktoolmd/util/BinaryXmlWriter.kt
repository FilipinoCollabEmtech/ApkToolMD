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
 * Parses AndroidManifest.xml binary XML and injects:
 *   <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
 *   <service android:name="com.fluxin.apktoolmd.modmenu.ModMenuOverlayService" android:exported="true" />
 *
 * Binary XML format (AOSP frameworks/base/libs/utils/ResXMLParser.cpp):
 *   Chunks: type(2) + headerSize(2) + size(4) + contents(headerSize-8 .. size-8)
 *   String pool: magic + chunkSize + stringCount + styleCount + flags + stringsOffset + stylesOffset +
 *                stringIndex[] + stringData[]
 *   Start element: type(0x0102) + chunkSize + lineNumber + startNs + name + flags +
 *                  attrCount + attrClassId + attrStyleId + attributes[n*20]
 *   Each attribute: ns(4) + name(4) + rawValue(4) + type(4) + data(4)
 *   End element: type(0x0103) + 16 + lineNumber + endNs + name
 */
object BinaryXmlWriter {

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

    private const val START_ELEMENT_HEADER_SIZE = 16 // type+size+line+startNs+name+flags+attrCount+attrClass
    private const val END_ELEMENT_SIZE = 16

    /**
     * Patch AndroidManifest.xml binary XML to inject uses-permission + service elements
     * before </manifest>.
     */
    fun patchManifest(xmlBytes: ByteArray): ByteArray? {
        if (xmlBytes.size < 64) return null

        try {
            return patchManifestInternal(xmlBytes)
        } catch (e: Exception) {
            return null
        }
    }

    private fun patchManifestInternal(xml: ByteArray): ByteArray? {
        val buf = ByteBuffer.wrap(xml)
        buf.order(ByteOrder.LITTLE_ENDIAN)

        // Find the XML chunk (skip ZIP local header if present)
        val xmlChunkStart = findXmlChunkStart(buf, xml) ?: return null
        val xmlChunkSize = buf.getInt(xmlChunkStart + 4) & 0xFFFFFFFFL
        val xmlContentStart = xmlChunkStart + 8

        // Parse string pool from within XML chunk
        val stringPoolStart = findStringPoolStart(xml, xmlContentStart, xmlChunkSize.toInt()) ?: return null
        val stringPool = parseStringPool(xml, stringPoolStart)

        // Strings we need to add
        val stringsToAdd = mutableListOf<String>()
        fun ensureString(s: String) {
            if (!stringPool.containsKey(s)) stringsToAdd.add(s)
        }
        ensureString("uses-permission")
        ensureString("android:name")
        ensureString("android.permission.SYSTEM_ALERT_WINDOW")
        ensureString("service")
        ensureString("android:exported")
        ensureString("true")
        ensureString("com.fluxin.apktoolmd.modmenu.ModMenuOverlayService")

        // Find </manifest> end element position
        val manifestEndPos = findManifestEnd(xml, stringPool) ?: return null

        // Build new element chunks
        val newElements = buildNewElements(stringPool, stringsToAdd)

        // Rebuild string pool with new strings
        val newStringPool = rebuildStringPool(xml, stringPoolStart, stringsToAdd)

        // Assemble: [stringPool][newElements][rest of XML after manifest end]
        val result = ByteArrayOutputStream()

        // Copy string pool (with new strings)
        result.write(newStringPool)

        // Copy everything between string pool end and manifest end (unchanged)
        val stringPoolEnd = stringPoolStart + newStringPool.size
        val middleSize = manifestEndPos - stringPoolEnd
        if (middleSize > 0) result.write(xml, stringPoolEnd, middleSize)

        // Insert new elements before </manifest>
        result.write(newElements)

        // Copy rest after manifest end
        val restSize = xml.size - manifestEndPos
        if (restSize > 0) result.write(xml, manifestEndPos, restSize)

        return result.toByteArray()
    }

    /** Find the start of the RES_XML chunk within the file. */
    private fun findXmlChunkStart(buf: ByteBuffer, xml: ByteArray): Int? {
        var pos = 0
        while (pos + 8 <= buf.capacity()) {
            val type = buf.getInt(pos) & 0xFFFFFFFFL
            val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(pos + 4) & 0xFFFFFFFFL
            if (chunkSize < 8 || pos + chunkSize > buf.capacity()) break
            if (type == CHUNK_TYPE_RES_XML) return pos
            pos += chunkSize.toInt()
        }
        return null
    }

    /** Find the string pool chunk start within XML content. */
    private fun findStringPoolStart(xml: ByteArray, xmlStart: Int, xmlSize: Int): Int? {
        val buf = ByteBuffer.wrap(xml)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        var pos = xmlStart
        while (pos + 8 <= buf.capacity()) {
            val type = buf.getInt(pos) & 0xFFFFFFFFL
            val chunkSize = buf.getInt(pos + 4) & 0xFFFFFFFFL
            if (chunkSize < 8 || pos + chunkSize > buf.capacity()) break
            if (type == CHUNK_TYPE_STRING_POOL) return pos
            pos += chunkSize.toInt()
        }
        return null
    }

    /** Parse string pool: map string -> index. */
    private fun parseStringPool(xml: ByteArray, spStart: Int): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        val buf = ByteBuffer.wrap(xml)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.position(spStart + 8) // skip chunk header

        val spHeaderSize = buf.getInt() & 0xFFFFFFFFL
        val stringCount = buf.getInt() & 0xFFFFFFFFL
        val styleCount = buf.getInt() & 0xFFFFFFFFL
        val flags = buf.getInt() & 0xFFFFFFFFL
        val stringsOffset = buf.getInt() & 0xFFFFFFFFL

        val isUtf8 = (flags and FLAG_UTF8) != 0
        val stringDataStart = spHeaderSize.toInt()
        val stringIdxStart = stringDataStart + stringCount.toInt() * 4

        for (i in 0 until stringCount.toInt()) {
            val strOffset = buf.getInt(stringIdxStart + i * 4) & 0xFFFFFFFFL
            val str = readStringFromPool(xml, stringDataStart.toInt() + strOffset.toInt(), isUtf8)
            if (str != null) result[str] = i
        }
        return result
    }

    private fun readStringFromPool(xml: ByteArray, offset: Int, isUtf8: Boolean): String? {
        if (offset + 2 > xml.size) return null
        val len = (xml[offset].toInt() and 0xFF) or ((xml[offset + 1].toInt() and 0xFF) shl 8)
        if (len == 0) return ""
        if (offset + 2 + len > xml.size) return null
        val bytes = ByteArray(len)
        System.arraycopy(xml, offset + 2, bytes, 0, len)
        return String(bytes, StandardCharsets.UTF_8)
    }

    /** Find position of </manifest> end element within XML content. */
    private fun findManifestEnd(xml: ByteArray, stringPool: Map<String, Int>): Int? {
        val buf = ByteBuffer.wrap(xml)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos + 8 <= buf.capacity()) {
            val type = buf.getInt(pos) & 0xFFFFFFFFL
            val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(pos + 4) & 0xFFFFFFFFL
            if (chunkSize < 8 || pos + chunkSize > buf.capacity()) break

            if (type == CHUNK_END_ELEMENT) {
                val contentsPos = pos + 8
                if (contentsPos + 8 <= buf.capacity()) {
                    buf.position(contentsPos)
                    buf.getInt() // skip lineNumber
                    buf.getInt() // skip endNs
                    val nameId = buf.getInt() & 0xFFFFFFFFL
                    val name = getStringFromPoolByIndex(xml, nameId.toInt())
                    if (name == "manifest") return pos
                }
            }
            pos += chunkSize.toInt()
        }
        return null
    }

    private fun getStringFromPoolByIndex(xml: ByteArray, index: Int): String? {
        val buf = ByteBuffer.wrap(xml)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos + 8 <= buf.capacity()) {
            val type = buf.getInt(pos) & 0xFFFFFFFFL
            val chunkSize = buf.getInt(pos + 4) & 0xFFFFFFFFL
            if (chunkSize < 8 || pos + chunkSize > buf.capacity()) break
            if (type == CHUNK_TYPE_STRING_POOL) {
                buf.position(pos + 8)
                buf.getInt() // spHeaderSize
                val stringCount = buf.getInt() & 0xFFFFFFFFL
                val styleCount = buf.getInt() & 0xFFFFFFFFL
                val flags = buf.getInt() & 0xFFFFFFFFL
                val stringsOffset = buf.getInt() & 0xFFFFFFFFL
                val isUtf8 = (flags and FLAG_UTF8) != 0
                val stringDataStart = buf.position()
                val stringIdxStart = stringDataStart + stringCount.toInt() * 4

                if (index >= 0 && index < stringCount) {
                    buf.position(stringIdxStart + index * 4)
                    val strOffset = buf.getInt() & 0xFFFFFFFFL
                    return readStringFromPool(xml, stringDataStart.toInt() + strOffset.toInt(), isUtf8)
                }
                return null
            }
            pos += chunkSize.toInt()
        }
        return null
    }

    /** Build new element chunks for uses-permission and service. */
    private fun buildNewElements(stringPool: Map<String, Int>, stringsToAdd: List<String>): ByteArray {
        val baos = ByteArrayOutputStream()

        fun idx(s: String): Int = stringPool[s] ?: stringsToAdd.indexOf(s) + stringPool.size

        // <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
        buildStartElement(baos, idx("uses-permission"), listOf(
            Pair(idx("android:name"), Pair(ATTR_TYPE_STRING, idx("android.permission.SYSTEM_ALERT_WINDOW")))
        ))
        buildEndElement(baos, idx("uses-permission"))

        // <service android:name="com.fluxin.apktoolmd.modmenu.ModMenuOverlayService" android:exported="true" />
        buildStartElement(baos, idx("service"), listOf(
            Pair(idx("android:name"), Pair(ATTR_TYPE_STRING, idx("com.fluxin.apktoolmd.modmenu.ModMenuOverlayService"))),
            Pair(idx("android:exported"), Pair(ATTR_TYPE_STRING, idx("true")))
        ))
        buildEndElement(baos, idx("service"))

        return baos.toByteArray()
    }

    private fun buildStartElement(baos: ByteArrayOutputStream, nameIdx: Int,
                                  attributes: List<Pair<Int, Pair<Int, Int>>>) {
        val attrCount = attributes.size
        val chunkSize = START_ELEMENT_HEADER_SIZE + attrCount * 20

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

        for ((attrNameIdx, (attrType, attrValueIdx)) in attributes) {
            baos.write(intToBytesLE(NO_NAMESPACE.toInt())) // ns
            baos.write(intToBytesLE(attrNameIdx)) // name
            baos.write(intToBytesLE(attrValueIdx)) // rawValue (string ref)
            baos.write(intToBytesLE(attrType)) // type
            baos.write(intToBytesLE(attrValueIdx)) // data
        }
    }

    private fun buildEndElement(baos: ByteArrayOutputStream, nameIdx: Int) {
        baos.write(intToBytesLE(CHUNK_END_ELEMENT))
        baos.write(intToBytesLE(END_ELEMENT_SIZE)) // chunkSize
        baos.write(intToBytesLE(0)) // lineNumber
        baos.write(intToBytesLE(NO_NAMESPACE.toInt())) // endNs
        baos.write(intToBytesLE(nameIdx)) // name
    }

    /** Rebuild string pool with new strings appended. */
    private fun rebuildStringPool(xml: ByteArray, spStart: Int, newStrings: List<String>): ByteArray {
        val buf = ByteBuffer.wrap(xml)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.position(spStart + 8)

        val spHeaderSize = buf.getInt() & 0xFFFFFFFFL
        val oldStringCount = buf.getInt() & 0xFFFFFFFFL
        val styleCount = buf.getInt() & 0xFFFFFFFFL
        val flags = buf.getInt() & 0xFFFFFFFFL
        val oldStringsOffset = buf.getInt() & 0xFFFFFFFFL
        val stylesOffset = buf.getInt() & 0xFFFFFFFFL

        val newStringCount = oldStringCount.toInt() + newStrings.size
        val newStringsOffset = spHeaderSize.toInt() + oldStringCount.toInt() * 4

        val out = ByteArrayOutputStream()

        // String pool header
        out.write(intToBytesLE(CHUNK_TYPE_STRING_POOL))
        val stringDataSize = newStrings.fold(0) { acc, s -> acc + s.toByteArray(StandardCharsets.UTF_8).size + 2 } // 2 = length prefix
        val newStylesDataSize = if (styleCount > 0) styleCount.toInt() * 4 else 0
        val newChunkSize = spHeaderSize.toInt() + newStringCount * 4 + newStringDataSize + newStylesDataSize
        out.write(intToBytesLE(newChunkSize))
        out.write(intToBytesLE(newStringCount))
        out.write(intToBytesLE(styleCount))
        out.write(intToBytesLE(flags))
        out.write(intToBytesLE(newStringsOffset))
        out.write(intToBytesLE(stylesOffset))

        // Copy old string index table
        buf.position(spStart + 8 + spHeaderSize.toInt())
        val oldIndexData = ByteArray(oldStringCount.toInt() * 4)
        buf.get(oldIndexData)
        out.write(oldIndexData)

        // Write new string index offsets
        var currentOffset = newStringsOffset.toInt() + newStringDataSize
        for (s in newStrings) {
            out.write(intToBytesLE(currentOffset))
            currentOffset += s.toByteArray(StandardCharsets.UTF_8).size + 2
        }

        // Copy old string data
        buf.position(spStart + 8 + oldStringsOffset.toInt())
        val oldDataSize = (stylesOffset - oldStringsOffset).toInt()
        if (oldDataSize > 0) {
            val oldData = ByteArray(oldDataSize)
            buf.get(oldData)
            out.write(oldData)
        }

        // Write new string data (length-prefixed UTF-8)
        for (s in newStrings) {
            val bytes = s.toByteArray(StandardCharsets.UTF_8)
            out.write(bytes.length.toByte())
            out.write((bytes.length shr 8).toByte())
            out.write(bytes)
            out.write(0) // null terminator
        }

        return out.toByteArray()
    }

    private fun intToBytesLE(v: Int): ByteArray =
        byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte()
        )
}
