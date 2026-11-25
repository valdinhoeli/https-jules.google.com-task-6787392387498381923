package com.iptv.player4k.data

import android.content.Context
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

object Http {
    /** User-Agent padrão: amplamente aceito por servidores/painéis IPTV. */
    const val DEFAULT_USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"

    /** Provedores de DNS seguro (DNS-over-HTTPS). Índice 0 = DNS da operadora. */
    class DohProvider(val name: String, val url: String, val bootstrap: List<String>)

    val providers = listOf(
        DohProvider("Desligado (DNS da operadora)", "", emptyList()),
        DohProvider("Cloudflare (1.1.1.1)", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
        DohProvider("Google (8.8.8.8)", "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4")),
        DohProvider("Quad9 (9.9.9.9)", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
    )

    @Volatile var client: OkHttpClient = build(0)
        private set

    /** Cliente para baixar listas grandes (alguns servidores são lentos). */
    @Volatile var playlistClient: OkHttpClient = client.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
        private set

    @Volatile var dnsMode = 0
        private set

    /** Lê o ajuste salvo e recria os clientes HTTP. */
    fun configure(context: Context) {
        val mode = PlaylistStore(context).dnsMode
        apply(mode)
    }

    fun apply(mode: Int) {
        dnsMode = mode.coerceIn(0, providers.lastIndex)
        client = build(dnsMode)
        playlistClient = client.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
    }

    private fun build(mode: Int): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
        val p = providers.getOrNull(mode)
        if (p != null && p.url.isNotEmpty()) {
            val bootstrap = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).build()
            val doh = DnsOverHttps.Builder()
                .client(bootstrap)
                .url(p.url.toHttpUrl())
                .bootstrapDnsHosts(p.bootstrap.map { InetAddress.getByName(it) })
                .includeIPv6(false)
                .build()
            b.dns(FallbackDns(doh))
        }
        return b.build()
    }

    /** Usa o DNS seguro; se ele falhar (rede bloqueia DoH), cai para o DNS do sistema. */
    private class FallbackDns(private val primary: Dns) : Dns {
        override fun lookup(hostname: String): List<InetAddress> = try {
            primary.lookup(hostname)
        } catch (e: UnknownHostException) {
            Dns.SYSTEM.lookup(hostname)
        }
    }
}
