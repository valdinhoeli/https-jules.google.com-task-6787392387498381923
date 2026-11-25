package com.iptv.player4k.data

import android.content.Context
import android.net.Uri
import com.iptv.player4k.R
import com.iptv.player4k.security.Crypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Baixa/copia a lista para um cache local e faz o parse. */
class PlaylistRepository(private val context: Context) {

    private fun cacheFile(p: Playlist) = File(context.filesDir, "playlists/${p.id}.m3u")

    fun hasCache(p: Playlist) = cacheFile(p).let { it.exists() && it.length() > 0 }

    suspend fun load(p: Playlist, forceRefresh: Boolean): M3uResult = withContext(Dispatchers.IO) {
        val file = cacheFile(p)
        if (forceRefresh || !hasCache(p)) fetch(p, file)
        // o cache contém URLs com usuário/senha: fica cifrado no aparelho
        val result = Crypto.decryptingStream(context, file.inputStream()).bufferedReader(Charsets.UTF_8).use {
            M3uParser.parse(it, context.getString(R.string.group_none))
        }
        if (result.channels.isEmpty() && p.type != PlaylistType.XTREAM) {
            throw IOException(context.getString(R.string.err_no_channels))
        }
        result
    }

    fun deleteCache(p: Playlist) {
        cacheFile(p).delete()
    }

    private fun fetch(p: Playlist, dest: File) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".tmp")
        val src = p.resolvedUrl()
        val scheme = Uri.parse(src).scheme?.lowercase()
        var copied = 0L
        if (scheme == "content" || scheme == "file") {
            val input = runCatching { context.contentResolver.openInputStream(Uri.parse(src)) }.getOrNull()
                ?: throw IOException(context.getString(R.string.err_file_access))
            copied = input.use { i -> Crypto.encryptingStream(context, tmp.outputStream()).use { o -> i.copyTo(o) } }
        } else {
            val req = Request.Builder()
                .url(src)
                .header("User-Agent", p.userAgent.ifBlank { Http.DEFAULT_USER_AGENT })
                .header("Accept", "*/*")
                .build()
            Http.playlistClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ${resp.message}".trim())
                val body = resp.body ?: throw IOException("Resposta vazia")
                copied = body.byteStream().use { i -> Crypto.encryptingStream(context, tmp.outputStream()).use { o -> i.copyTo(o) } }
            }
        }
        if (copied == 0L) {
            tmp.delete()
            throw IOException(context.getString(R.string.err_no_channels))
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }
}
