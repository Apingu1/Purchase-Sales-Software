package com.apingu.purchasesales.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest

data class InvoiceFingerprint(val sha256: String, val byteSize: Long)

/** File metadata and streaming hashing without loading entire invoices into RAM. */
object PurchaseInvoiceFiles {
    fun fingerprint(path: String): InvoiceFingerprint {
        val file = File(path)
        require(file.isFile) { "Invoice document is missing" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(8192)
            while (true) {
                val count = input.read(bytes)
                if (count == -1) break
                digest.update(bytes, 0, count)
            }
        }
        return InvoiceFingerprint(
            sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) },
            byteSize = file.length()
        )
    }

    fun originalName(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        val fromCursor = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getString(0)
            }
        }.getOrNull()
        return (fromCursor ?: uri.lastPathSegment ?: "invoice")
            .substringAfterLast('/').take(180).ifBlank { "invoice" }
    }
}
