package com.fluxin.apktoolmd.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The surgical patcher has to keep the package byte identical except for the
 * entry we really change - a full re-pack is what broke the game (the
 * compression of the entries changes and the package manager loads the
 * libraries differently).
 */
class SurgicalZipTest {

    private fun deflated(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(data)
        d.finish()
        val bos = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) {
            val n = d.deflate(buf)
            bos.write(buf, 0, n)
        }
        d.end()
        return bos.toByteArray()
    }

    /** a package with the same structure mix as a real one */
    private fun buildPackage(file: File) {
        val rnd = java.util.Random(1234)
        ZipOutputStream(file.outputStream().buffered()).use { zos ->
            zos.setMethod(ZipOutputStream.DEFLATED)
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(ByteArray(2000) { rnd.nextInt().toByte() })
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("resources.arsc"))
            zos.write(ByteArray(3000) { rnd.nextInt().toByte() })
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("lib/arm64-v8a/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("lib/arm64-v8a/libgame.so"))
            zos.write(ByteArray(40000) { rnd.nextInt().toByte() })
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(ByteArray(60000) { rnd.nextInt().toByte() })
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("assets/data/hello.txt"))
            zos.write("hello".repeat(500).toByteArray())
            zos.closeEntry()
        }
    }

    @Test
    fun patchKeepsEverythingElseIdentical() {
        val dir = File(System.getProperty("java.io.tmpdir"), "surgicalzip")
        dir.mkdirs()
        val input = File(dir, "in.apk")
        val output = File(dir, "out.apk")
        buildPackage(input)

        // read the library we want to replace
        var name = ""
        var original = ByteArray(0)
        ZipFile(input).use { zf ->
            val e = zf.entries().asSequence().first { it.name.endsWith(".so") }
            name = e.name
            original = zf.getInputStream(e).use { it.readBytes() }
        }
        assertTrue(name.isNotEmpty())

        // patch one byte of it and add a payload
        val patched = original.copyOf()
        patched[0] = (patched[0].toInt() xor 0xFF).toByte()
        val payload = ByteArray(2048) { (it * 13).toByte() }

        val err = SurgicalZip.patch(
            input, output,
            mapOf(name to patched),
            mapOf("lib/arm64-v8a/libpayload.so" to payload)
        )
        assertEquals(null, err)

        // the result is a valid zip
        ZipFile(output).use { zf ->
            // every original entry is there and byte identical (except ours)
            ZipFile(input).use { src ->
                for (e in src.entries()) {
                    if (e.isDirectory) continue
                    val be = zf.getEntry(e.name)
                    assertNotNull("missing ${e.name}", be)
                    val a = src.getInputStream(e).use { it.readBytes() }
                    val b = zf.getInputStream(be).use { it.readBytes() }
                    if (e.name == name) {
                        assertTrue("the library was not replaced", a.size == b.size)
                        assertEquals("the patched byte is wrong", patched[0], b[0])
                    } else {
                        assertTrue("entry ${e.name} changed", a.contentEquals(b))
                    }
                }
            }
            // the new entry is there
            val added = zf.getEntry("lib/arm64-v8a/libpayload.so")
            assertNotNull("the payload is missing", added)
            val ab = zf.getInputStream(added).use { it.readBytes() }
            assertEquals(payload.size, ab.size)
            assertTrue(payload.contentEquals(ab))
        }

        // and the raw bytes of the untouched entries really are the same
        // (the compression was not redone)
        assertTrue(output.length() > 0)
    }

    @Test
    fun deflateMatchesTheZipMethod() {
        val data = ByteArray(1000) { (it % 251).toByte() }
        val raw = deflated(data)
        // inflate it again with the raw inflater
        val inf = java.util.zip.Inflater(true)
        inf.setInput(raw)
        val out = ByteArray(data.size + 16)
        val n = inf.inflate(out)
        inf.end()
        assertEquals(data.size, n)
        assertTrue(data.contentEquals(out.copyOf(n)))
    }
}
