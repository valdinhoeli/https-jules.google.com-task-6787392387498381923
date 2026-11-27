package com.iptv.player4k.util

import com.iptv.player4k.data.Channel
import com.iptv.player4k.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Teste de velocidade.
 * - Internet: speed.cloudflare.com (ping, download e upload), sem conta e sem cadastro.
 * - Servidor da lista: baixa um canal ao vivo da própria lista por alguns segundos — mostra se a
 *   linha até o servidor IPTV aguenta SD, HD, Full HD ou 4K.
 */
object SpeedTest {
    private const val CF_DOWN = "https://speed.cloudflare.com/__down?bytes="
    private const val CF_UP = "https://speed.cloudflare.com/__up"

    private val client: OkHttpClient
        get() = Http.client.newBuilder()
            .readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()

    /** Mbps a partir de bytes em ms. */
    fun mbps(bytes: Long, ms: Long): Double = if (ms <= 0) 0.0 else bytes * 8.0 / ms / 1000.0

    /** O que dá para assistir com essa velocidade. */
    fun verdict(mbps: Double): Quality = when {
        mbps >= 25 -> Quality.UHD
        mbps >= 10 -> Quality.FHD
        mbps >= 5 -> Quality.HD
        mbps >= 2.5 -> Quality.SD
        else -> Quality.WEAK
    }

    enum class Quality { UHD, FHD, HD, SD, WEAK }

    /** Latência (mediana de 5 pedidos pequenos), em ms. */
    suspend fun ping(): Long = withContext(Dispatchers.IO) {
        val c = client
        val times = ArrayList<Long>()
        repeat(6) { i ->
            coroutineContext.ensureActive()
            val t0 = System.nanoTime()
            runCatching {
                c.newCall(Request.Builder().url(CF_DOWN + "0").build()).execute().use { it.body?.bytes() }
            }.onSuccess { if (i > 0) times += (System.nanoTime() - t0) / 1_000_000 } // o 1º abre a conexão
        }
        if (times.isEmpty()) error("sem conexão")
        times.sorted()[times.size / 2]
    }

    /** Download da internet com 3 conexões por [durationMs]; [onProgress] recebe os Mbps atuais. */
    suspend fun internetDownload(durationMs: Long = 10_000, onProgress: (Double) -> Unit): Double =
        measure(durationMs, onProgress) { counter, deadline ->
            val c = client
            coroutineScope {
                (1..3).map {
                    async(Dispatchers.IO) {
                        while (System.currentTimeMillis() < deadline) {
                            coroutineContext.ensureActive()
                            val req = Request.Builder().url(CF_DOWN + 25_000_000).build()
                            c.newCall(req).execute().use { r ->
                                if (!r.isSuccessful) error("HTTP ${r.code}")
                                drain(r.body!!.byteStream(), counter, deadline)
                            }
                        }
                    }
                }.awaitAll()
            }
        }

