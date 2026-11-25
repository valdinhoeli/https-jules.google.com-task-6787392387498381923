package com.iptv.player4k.epg

import android.content.Context
import android.util.Xml
import com.iptv.player4k.data.Channel
import com.iptv.player4k.data.Http
import com.iptv.player4k.data.Playlist
import com.iptv.player4k.data.PlaylistType
import com.iptv.player4k.data.XtreamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Baixa o guia XMLTV (xml ou xml.gz), filtra e guarda um cache compacto. */
class EpgRepository(private val context: Context) {

    fun sources(p: Playlist, tvgUrls: List<String>): List<String> {
        val custom = p.epgUrl.split(',', '\n', ' ').map { it.trim() }.filter { it.startsWith("http", true) }
        if (custom.isNotEmpty()) return custom
        val list = ArrayList<String>()
        if (p.type == PlaylistType.XTREAM) list += XtreamApi(p).xmltvUrl()
        list += tvgUrls
        return list.distinct()
    }

    /** @return null quando a lista não tem nenhuma fonte de guia. */
    suspend fun load(p: Playlist, tvgUrls: List<String>, live: List<Channel>, force: Boolean): EpgData? =
        withContext(Dispatchers.IO) {
            val src = sources(p, tvgUrls)
            if (src.isEmpty()) return@withContext null
            val cache = File(context.filesDir, "epg/${p.id}.bin")
            val sig = src.joinToString("|").hashCode()
            val cached = if (force) null else readCache(cache, sig)
            if (cached != null && System.currentTimeMillis() - cached.updatedAt < TTL) return@withContext cached

            try {
                val data = download(src, p, live)
                writeCache(cache, sig, data)
                data
            } catch (e: Exception) {
                cached ?: readCache(cache, sig) ?: throw e
            }
        }

    private fun download(src: List<String>, p: Playlist, live: List<Channel>): EpgData {
        val wantIds = HashSet<String>()
        val wantNames = HashSet<String>()
        live.forEach { ch ->
            ch.tvgId?.lowercase()?.let { wantIds += it }
            listOfNotNull(ch.tvgId, ch.tvgName, ch.name).forEach { n ->
                EpgData.nameKey(n).takeIf { it.isNotEmpty() }?.let { wantNames += it }
            }
        }
        val now = System.currentTimeMillis()
        val builder = XmltvParser.Builder()
        var ok = 0
        var lastError: Exception? = null
        for (url in src) {
            try {
                val req = Request.Builder().url(url)
                    .header("User-Agent", p.userAgent.ifBlank { Http.DEFAULT_USER_AGENT }).build()
                Http.playlistClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val raw = BufferedInputStream(r.body!!.byteStream(), 64 * 1024)
                    raw.mark(4)
                    val b1 = raw.read()
                    val b2 = raw.read()
                    raw.reset()
                    val input = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw
                    val xpp = Xml.newPullParser()
                    xpp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    xpp.setInput(input, null)
                    XmltvParser.parse(xpp, wantIds, wantNames, now - BEFORE, now + AFTER, builder)
                    ok++
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (ok == 0) throw lastError ?: IOException("Guia indisponível")
        return builder.build(now)
    }

    private fun writeCache(f: File, sig: Int, d: EpgData) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        DataOutputStream(GZIPOutputStream(tmp.outputStream().buffered())).use { o ->
            o.writeInt(VERSION); o.writeInt(sig); o.writeLong(d.updatedAt)
            o.writeInt(d.byId.size)
            d.byId.forEach { (id, list) ->
                o.writeUTF(id)
                o.writeInt(list.size)
                list.forEach { p ->
                    o.writeLong(p.start); o.writeLong(p.stop)
                    o.writeUTF(p.title.take(300)); o.writeUTF(p.desc.orEmpty())
                }
            }
            o.writeInt(d.nameIndex.size)
            d.nameIndex.forEach { (k, v) -> o.writeUTF(k); o.writeUTF(v) }
        }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
    }

    private fun readCache(f: File, sig: Int): EpgData? {
        if (!f.exists()) return null
        return runCatching {
            DataInputStream(GZIPInputStream(f.inputStream().buffered())).use { i ->
                if (i.readInt() != VERSION || i.readInt() != sig) return null
                val updated = i.readLong()
                val n = i.readInt()
                val byId = HashMap<String, List<Programme>>(n)
                repeat(n) {
                    val id = i.readUTF()
                    val c = i.readInt()
                    byId[id] = List(c) {
                        val s = i.readLong(); val e = i.readLong()
                        Programme(s, e, i.readUTF(), i.readUTF().ifEmpty { null })
                    }
                }
                val m = i.readInt()
                val names = HashMap<String, String>(m)
                repeat(m) { names[i.readUTF()] = i.readUTF() }
                EpgData(byId, names, updated)
            }
        }.getOrNull()
    }

    fun deleteCache(p: Playlist) {
        File(context.filesDir, "epg/${p.id}.bin").delete()
    }

    companion object {
        private const val VERSION = 1
        private const val TTL = 12 * 60 * 60 * 1000L
        private const val BEFORE = 3 * 60 * 60 * 1000L
        private const val AFTER = 36 * 60 * 60 * 1000L
    }
}
