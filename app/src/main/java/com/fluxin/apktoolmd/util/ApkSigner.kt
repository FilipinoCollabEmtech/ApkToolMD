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
                zf.getInputStream(entry).use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int = input.read(buffer)
                    while (read > 0) {
                        entrySig.update(buffer, 0, read)
                        read = input.read(buffer)
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

        val sfSig = Signature.getInstance(SIG_ALGORITHM)
        sfSig.initSign(privateKey)
        val sfContent = sfLines.joinToString("\n")
        sfSig.update(sfContent.toByteArray(Charsets.UTF_8))
        val rsaSignature = sfSig.sign()

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
        baos.write(signature.size)
        baos.write(signature)
        val certBytes = cert.encoded
        baos.write(certBytes.size)
        baos.write(certBytes)
        return baos.toByteArray()
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
                        zf.getInputStream(entry).use { input ->
                            input.copyTo(zos)
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