    /** Upload para a internet (1 conexão) por [durationMs]. */
    suspend fun internetUpload(durationMs: Long = 8_000, onProgress: (Double) -> Unit): Double =
        measure(durationMs, onProgress) { counter, deadline ->
            val chunk = ByteArray(64 * 1024).also { java.util.Random().nextBytes(it) }
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun writeTo(sink: BufferedSink) {
                    while (System.currentTimeMillis() < deadline) {
                        sink.write(chunk)
                        sink.flush()
                        counter.addAndGet(chunk.size.toLong())
                    }
                }
            }
            withContext(Dispatchers.IO) {
                runCatching { client.newCall(Request.Builder().url(CF_UP).post(body).build()).execute().close() }
            }
        }

    /**
     * Velocidade até o servidor da lista: baixa o canal (TS contínuo ou segmentos HLS) por
     * [durationMs]. Usa 1 conexão da lista durante o teste.
     */
    suspend fun serverDownload(ch: Channel, userAgent: String?, durationMs: Long = 10_000, onProgress: (Double) -> Unit): Double =
        measure(durationMs, onProgress) { counter, deadline ->
            withContext(Dispatchers.IO) {
                val c = client
                val headers = HashMap(ch.headers)
                val ua = headers.remove("User-Agent") ?: userAgent?.takeIf { it.isNotBlank() } ?: Http.DEFAULT_USER_AGENT
                fun req(url: String) = Request.Builder().url(url).header("User-Agent", ua)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                val path = ch.url.substringBefore('?').lowercase()
                if (!path.endsWith(".m3u8") && !path.endsWith(".m3u")) {
                    // MPEG-TS ao vivo: fluxo contínuo
                    while (System.currentTimeMillis() < deadline) {
                        coroutineContext.ensureActive()
                        c.newCall(req(ch.url)).execute().use { r ->
                            if (!r.isSuccessful) error("HTTP ${r.code}")
                            drain(r.body!!.byteStream(), counter, deadline)
                        }
                    }
                } else {
                    // HLS: escolhe a maior qualidade e baixa os segmentos em sequência
                    var url = ch.url
                    var text = c.newCall(req(url)).execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body!!.string() }
                    if ("#EXT-X-STREAM-INF" in text) {
                        val variant = hlsBestVariant(text) ?: error("sem variante")
                        url = resolve(url, variant)
                        text = c.newCall(req(url)).execute().use { r -> r.body!!.string() }
                    }
                    val segments = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                    if (segments.isEmpty()) error("sem segmentos")
                    var i = 0
                    while (System.currentTimeMillis() < deadline) {
                        coroutineContext.ensureActive()
                        c.newCall(req(resolve(url, segments[i % segments.size]))).execute().use { r ->
                            if (!r.isSuccessful) error("HTTP ${r.code}")
                            drain(r.body!!.byteStream(), counter, deadline)
                        }
                        i++
                    }
                }
            }
        }

    /** Variante de maior BANDWIDTH de uma playlist mestre HLS. */
    internal fun hlsBestVariant(master: String): String? {
        val lines = master.lines().map { it.trim() }
        var best: String? = null
        var bestBw = -1L
        for (i in lines.indices) {
            val l = lines[i]
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val bw = Regex("""BANDWIDTH=(\d+)""").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            if (bw > bestBw) { bestBw = bw; best = uri }
        }
        return best
    }

    internal fun resolve(base: String, ref: String): String =
        if (ref.startsWith("http://") || ref.startsWith("https://")) ref
        else java.net.URI(base).resolve(ref).toString()

    private fun drain(input: java.io.InputStream, counter: AtomicLong, deadline: Long) {
        val buf = ByteArray(64 * 1024)
        while (System.currentTimeMillis() < deadline) {
            val n = input.read(buf)
            if (n < 0) break
            counter.addAndGet(n.toLong())
        }
    }

    /**
     * Mede a taxa de [work]: ignora o primeiro segundo (abertura das conexões) e informa a
     * velocidade 4× por segundo. Retorna a média em Mbps.
     */
    private suspend fun measure(
        durationMs: Long, onProgress: (Double) -> Unit,
        work: suspend (AtomicLong, Long) -> Unit,
    ): Double = coroutineScope {
        val counter = AtomicLong()
        val start = System.currentTimeMillis()
        val deadline = start + durationMs
        val job = async { runCatching { work(counter, deadline) } }
        var baseBytes = 0L
        var baseTime = start
        var warmed = false
        var result = 0.0
        while (job.isActive) {
            kotlinx.coroutines.delay(250)
            val now = System.currentTimeMillis()
            if (!warmed && now - start >= 1000) { warmed = true; baseBytes = counter.get(); baseTime = now }
            result = if (warmed) mbps(counter.get() - baseBytes, now - baseTime) else mbps(counter.get(), now - start)
            withContext(Dispatchers.Main) { onProgress(result) }
        }
        val err = job.await().exceptionOrNull()
        if (counter.get() == 0L && err != null) throw err
        result
    }
}
