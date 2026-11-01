package com.fluxin.apktoolmd.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkSigner {

    private const val SIG_ALGORITHM = "SHA256withRSA"

    fun signV1(
        apkFile: File,
        keystoreFile: File,
        keystorePassword: String,
        keyAlias: String,
        keyPassword: String
    ): String? {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(FileInputStream(keystoreFile), keystorePassword.toCharArray())
        }
        val privateKey = keyStore.getKey(keyAlias, keyPassword.toCharArray()) as PrivateKey
        val chain = keyStore.getCertificateChain(keyAlias).map { it as X509Certificate }

        return try {
            signV1Internal(apkFile, privateKey, chain)
            null
        } catch (e: Exception) {
            "V1 sign failed: ${e.message}"
        }
    }

    private fun signV1Internal(
        apkFile: File,
        privateKey: PrivateKey,
        chain: List<X509Certificate>
    ) {
        val entryDigests = mutableMapOf<String, ByteArray>()
        ZipFile(apkFile).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name
                if (name.startsWith("META-INF/")) continue

                val entrySig = Signature.getInstance(SIG_ALGORITHM)
                entrySig.initSign(privateKey)
                zf.getInputStream(entry).use { is ->
                    val buffer = ByteArray(8192)
                    var read = is.read(buffer)
                    while (read > 0) {
                        entrySig.update(buffer, 0, read)
                        read = is.read(buffer)
                    }
                }
                entryDigests[name] = entrySig.sign()
            }
        }

        val manifestLines = mutableListOf<String>()
        manifestLines.add("Manifest-Version: 1.0")
        manifestLines.add("Created-By: ApkToolMD v1-signer")
        manifestLines.add("")

        val sfLines = mutableListOf<String>()
        sfLines.add("Signature-Version: 1.0")
        sfLines.add("Created-By: ApkToolMD v1-signer")
        sfLines.add("")

        val allEntryDigests = entryDigests.entries.sortedBy { it.key }
        for ((name, digest) in allEntryDigests) {
            val b64 = Base64.getEncoder().encodeToString(digest)
            manifestLines.add("Name: $name")
            manifestLines.add("SHA-256-Digest: $b64")
            manifestLines.add("")

            sfLines.add("Name: $name")
            sfLines.add("SHA-256-Digest: $b64")
            sfLines.add("")
        }

        val sf = Signature.getInstance(SIG_ALGORITHM)
        sf.initSign(privateKey)
        val sfContent = sfLines.joinToString("\n")
        sf.update(sfContent.toByteArray(Charsets.UTF_8))
        val rsaSignature = sf.sign()

        val certSFContent = sfLines.joinToString("\n")
        val certSFBytes = certSFContent.toByteArray(Charsets.UTF_8)

        val certRSA = buildCertRSA(rsaSignature, chain.first())

        rewriteZipWithEntries(apkFile, mapOf(
            "META-INF/MANIFEST.MF" to manifestLines.joinToString("\n").toByteArray(Charsets.UTF_8),
            "META-INF/CERT.SF" to certSFBytes,
            "META-INF/CERT.RSA" to certRSA
        ))
    }

    private fun buildCertRSA(signature: ByteArray, cert: X509Certificate): ByteArray {
        val baos = ByteArrayOutputStream()

        val content = ByteArrayOutputStream()

        content.write(0x02)
        content.write(0x01)
        content.write(0x03)

        content.write(0x30)
        val algoContent = ByteArrayOutputStream()
        algoContent.write(0x06)
        val oid = byteArrayOf(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B)
        algoContent.write(0x09)
        algoContent.write(oid.size.toInt().toByte())
        algoContent.write(oid)
        algoContent.write(0x05)
        algoContent.write(0x00)
        content.write(algoContent.size.toInt().toByte())
        content.write(algoContent.toByteArray())

        content.write(0x04)
        content.write(signature.size.toInt().toByte())
        content.write(signature)

        content.write(0x31)
        val certContent = ByteArrayOutputStream()
        certContent.write(0x30)
        val certInner = ByteArrayOutputStream()
        certInner.write(0x02)
        certInner.write(0x01)
        certInner.write(0x03)
        certContent.write(certInner.size.toInt().toByte())
        certContent.write(certInner.toByteArray())
        content.write(certContent.size.toInt().toByte())
        content.write(certContent.toByteArray())

        val result = ByteArrayOutputStream()
        result.write(0x30)
        val contentBytes = content.toByteArray()
        if (contentBytes.size < 128) {
            result.write(contentBytes.size.toInt().toByte())
        } else {
            result.write(0x81)
            result.write(contentBytes.size.toInt().toByte())
        }
        result.write(contentBytes)

        return result.toByteArray()
    }

    private fun rewriteZipWithEntries(
        apkFile: File,
        entries: Map<String, ByteArray>
    ) {
        val tempFile = File(apkFile.parentFile, "${apkFile.name}.tmp.${System.currentTimeMillis()}")
        ZipFile(apkFile).use { zf ->
            ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                val existingNames = mutableSetOf<String>()
                val e = zf.entries()
                while (e.hasMoreElements()) {
                    val entry = e.nextElement()
                    existingNames.add(entry.name)
                    zos.putNextEntry(entry)
                    if (!entry.isDirectory && entry.name !in entries) {
                        zf.getInputStream(entry).use { is ->
                            is.copyTo(zos)
                        }
                    }
                    zos.closeEntry()
                }
                for ((name, data) in entries) {
                    if (name in existingNames) continue
                    val ze = ZipEntry(name)
                    ze.time = System.currentTimeMillis()
                    zos.putNextEntry(ze)
                    zos.write(data)
                    zos.closeEntry()
                }
            }
        }
        tempFile.renameTo(apkFile)
    }
}
