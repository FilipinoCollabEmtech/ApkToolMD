package com.fluxin.apktoolmd.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * A surgical ZIP patcher.
 *
 * The normal re-pack (read every entry, write it again) rebuilds the whole
 * archive, and that is enough to break a game: the compression of the
 * entries changes, the package manager loads the libraries differently and
 * some games simply die at startup.
 *
 * This one keeps the archive byte identical except for the entries we
 * really change:
 *   * every other entry is copied verbatim (local header + compressed data)
 *   * the entry we replace is written with the SAME compression method, so
 *     the package manager treats it exactly like the original
 *   * the new entries use the same method too
 *
 * Nothing is re-deflated, so a signed package keeps everything but the
 * bytes we deliberately changed.
 */
object SurgicalZip {

    private const val SIG_LOCAL = 0x04034b50L
    private const val SIG_CENTRAL = 0x02014b50L
    private const val SIG_EOCD = 0x06054b50L

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
                ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or
                ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun put16(v: Int, out: ByteArrayOutputStream) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun put32(v: Long, out: ByteArrayOutputStream) {
        out.write((v.toInt()) and 0xFF)
        out.write(((v shr 8).toInt()) and 0xFF)
        out.write(((v shr 16).toInt()) and 0xFF)
        out.write(((v shr 24).toInt()) and 0xFF)
    }

