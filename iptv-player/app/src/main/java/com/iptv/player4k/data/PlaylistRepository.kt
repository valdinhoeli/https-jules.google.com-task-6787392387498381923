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

    /** Identifica a versão do arquivo baixado (muda a cada atualização da lista). */
    fun fingerprint(p: Playlist): String = cacheFile(p).let { "${it.length()}:${it.lastModified()}" }

    /**
     * Ordem do mais rápido ao mais lento:
     * 1) memória (lista já aberta neste processo), 2) retrato binário em disco,
     * 3) interpretar a lista M3U (e gravar o retrato para a próxima vez), 4) baixar.
     */
    suspend fun load(p: Playlist, forceRefresh: Boolean): M3uResult = withContext(Dispatchers.IO) {
        synchronized(lockFor(p.id)) {
            val file = cacheFile(p)
            if (forceRefresh || !hasCache(p)) {
                fetch(p, file)
                memo.remove(p.id)
            }
            val fp = fingerprint(p)
            memo[p.id]?.takeIf { it.first == fp }?.let { return@withContext it.second }

            val result = Snapshot.readChannels(context, p.id, fp) ?: run {
                // o cache contém URLs com usuário/senha: fica cifrado no aparelho
                val parsed = java.io.BufferedReader(java.io.InputStreamReader(Crypto.decryptingStream(context, file.inputStream()), Charsets.UTF_8), 64 * 1024).use {
                    M3uParser.parse(it, context.getString(R.string.group_none))
                }
                if (parsed.channels.isNotEmpty()) runCatching { Snapshot.writeChannels(context, p.id, fp, parsed) }
                parsed
            }
            if (result.channels.isEmpty() && p.type != PlaylistType.XTREAM) {
                throw IOException(context.getString(R.string.err_no_channels))
            }
            memo[p.id] = fp to result
            result
        }
    }

    /** Só o que já está no aparelho (para pré-carregar sem internet); null se nunca foi baixada. */
    suspend fun loadCached(p: Playlist): M3uResult? = if (hasCache(p)) runCatching { load(p, false) }.getOrNull() else null

    fun deleteCache(p: Playlist) {
        cacheFile(p).delete()
        memo.remove(p.id)
        Snapshot.delete(context, p.id)
    }

    companion object {
        /** listas já abertas neste processo: (versão do arquivo, resultado) */
        private val memo = java.util.concurrent.ConcurrentHashMap<String, Pair<String, M3uResult>>()
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private fun lockFor(id: String) = locks.getOrPut(id) { Any() }
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
