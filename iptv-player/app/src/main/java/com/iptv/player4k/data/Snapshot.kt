package com.iptv.player4k.data

import android.content.Context
import com.iptv.player4k.security.Crypto
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * "Retrato" da lista já processada (canais, filmes, séries), gravado em binário compacto e cifrado.
 * Na segunda abertura o app lê o retrato em vez de interpretar a lista M3U/JSON de novo
 * (que é o que demora em listas grandes).
 */
object Snapshot {
    private const val VERSION = 4

    private fun dir(ctx: Context) = File(ctx.filesDir, "snapshots").apply { mkdirs() }
    fun channelsFile(ctx: Context, id: String) = File(dir(ctx), "$id.chn")
    fun libraryFile(ctx: Context, id: String) = File(dir(ctx), "$id.lib")

    fun delete(ctx: Context, id: String) {
        channelsFile(ctx, id).delete()
        libraryFile(ctx, id).delete()
    }

    // ---------------------------------------------------------------- escrita/leitura

    private inline fun write(ctx: Context, f: File, block: (Out) -> Unit) {
        val tmp = File(f.path + ".tmp")
        DataOutputStream(Crypto.encryptingStream(ctx, tmp.outputStream())).use { block(Out(it)) }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
    }

    private inline fun <T> read(ctx: Context, f: File, block: (In) -> T): T? {
        if (!f.exists() || f.length() == 0L) return null
        return runCatching {
            DataInputStream(BufferedInputStream(Crypto.decryptingStream(ctx, f.inputStream()), 64 * 1024)).use { block(In(it)) }
        }.getOrNull()
    }

    /** Strings repetidas (grupos, User-Agent…) viram um índice numa tabela. */
    private class Out(val o: DataOutputStream) {
        private val table = HashMap<String, Int>()
        fun int(v: Int) = o.writeInt(v)
        fun long(v: Long) = o.writeLong(v)
        fun str(s: String?) {
            if (s == null) { o.writeInt(-1); return }
            val b = s.toByteArray(Charsets.UTF_8)
            o.writeInt(b.size); o.write(b)
        }
        fun shared(s: String?) {
            if (s == null) { o.writeInt(-1); return }
            val idx = table[s]
            if (idx != null) { o.writeInt(idx); return }
            table[s] = table.size
            o.writeInt(-2)
            str(s)
        }
        fun map(m: Map<String, String>) {
            o.writeInt(m.size)
            m.forEach { (k, v) -> shared(k); shared(v) }
        }
        fun strs(l: List<String>) { o.writeInt(l.size); l.forEach { shared(it) } }
    }

    private class In(val i: DataInputStream) {
        private val table = ArrayList<String>()
        private var buf = ByteArray(256)
        fun int() = i.readInt()
        fun long() = i.readLong()
        fun str(): String? {
            val n = i.readInt()
            if (n < 0) return null
            if (buf.size < n) buf = ByteArray(n)
            i.readFully(buf, 0, n)
            return String(buf, 0, n, Charsets.UTF_8)
        }
        fun shared(): String? = when (val n = i.readInt()) {
            -1 -> null
            -2 -> str()!!.also { table += it }
            else -> table[n]
        }
        fun map(): Map<String, String> {
            val n = i.readInt()
            if (n == 0) return emptyMap()
            val m = HashMap<String, String>(n)
            repeat(n) { m[shared()!!] = shared()!! }
            return m
        }
        fun strs(): List<String> = List(i.readInt()) { shared()!! }
    }

    private fun Out.optInt(v: Int?) = int(v ?: Int.MIN_VALUE)
    private fun In.optInt(): Int? = int().takeIf { it != Int.MIN_VALUE }

    // ---------------------------------------------------------------- canais

    fun writeChannels(ctx: Context, id: String, fingerprint: String, r: M3uResult) =
        write(ctx, channelsFile(ctx, id)) { o ->
            o.int(VERSION); o.str(fingerprint)
            o.strs(r.tvgUrls)
            o.int(r.channels.size)
            for (c in r.channels) {
                o.str(c.name); o.str(c.url); o.shared(c.logo); o.shared(c.group); o.str(c.tvgId)
                o.map(c.headers); o.int(c.kind.ordinal); o.str(c.tvgName); o.str(c.refId)
                o.optInt(c.age); o.str(c.description); o.str(c.searchKey); o.int(c.qualityTag?.ordinal ?: -1)
                o.str(c.trailer)
            }
        }