    /** deflate a buffer the way a ZIP entry does (raw deflate, no header) */
    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(data)
        d.finish()
        val bos = ByteArrayOutputStream(data.size)
        val buf = ByteArray(65536)
        while (!d.finished()) {
            val n = d.deflate(buf)
            bos.write(buf, 0, n)
        }
        d.end()
        return bos.toByteArray()
    }

    /**
     * @param replace the entries that are replaced (name -> new bytes)
     * @param add    the new entries (name -> bytes)
     * @return null on success, otherwise the reason it failed
     */
    fun patch(
        input: File,
        output: File,
        replace: Map<String, ByteArray>,
        add: Map<String, ByteArray>
    ): String? {
        val src = RandomAccessFile(input, "r")
        val out = FileOutputStream(output)
        try {
            val len = src.length()
            val tailLen = minOf(len, 66000L).toInt()
            val tail = ByteArray(tailLen)
            src.seek(len - tailLen)
            src.readFully(tail)

            var eocd = -1
            for (i in tailLen - 22 downTo 0) {
                if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() &&
                    tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()) {
                    eocd = i
                    break
                }
            }
            if (eocd < 0) return "no end of central directory"

            val entryCount = u16(tail, eocd + 10)
            val cdSize = u32(tail, eocd + 12).toInt()
            val cdOffset = u32(tail, eocd + 16)
            val cd = ByteArray(cdSize)
            src.seek(cdOffset)
            src.readFully(cd)

            val cdOut = ByteArrayOutputStream(cdSize + 65536)
            var outPos = 0L
            var p = 0
            var written = 0

            for (i in 0 until entryCount) {
                if (p + 46 > cd.size) return "truncated central directory"
                if (u32(cd, p) != SIG_CENTRAL) return "bad central directory"
                val flags = u16(cd, p + 8)
                val method = u16(cd, p + 10)
                val crc = u32(cd, p + 16)
                val csize = u32(cd, p + 20)
                val usize = u32(cd, p + 24)
                val nlen = u16(cd, p + 28)
                val elen = u16(cd, p + 30)
                val clen = u16(cd, p + 32)
                val lho = u32(cd, p + 42)
                val name = String(cd, p + 46, nlen, Charsets.UTF_8)

                if (name.endsWith("/")) {
                    // a directory: copy it verbatim
                    copyRaw(src, out, lho, 0, 0)
                    outPos += 0
                }

                val newData = replace[name]
                if (newData != null) {
                    // write the entry with the SAME compression method
                    val payload: ByteArray
                    val outMethod: Int
                    val outCrc: Long
                    val outCsize: Long
                    val outUsize: Long
                    if (method == 0) {
                        payload = newData
                        outMethod = 0
                        outCrc = crc32(newData)
                        outCsize = newData.size.toLong()
                        outUsize = newData.size.toLong()
                    } else {
                        payload = deflate(newData)
                        outMethod = 8
                        outCrc = crc32(newData)
                        outCsize = payload.size.toLong()
                        outUsize = newData.size.toLong()
                    }
                    val newOffset = outPos
                    writeLocal(out, name, outMethod, outCrc, outCsize, outUsize, payload, flags)
                    outPos += 30 + name.toByteArray(Charsets.UTF_8).size + payload.size
                    writeCentral(cdOut, name, flags, outMethod, outCrc, outCsize, outUsize, newOffset)
                    written++
                } else {
                    // copy the entry verbatim: the local header + the data
                    src.seek(lho)
                    val local = ByteArray(30)
                    src.readFully(local)
                    if (u32(local, 0) != SIG_LOCAL) return "bad local header for $name"
                    val lnlen = u16(local, 26)
                    val lelen = u16(local, 28)
                    val head = ByteArray(30 + lnlen + lelen)
                    src.seek(lho)
                    src.readFully(head)
                    val data = ByteArray(csize.toInt())
                    if (csize > 0) src.readFully(data)
                    out.write(head)
                    out.write(data)
                    val newOffset = outPos
                    outPos += (head.size + data.size).toLong()
                    writeCentral(cdOut, name, flags, method, crc, csize, usize, newOffset)
                    written++
                }
                p += 46 + nlen + elen + clen
            }

            // the new entries
            for ((name, data) in add) {
                val method = if (replace.isEmpty()) 0 else 8
                val payload = if (method == 0) data else deflate(data)
                val c = crc32(data)
                val newOffset = outPos
                writeLocal(out, name, method, c, payload.size.toLong(), data.size.toLong(), payload, 0)
                outPos += 30 + name.toByteArray(Charsets.UTF_8).size + payload.size
                writeCentral(cdOut, name, 0, method, c, payload.size.toLong(), data.size.toLong(), newOffset)
                written++
            }

            // the central directory + the end of it
            val cdBytes = cdOut.toByteArray()
            out.write(cdBytes)
            val eocdOut = ByteArrayOutputStream(22)
            put32(SIG_EOCD, eocdOut)
            put16(0, eocdOut)
            put16(0, eocdOut)
            put16(written, eocdOut)
            put16(written, eocdOut)
            put32(cdBytes.size.toLong(), eocdOut)
            put32(outPos, eocdOut)
            put16(0, eocdOut)
            out.write(eocdOut.toByteArray())
            out.flush()
            return null
        } catch (e: Throwable) {
            return e.toString()
        } finally {
            try { out.close() } catch (ignored: Throwable) {}
            try { src.close() } catch (ignored: Throwable) {}
        }
    }

    private fun crc32(data: ByteArray): Long {
        val c = CRC32()
        c.update(data)
        return c.value
    }

    private fun copyRaw(src: RandomAccessFile, out: FileOutputStream, lho: Long, skip: Long, count: Long) {
        if (count <= 0) return
        src.seek(lho + skip)
        val buf = ByteArray(65536)
        var left = count
        while (left > 0) {
            val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun writeLocal(
        out: FileOutputStream, name: String, method: Int, crc: Long,
        csize: Long, usize: Long, payload: ByteArray, flags: Int
    ) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val head = ByteArrayOutputStream(30 + nameBytes.size)
        put32(SIG_LOCAL, head)
        put16(20, head)            // version needed
        put16(flags, head)
        put16(method, head)
        put16(0, head)              // time
        put16(0, head)              // date
        put32(crc, head)
        put32(csize, head)
        put32(usize, head)
        put16(nameBytes.size, head)
        put16(0, head)              // no extra
        out.write(head.toByteArray())
        out.write(nameBytes)
        out.write(payload)
    }

    private fun writeCentral(
        cd: ByteArrayOutputStream, name: String, flags: Int, method: Int,
        crc: Long, csize: Long, usize: Long, offset: Long
    ) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        put32(SIG_CENTRAL, cd)
        put16(20, cd)                // version made by
        put16(20, cd)                // version needed
        put16(flags, cd)
        put16(method, cd)
        put16(0, cd)                  // time
        put16(0, cd)                  // date
        put32(crc, cd)
        put32(csize, cd)
        put32(usize, cd)
        put16(nameBytes.size, cd)
        put16(0, cd)                  // extra
        put16(0, cd)                  // comment
        put16(0, cd)                  // disk
        put16(0, cd)                  // internal attrs
        put32(0, cd)                  // external attrs
        put32(offset, cd)
        cd.write(nameBytes)
    }
}
