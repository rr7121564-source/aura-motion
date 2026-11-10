package app.absentmotion.library

import app.absentmotion.R
import app.absentmotion.i18n.txt
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CancellationException

/** Public Alight share packages use the same ZIP import path as local .alight files. */
object ProjectLinks {
    const val MAX_PACKAGE_BYTES = 2L * 1024 * 1024 * 1024
    private val sharePath = Regex("^/am/share/u/([A-Za-z0-9_-]{1,256})/p/([A-Za-z0-9_-]{1,512})/?$")

    fun packageUrl(text: String): URL {
        val value = text.trim().let { if (it.startsWith("alightcreative.com/", true) || it.startsWith("www.alightcreative.com/", true)) "https://$it" else it }
        val uri = runCatching { URI(value) }.getOrNull()
        val host = uri?.host?.lowercase()
        if (uri == null || uri.scheme?.lowercase() !in setOf("https","http") || host !in setOf("alightcreative.com","www.alightcreative.com") || uri.userInfo != null || uri.port !in setOf(-1,80,443))
            throw ImportFailed(txt(R.string.project_link_invalid))
        val match = sharePath.matchEntire(uri.path.orEmpty()) ?: throw ImportFailed(txt(R.string.project_link_invalid))
        val objectName = "share/u/${match.groupValues[1]}/p/${match.groupValues[2]}/projectfiles.zip"
        val path = URLEncoder.encode(objectName,"UTF-8").replace("+","%20")
        return URL("https://firebasestorage.googleapis.com/v0/b/alight-creative.appspot.com/o/$path?alt=media")
    }

    fun download(
        cache: File, text: String, progress: (Long, Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false },
        connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    ): File {
        val url = packageUrl(text)
        fun active() { if (cancelled()) throw CancellationException("Project import cancelled") }
        active()
        val folder = File(cache,"project-links").apply { mkdirs() }
        val file = File.createTempFile("project-",".alight",folder)
        var complete = false
        var connection: HttpURLConnection? = null
        try {
            connection = connect(url).apply {
                connectTimeout = 15_000; readTimeout = 15_000
                setRequestProperty("Accept","application/zip, application/octet-stream")
                instanceFollowRedirects = true
            }
            val status = connection.responseCode
            active()
            if (status == 403 || status == 404 || status == 410) throw ImportFailed(txt(R.string.project_link_unavailable))
            if (status !in 200..299) throw IOException("HTTP $status")
            val total = connection.contentLengthLong
            if (total > MAX_PACKAGE_BYTES) throw ImportFailed(txt(R.string.project_link_large))
            var bytes = 0L
            progress(0,total)
            connection.inputStream.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(64*1024)
                while (true) {
                    active()
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    if (bytes > MAX_PACKAGE_BYTES) throw ImportFailed(txt(R.string.project_link_large))
                    output.write(buffer,0,count)
                    progress(bytes,total)
                }
            } }
            active()
            if (total >= 0 && bytes != total) throw IOException("Incomplete project package")
            val head = file.inputStream().use { input -> ByteArray(4).also { if (input.read(it) != 4) throw ImportFailed(txt(R.string.library_err_unsupported_type)) } }
            if (!head.contentEquals(byteArrayOf(0x50,0x4b,3,4))) throw ImportFailed(txt(R.string.library_err_unsupported_type))
            complete = true
            return file
        } finally {
            connection?.disconnect()
            if (!complete) file.delete()
        }
    }

    /** Only the downloader's own temporary files may be removed after import. */
    fun isDownload(cache: File, file: File): Boolean = runCatching {
        file.canonicalFile.parentFile == File(cache,"project-links").canonicalFile && file.name.startsWith("project-") && file.extension == "alight"
    }.getOrDefault(false)

    fun removeDownload(cache: File, file: File) {
        runCatching {
            if (isDownload(cache,file)) file.delete()
        }
    }
}