    fun readChannels(ctx: Context, id: String, fingerprint: String): M3uResult? =
        read(ctx, channelsFile(ctx, id)) { i ->
            if (i.int() != VERSION || i.str() != fingerprint) return@read null
            val tvg = i.strs()
            val n = i.int()
            val kinds = ContentKind.values()
            val tags = QualityTag.values()
            val list = ArrayList<Channel>(n)
            repeat(n) {
                list += Channel(
                    name = i.str()!!, url = i.str()!!, logo = i.shared(), group = i.shared() ?: "", tvgId = i.str(),
                    headers = i.map(), kind = kinds[i.int()], tvgName = i.str(), refId = i.str(),
                    age = i.optInt(), description = i.str(), searchKey = i.str()!!,
                    qualityTag = i.int().let { q -> if (q < 0) null else tags[q] },
                    trailer = i.str(),
                )
            }
            M3uResult(list, tvg)
        }

    // ---------------------------------------------------------------- filmes e séries

    fun writeLibrary(ctx: Context, id: String, fingerprint: String, lib: Library) =
        write(ctx, libraryFile(ctx, id)) { o ->
            o.int(VERSION); o.str(fingerprint)
            o.strs(lib.movieCategories); o.strs(lib.seriesCategories)
            o.int(lib.movies.size)
            for (m in lib.movies) {
                o.str(m.id); o.str(m.name); o.str(m.url); o.shared(m.poster); o.shared(m.category); o.shared(m.year)
                o.int(java.lang.Float.floatToIntBits(m.rating ?: Float.NaN)); o.long(m.added); o.optInt(m.xtreamId)
                o.map(m.headers); o.str(m.plot); o.str(m.backdrop); o.optInt(m.age); o.str(m.searchKey); o.str(m.trailer)
            }
            o.int(lib.series.size)
            for (s in lib.series) {
                o.str(s.id); o.str(s.name); o.shared(s.poster); o.shared(s.category); o.shared(s.year)
                o.int(java.lang.Float.floatToIntBits(s.rating ?: Float.NaN)); o.long(s.added); o.optInt(s.xtreamId)
                o.str(s.plot); o.shared(s.genre); o.str(s.backdrop); o.optInt(s.age); o.str(s.searchKey); o.str(s.trailer)
                o.int(s.episodes.size)
                for (e in s.episodes) {
                    o.str(e.id); o.int(e.season); o.int(e.number); o.str(e.title); o.str(e.url)
                    o.shared(e.image); o.str(e.plot); o.int(e.durationSecs); o.map(e.headers)
                }
            }
        }

    fun readLibrary(ctx: Context, id: String, fingerprint: String): Library? =
        read(ctx, libraryFile(ctx, id)) { i ->
            if (i.int() != VERSION || i.str() != fingerprint) return@read null
            val movieCats = i.strs()
            val seriesCats = i.strs()
            fun rating() = java.lang.Float.intBitsToFloat(i.int()).takeIf { !it.isNaN() }
            val movies = List(i.int()) {
                VodItem(
                    id = i.str()!!, name = i.str()!!, url = i.str()!!, poster = i.shared(), category = i.shared() ?: "",
                    year = i.shared(), rating = rating(), added = i.long(), xtreamId = i.optInt(), headers = i.map(),
                    plot = i.str(), backdrop = i.str(), age = i.optInt(), searchKey = i.str()!!, trailer = i.str(),
                )
            }
            val series = List(i.int()) {
                val sid = i.str()!!; val name = i.str()!!; val poster = i.shared(); val cat = i.shared() ?: ""
                val year = i.shared(); val rating = rating(); val added = i.long(); val xid = i.optInt()
                val plot = i.str(); val genre = i.shared(); val backdrop = i.str(); val age = i.optInt(); val key = i.str()!!
                val trailer = i.str()
                val eps = List(i.int()) {
                    Episode(
                        id = i.str()!!, season = i.int(), number = i.int(), title = i.str()!!, url = i.str()!!,
                        image = i.shared(), plot = i.str(), durationSecs = i.int(), headers = i.map(),
                    )
                }
                SeriesItem(sid, name, poster, cat, year, rating, added, xid, plot, genre, backdrop, eps, age, key, trailer)
            }
            Library(movies, movieCats, series, seriesCats)
        }
}
