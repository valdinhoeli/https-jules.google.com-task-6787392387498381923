# Teste de funcionamento — lista com 1000 canais, 1000 filmes e 1000 séries

Data: 26/11/2026 · versão testada: 1.7.0 (+ correções da 1.7.1)

## Como foi feito

- **Painel Xtream simulado** (`tools/mock_xtream_1000.py`) com:
  - **1000 canais** em 10 grupos: Abertos 100, Notícias 100, Esportes 150, Filmes 150, Séries 100,
    Infantil 120, Novelas 80, Documentários 100, Música 80, Adultos 20
  - **1000 filmes** em 9 categorias (Ação 200, Comédia 200, Drama 150, Terror 100, Infantil 100,
    Animação 80, Esportes 70, Documentário 70, Adultos +18 30), com sinopse (get_vod_info)
  - **1000 séries** em 8 categorias (Drama 250, Comédia 150, Suspense 150, Novelas 150, Desenhos 120,
    Reality 80, Documentários 50, Esportes 50), cada uma com 2 temporadas × 5 episódios
  - **Guia (EPG) de 24 h** para os 1000 canais (16.901 programas): jogos ao vivo marcados, reprises
    "(VT)", programas de estúdio, canais de filmes passando "Rocky x Drago", novelas nos canais
    abertos, validade da assinatura (30 dias)
- **Android TV** (emulador oficial, 1920×1080), só com o controle remoto, com PIN dos pais ativo.
- **Celular** (emulador que decodifica vídeo) para conferir a reprodução.

## Resultado: 27 de 27 verificações passaram

| # | Verificação | Resultado |
|---|---|---|
| 1 | Lista carregada após "Atualizar" (1000 canais + guia + catálogo) | ✅ 94 s no emulador da TV |
| 2 | TV AO VIVO = 980 canais (20 adultos escondidos pelo PIN) | ✅ |
| 3 | FILMES = 970 (30 adultos escondidos pelo PIN) | ✅ |
| 4 | SÉRIES = 1000 | ✅ |
| 5 | KIDS = 300 títulos · 120 canais | ✅ |
| 6 | ESPORTES = jogos ao vivo · 120 títulos | ✅ |
| 7 | NOVELAS = 150 títulos · 80 canais | ✅ |
| 8 | Validade da lista (vence em 30 dias) | ✅ "Válida até 26/12/2026 · 29 dias" |
| 9 | Rodapé de esportes só com jogos (sem filme, VT, resenha ou gols da rodada) | ✅ |
| 10 | Rodapé sem limite de 30 jogos ao vivo | ✅ (corrigido na 1.7.1) |
| 11 | Lista ao vivo: "Todos" com 980 canais | ✅ |
| 12 | Grupo "Adultos" escondido pelo PIN | ✅ |
| 13 | Grupo Esportes = 150 canais | ✅ |
| 14 | Rolar 40 canais seguidos sem travar a tela | ✅ 0 quadros pulados |
| 15 | Busca de canal estando em outro grupo ("Kids Fun 77" dentro de Esportes) | ✅ (corrigido na 1.7.1) |
| 16 | Guia: programação de 24 h, linha do "agora" e prévia | ✅ |
| 17 | FILMES: categorias na barra lateral | ✅ |
| 18 | FILMES: "Adultos +18" escondida pelo PIN | ✅ |
| 19 | Detalhes do filme com sinopse do painel | ✅ |
| 20 | Série com temporadas e episódios do painel | ✅ |
| 21 | KIDS: 120 canais infantis, foco no 1º item | ✅ |
| 22 | ESPORTES: ao vivo agora, próximos jogos e 150 canais | ✅ |
| 23 | NOVELAS: no ar agora (canais abertos) e 80 canais de novelas | ✅ |
| 24 | Favorito marcado na Guia (★) aparece em ★ Favoritos | ✅ (e "Recentes" com os canais abertos) |
| 25 | Canal ao vivo toca | ✅ |
| 26 | ▲ troca para o próximo canal e continua tocando | ✅ |
| 27 | Filme e episódio de série tocam | ✅ |

## Fluidez com essa lista (Android TV)

| Situação | Quadros pulados (tela travada) |
|---|---|
| Navegar na tela inicial | 0 |
| Rolar a lista de canais | 0 |
| Trocar de grupo | 0 |
| Abrir Ajustes | ~180 por abertura no emulador (≈ 0,2 s numa TV Box) |

Abertura do app (fria): 5,9–7,3 s no emulador sem aceleração (numa TV Box real é bem menor).

## Falhas encontradas e corrigidas (versão 1.7.1)

1. **Busca presa ao grupo:** estando num grupo (ex.: Esportes), buscar um canal de outro grupo não
   achava nada ("0 canais"). Agora a busca procura em todos os canais.
2. **Rodapé de esportes limitado a 30 jogos ao vivo:** com muitos canais de esporte, parte dos jogos
   ao vivo não aparecia (e o cartão ESPORTES dizia "30 ao vivo"). Agora mostra até 100 ao vivo e 60
   próximos.

## Observações

- No Android TV, "OK longo" para favoritar não pôde ser simulado pela ferramenta de teste (ela envia a
  tecla rápido demais); o favorito foi testado pelo botão ★ da Guia.
- O emulador de Android TV não decodifica vídeo; a reprodução foi conferida no emulador de celular.
- Jogos com o mesmo confronto exibidos de novo mais tarde (em qualquer canal) contam como reexibição
  e não entram como "próximos" — por isso o rodapé mostrou poucos "em breve" nessa lista sintética.
