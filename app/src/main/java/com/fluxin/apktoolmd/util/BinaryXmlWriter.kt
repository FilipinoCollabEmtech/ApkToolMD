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
 */
object BinaryXmlWriter {

    private const val CHUNK_TYPE_STRING_POOL = 0x00100001
    private const val CHUNK_TYPE_RES_XML = 0x00100101
    private const val CHUNK_START_ELEMENT = 0x00100102
    private const val CHUNK_END_ELEMENT = 0x00100103
    private const val CHUNK_RESOURCE_MAP = 0x00080001

    private const val FLAG_UTF8 = 0x00000008

    private const val ATTR_TYPE_STRING = 3
    private const val ATTR_TYPE_INT_DEC = 0x10
    private const val ATTR_TYPE_INT_HEX = 0x11
    private const val ATTR_TYPE_INT_BOOLEAN = 0x12

    private const val NO_NAMESPACE = 0xFFFFFFFFL

    private const val START_ELEMENT_HEADER_SIZE = 16
    private const val END_ELEMENT_SIZE = 16

    fun patchManifest(xml: ByteArray): ByteArray? {
        if (xml.size < 64) return null

        return try {
            patchManifestInternal(xml)
        } catch (e: Exception) {
            null
        }
    }

    private fun patchManifestInternal(xml: ByteArray): ByteArray? {
        val buf = ByteBuffer.wrap(xml)
        buf.order(ByteOrder.LITTLE_ENDIAN)

        val xmlChunkStart = findXmlChunkStart(buf, xml) ?: return null
        val xmlChunkSize = buf.getInt(xmlChunkStart + 4).toInt() and 0xFFFF

        val stringPoolStart = findStringPoolStart(xml, xmlChunkStart + 8, xmlChunkSize - 8) ?: return null
        val stringPool = parseStringPool(xml, stringPoolStart)

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

        val manifestEndPos = findManifestEnd(xml, stringPool) ?: return null

        val newElements = buildNewElements(stringPool, stringsToAdd)

        val newStringPool = rebuildStringPool(xml, stringPoolStart, stringsToAdd)

        val result = ByteArrayOutputStream()

        result.write(newStringPool)

        val stringPoolEnd = stringPoolStart + newStringPool.size
        val middleSize = manifestEndPos - stringPoolEnd
        if (middleSize > 0) result.write(xml, stringPoolEnd, middleSize)

        result.write(newElements)

        val restSize = xml.size - manifestEndPos
        if (restSize > 0) result.write(xml, manifestEndPos, restSize)

        return result.toByteArray()
    }

    private fun findXmlChunkStart(buf: ByteBuffer, xml: ByteArray): Int? {
        var pos = 0
        while (pos + 8 <= buf.capacity()) {
            val type = buf.getInt(pos) and 0xFFFFFFFFL
            val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(pos + 4).toInt() and 0xFFFF
            if (chunkSize < 8 || pos + chunkSize > buf.capacity()) break
            if (type == CHUNK_TYPE_RES_XML) return pos
            pos += chunkSize
        }
        return null
    }

    private fun findStringPoolStart(xml: ByteArray, xmlStart: Int, xmlSize: Int): Int? {
        val buf = ByteBuffer.wrap(xml)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        var pos = xmlStart
        while (pos + 8 <= buf.capacity()) {
            val type = buf.getInt(pos) and 0xFFFFFFFFL
            val chunkSize = buf.getInt(pos + 4).toInt() and 0xFFFF
            if (chunkSize < 8 || pos + chunkSize > buf.capacity()) break
            if (type == CHUNK_TYPE_STRING_POOL) return pos
            pos += chunkSize
        }
        return null
    }

    private fun parseStringPool(xml: ByteArray, spStart: Int): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        val buf = ByteBuffer.wrap(xml)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.position(spStart + 8)

        val spHeaderSize = buf.getInt().toInt() and 0xFFFF
        val stringCount = buf.getInt().toInt() and 0xFFFF
        buf.getInt() // styleCount
        val flags = buf.getInt() and 0xFFFFFFFFL
        buf.getInt() // stringsOffset

        val isUtf8 = (flags and FLAG_UTF8) != 0
        val stringDataStart = spHeaderSize.toInt()
        val stringIdxStart = stringDataStart + stringCount * 4

        for (i in 0 until stringCount) {
            val strOffset = buf.getInt(stringIdxStart + i * 4) and 0xFFFFFFFFL
            val str = readStringFromPool(xml, stringDataStart + strOffset.toInt(), isUtf8)
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

    private fun findManifestEnd(xml: ByteArray, stringPool: Map<String, Int>): Int? {
        val buf = ByteBuffer.wrap(xml)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos + 8 <= buf.capacity()) {
            val type = buf.getInt(pos) and 0xFFFFFFFFL
            val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(pos + 4).toInt() and 0xFFFF
            if (chunkSize < 8 || pos + chunkSize > buf.capacity()) break

            if (type == CHUNK_END_ELEMENT) {
                val contentsPos = pos + 8
                if (contentsPos + 8 <= buf.capacity()) {
                    buf.position(contentsPos)
                    buf.getInt() // lineNumber
                    buf.getInt() // endNs
                    val nameId = buf.getInt() and 0xFFFFFFFFL
                    val name = getStringFromPoolByIndex(xml, nameId.toInt())
                    if (name == "manifest") return pos
                }
            }
            pos += chunkSize
        }
        return null
    }

