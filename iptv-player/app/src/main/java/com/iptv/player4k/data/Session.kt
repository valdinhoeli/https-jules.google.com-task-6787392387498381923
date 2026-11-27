package com.iptv.player4k.data

import com.iptv.player4k.epg.EpgData

/** Dados carregados compartilhados entre telas (evita passar listas grandes por Intent). */
object Session {
    @Volatile var playlist: Playlist? = null
    @Volatile var library: Library = Library.EMPTY
    @Volatile var epg: EpgData? = null
    /** painel Xtream detectado para a lista atual (catálogo, sinopses e guia pela API) */
    @Volatile var creds: XtreamCreds? = null
    /** canais ao vivo visíveis (respeitando o controle dos pais) */
    @Volatile var live: List<Channel> = emptyList()
    /** todos os grupos/categorias da lista (para a tela de bloqueio) */
    @Volatile var allGroups: List<String> = emptyList()
    /** "Quem está assistindo?" já foi mostrado nesta abertura do app */
    @Volatile var profileAsked: Boolean = false
    /** Perfil com senha que já foi aberto (com a senha) nesta execução do app */
    @Volatile var unlockedProfileId: String? = null
}
