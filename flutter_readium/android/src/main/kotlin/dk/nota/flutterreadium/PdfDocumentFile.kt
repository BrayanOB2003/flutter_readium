package dk.nota.flutterreadium

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.getOrElse
import java.io.File
import java.io.IOException

private const val TAG = "PdfDocumentFile"
private const val CHUNK_SIZE = 1L shl 20
private const val CACHE_DIRECTORY = "flutter_readium_pdf"

/**
 * androidx.pdf opens a seekable file descriptor, which a publication resource is not.
 * The PDF is copied once into the app cache and reused while its size is unchanged.
 */
internal object PdfDocumentFile {
    suspend fun materialize(
        context: Context,
        publication: Publication,
    ): File? =
        withContext(Dispatchers.IO) {
            val link =
                publication.readingOrder.firstOrNull() ?: run {
                    PluginLog.w(TAG, "::materialize. The publication has no reading order.")
                    return@withContext null
                }
            val resource =
                publication.get(link) ?: run {
                    PluginLog.w(TAG, "::materialize. The PDF resource is missing.")
                    return@withContext null
                }
            try {
                val length =
                    resource.length().getOrElse { error ->
                        PluginLog.w(TAG, "::materialize. Unknown PDF length: $error")
                        return@withContext null
                    }
                val directory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
                val target = File(directory, "${cacheKey(publication, link.href.toString())}.pdf")
                if (target.isFile && target.length() == length) return@withContext target

                val partial = File(directory, "${target.name}.part")
                try {
                    partial.outputStream().use { out ->
                        var offset = 0L
                        while (offset < length) {
                            val end = minOf(offset + CHUNK_SIZE, length)
                            val bytes =
                                resource.read(offset until end).getOrElse { error ->
                                    throw IOException("Cannot read the PDF at $offset: $error")
                                }
                            out.write(bytes)
                            offset = end
                        }
                    }
                    if (!partial.renameTo(target)) throw IOException("Cannot move ${partial.name} into place.")
                    target
                } catch (error: IOException) {
                    PluginLog.e(TAG, "::materialize. Copy failed: $error")
                    partial.delete()
                    null
                }
            } finally {
                resource.close()
            }
        }

    private fun cacheKey(
        publication: Publication,
        href: String,
    ): String {
        val identifier = publication.metadata.identifier ?: publication.metadata.title.orEmpty()
        return (identifier + href).hashCode().toUInt().toString(16)
    }
}