    private fun getStringFromPoolByIndex(xml: ByteArray, index: Int): String? {
        val buf = ByteBuffer.wrap(xml)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos + 8 <= buf.capacity()) {
            val type = buf.getInt(pos) and 0xFFFFFFFFL
            val chunkSize = buf.getInt(pos + 4).toInt() and 0xFFFF
            if (chunkSize < 8 || pos + chunkSize > buf.capacity()) break
            if (type == CHUNK_TYPE_STRING_POOL) {
                buf.position(pos + 8)
                buf.getInt() // spHeaderSize
                val stringCount = buf.getInt() and 0xFFFFFFFFL
                buf.getInt() // styleCount
                buf.getInt() // flags
                val stringsOffset = buf.getInt() & 0xFFFFFFFFL
                val isUtf8 = (flags and FLAG_UTF8) != 0
                val stringDataStart = buf.position()
                val stringIdxStart = stringDataStart + stringCount.toInt() * 4

                if (index >= 0 && index < stringCount) {
                    buf.position(stringIdxStart + index * 4)
                    val strOffset = buf.getInt() and 0xFFFFFFFFL
                    return readStringFromPool(xml, stringDataStart.toInt() + strOffset.toInt(), isUtf8)
                }
                return null
            }
            pos += chunkSize
        }
        return null
    }

    private fun buildNewElements(stringPool: Map<String, Int>, stringsToAdd: List<String>): ByteArray {
        val baos = ByteArrayOutputStream()

        fun idx(s: String): Int {
            val existing = stringPool[s]
            if (existing != null) return existing
            return stringPool.size + stringsToAdd.indexOf(s)
        }

        buildStartElement(baos, idx("uses-permission"), listOf(
            Pair(idx("android:name"), Pair(ATTR_TYPE_STRING, idx("android.permission.SYSTEM_ALERT_WINDOW")))
        ))
        buildEndElement(baos, idx("uses-permission"))

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

        for (pair in attributes) {
            val attrNameIdx = pair.first
            val attrValuePair = pair.second
            val attrValueIdx = attrValuePair.second
            val attrType = attrValuePair.first

            baos.write(intToBytesLE(NO_NAMESPACE.toInt()))
            baos.write(intToBytesLE(attrNameIdx))
            baos.write(intToBytesLE(attrValueIdx))
            baos.write(intToBytesLE(attrType))
            baos.write(intToBytesLE(attrValueIdx))
        }
    }

    private fun buildEndElement(baos: ByteArrayOutputStream, nameIdx: Int) {
        baos.write(intToBytesLE(CHUNK_END_ELEMENT))
        baos.write(intToBytesLE(END_ELEMENT_SIZE))
        baos.write(intToBytesLE(0)) // lineNumber
        baos.write(intToBytesLE(NO_NAMESPACE.toInt())) // endNs
        baos.write(intToBytesLE(nameIdx)) // name
    }

    private fun rebuildStringPool(xml: ByteArray, spStart: Int, newStrings: List<String>): ByteArray {
        val buf = ByteBuffer.wrap(xml)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.position(spStart + 8)

        val spHeaderSize = buf.getInt().toInt() and 0xFFFF
        val oldStringCount = buf.getInt().toInt() and 0xFFFF
        val styleCount = buf.getInt().toInt() and 0xFFFF
        val flags = buf.getInt() and 0xFFFFFFFFL
        val oldStringsOffset = buf.getInt() & 0xFFFFFFFFL

        val newStringCount = oldStringCount + newStrings.size
        val newStringsOffset = spHeaderSize.toInt() + oldStringCount * 4

        val out = ByteArrayOutputStream()

        out.write(intToBytesLE(CHUNK_TYPE_STRING_POOL))
        val stringDataSize = newStrings.fold(0) { acc, s -> acc + s.toByteArray(StandardCharsets.UTF_8).size + 2 }
        val newStylesDataSize = if (styleCount > 0) styleCount * 4 else 0
        val newChunkSize = spHeaderSize.toInt() + newStringCount * 4 + newStringDataSize + newStylesDataSize
        out.write(intToBytesLE(newChunkSize))
        out.write(intToBytesLE(newStringCount))
        out.write(intToBytesLE(styleCount))
        out.write(intToBytesLE(flags))
        out.write(intToBytesLE(newStringsOffset))
        out.write(intToBytesLE(stylesOffset))

        buf.position(spStart + 8 + spHeaderSize.toInt())
        val oldIndexData = ByteArray(oldStringCount * 4)
        buf.get(oldIndexData)
        out.write(oldIndexData)

        var currentOffset = newStringsOffset + newStringDataSize
        for (s in newStrings) {
            out.write(intToBytesLE(currentOffset))
            currentOffset += s.toByteArray(StandardCharsets.UTF_8).size + 2
        }

        buf.position(spStart + 8 + oldStringsOffset.toInt())
        val oldDataSize = (stylesOffset - oldStringsOffset).toInt()
        if (oldDataSize > 0) {
            val oldData = ByteArray(oldDataSize)
            buf.get(oldData)
            out.write(oldData)
        }

        for (s in newStrings) {
            val bytes = s.toByteArray(StandardCharsets.UTF_8)
            out.write(bytes.size)
            out.write((bytes.size shr 8).toInt().toByte())
            out.write(bytes)
            out.write(0)
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
