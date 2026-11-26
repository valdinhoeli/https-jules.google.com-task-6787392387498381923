# Verificação de performance — v1.7.2

Data: 26/11/2026 · lista de teste: 1000 canais, 1000 filmes, 1000 séries, guia de 24 h (16.901 programas).
Aparelhos: emulador de celular (Android 7, 2 núcleos) e emulador oficial de Android TV (1 núcleo).
Os dois **não têm aceleração nem placa de vídeo** (tudo é desenhado pela CPU): os tempos absolutos são
várias vezes maiores que numa TV Box real. O que vale é a comparação antes × depois.

## Resultado

| Item | Antes (1.7.1) | Agora (1.7.2) |
|---|---|---|
| CPU com o app parado na tela inicial | **22 % de um núcleo** (pico de ~12 s de CPU a cada minuto) | **0,3 %** |
| Primeira tela desenhada (celular) | 10,2 s | 6,0–8,2 s |
| Abertura fria (TV) | 5,9–7,3 s | 5,3–6,2 s |
| Abertura com o app em segundo plano | 0,7–0,8 s | 0,3–0,7 s |
| Requisições de rede ao reabrir o app (4 aberturas) | 0 | 0 (usa o que já está salvo) |
| Memória na tela inicial | 39 MB | 34 MB |
| Memória com todas as seções abertas | 95 MB | 63 MB |
| Memória quando o sistema avisa "pouca memória" | não liberava as seções | libera as seções escondidas |
| Abrir/fechar o player 5× (vazamento de telas) | celular: **o player não fechava** | fecha sempre; nenhuma tela presa na memória |
| Quadros pulados: navegar na tela inicial / lista / trocar de grupo (TV) | 0 / 0 / 0 | 0 / 0 / 0 |
| Quadros pulados: abrir Ajustes e voltar 3× (TV) | 553 | 223 |
| Travamentos (crash) | 1 (ao liberar memória) | 0 |
| Tamanho do APK | 3,5 MB | 3,5 MB |
| Espaço usado no aparelho | — | 1,4 MB de dados + 1,6 MB de cache de imagens |

## O que foi corrigido

1. **CPU parada a cada minuto:** o rodapé/aba de esportes reclassificava o guia inteiro (1000 canais ×
   todos os programas, com expressões regulares) a cada minuto. Agora o guia é classificado **uma vez**
   quando carrega; a cada minuto só se filtra pelo horário.
2. **Abertura:** a tela da TV AO VIVO (lista de canais) era montada escondida atrás da tela inicial ao
   abrir o app. Agora cada seção é criada só quando o usuário entra nela.
3. **Player não fechava no celular:** no canal ao vivo, Voltar abria os grupos e o segundo Voltar
   deveria sair; no celular (modo de toque, sem foco) ele reabria os grupos para sempre. Corrigido.
4. **Memória:** cache de capas na memória limitado a 15 % (antes podia chegar a 25 % do heap grande) e,
   quando o sistema avisa que falta memória, as seções escondidas (Filmes, Séries, KIDS…) são descartadas
   e recriadas quando o usuário volta a elas.
5. **Travamento encontrado no teste:** a troca automática do destaque (Filmes/Séries/KIDS) continuava
   rodando com a tela escondida ou já destruída e derrubava o app. Agora pausa quando a tela some.

## Observações

- A maior parte do tempo de abertura que sobra no emulador é desenho da tela pela CPU (sem placa de
  vídeo); numa TV Box com GPU isso cai para uma fração de segundo.
- O catálogo (1000 filmes + 1000 séries) e o guia vêm do armazenamento cifrado do aparelho em ~1 s e não
  usam a internet ao reabrir; a lista só é baixada de novo após 24 h ou no botão Atualizar.
