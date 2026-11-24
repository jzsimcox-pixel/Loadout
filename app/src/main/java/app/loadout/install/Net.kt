package app.loadout.install

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Net {
    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "Loadout")
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        return connection
    }

    private fun check(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code in 200..299) return
        throw IOException(
            when (code) {
                403, 429 -> "GitHub is limiting requests right now. Try again in a while."
                404 -> "The download was not found."
                451 -> "GitHub has blocked this project's downloads (error 451), so Loadout can't fetch it from there."
                else -> "The server answered with error $code."
            },
        )
    }

    fun getText(url: String): String {
        val connection = open(url)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            check(connection)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads to a temporary file first, so a broken download never looks complete. */
    fun download(url: String, target: File, onProgress: (percent: Int) -> Unit) {
        val connection = open(url)
        val partial = File(target.parentFile, target.name + ".part")
        try {
            check(connection)
            val total = connection.contentLengthLong
            target.parentFile?.mkdirs()
            var done = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("The download was cut off. Try again.")
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) throw IOException("Could not save the download.")
        } finally {
            partial.delete()
            connection.disconnect()
        }
    }
}
