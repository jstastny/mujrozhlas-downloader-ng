package com.stastnarodina.mujrozhlas.web

import java.io.File
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class UrlSigner(
    private val outputDir: File,
    private val expirySeconds: Long = 7 * 24 * 3600, // 7 days
) {
    private val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /**
     * Generate a presigned download URL for a file.
     * Returns null if the file is outside outputDir or doesn't exist.
     */
    fun sign(file: File): String? {
        val relativePath = file.toRelativeStringOrNull(outputDir) ?: return null
        val expires = System.currentTimeMillis() / 1000 + expirySeconds
        val signature = hmac("$relativePath:$expires")
        return "/dl/${urlEncodePath(relativePath)}?e=$expires&s=$signature"
    }

    /**
     * Verify a presigned URL and return the resolved file, or null if invalid/expired.
     */
    fun verify(relativePath: String, expires: String?, signature: String?): File? {
        if (expires == null || signature == null) return null

        val expiresLong = expires.toLongOrNull() ?: return null
        if (System.currentTimeMillis() / 1000 > expiresLong) return null

        val expected = hmac("$relativePath:$expires")
        if (!timingSafeEquals(expected, signature)) return null

        val file = File(outputDir, relativePath).canonicalFile
        if (!file.canonicalPath.startsWith(outputDir.canonicalPath)) return null
        if (!file.exists()) return null

        return file
    }

    private fun hmac(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun timingSafeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    private fun File.toRelativeStringOrNull(base: File): String? {
        val canonical = this.canonicalFile
        val baseCanonical = base.canonicalFile
        if (!canonical.canonicalPath.startsWith(baseCanonical.canonicalPath)) return null
        return canonical.relativeTo(baseCanonical).path
    }

    private fun urlEncodePath(path: String): String {
        return path.split("/").joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
    }
}
