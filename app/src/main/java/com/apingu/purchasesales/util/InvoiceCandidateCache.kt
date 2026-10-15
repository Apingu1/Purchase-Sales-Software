package com.apingu.purchasesales.util

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Temporary, device-private preview for an email invoice candidate.
 * Email adapters can stage a verified attachment here AFTER creating its candidate row.
 * Preview files are not approved invoice documents or synced to Dropbox.
 */
object InvoiceCandidateCache {
    private fun directory(context: Context): File =
        File(context.cacheDir, "invoice-candidate-previews").apply { mkdirs() }

    private val types = mapOf(
        "application/pdf" to "pdf",
        "image/png" to "png",
        "image/jpeg" to "jpg"
    )

    fun previewFile(context: Context, candidateId: Long): File? {
        if (candidateId <= 0) return null
        val root = directory(context)
        return types.values.distinct().map { File(root, candidateId.toString() + "." + it) }
            .firstOrNull { it.isFile && it.length() > 0 }
    }

    fun stage(context: Context, candidateId: Long, mimeType: String, stream: InputStream): File {
        require(candidateId > 0) { "Candidate ID must be positive" }
        val extension = types[mimeType.lowercase()]
            ?: error("Only PDF and JPEG/PNG invoice previews are supported")
        val root = directory(context)
        val temp = File(root, candidateId.toString() + "." + UUID.randomUUID() + ".tmp")
        val maximumBytes = 25L * 1024L * 1024L
        try {
            temp.outputStream().use { output ->
                stream.use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= maximumBytes) { "Invoice exceeds the 25 MB preview limit" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(temp.length() > 0) { "Invoice attachment is empty" }
            val destination = File(root, candidateId.toString() + "." + extension)
            require(temp.renameTo(destination)) { "Unable to cache invoice preview" }
            types.values.distinct().filterNot { it == extension }.forEach {
                File(root, candidateId.toString() + "." + it).delete()
            }
            return destination
        } finally {
            temp.delete()
        }
    }

    fun discard(context: Context, candidateId: Long) {
        if (candidateId <= 0) return
        types.values.distinct().forEach {
            File(directory(context), candidateId.toString() + "." + it).delete()
        }
    }
}
