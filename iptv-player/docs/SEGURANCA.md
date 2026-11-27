# Relatório de segurança — Eli Player 4K (antes IPTV Player 4K) v1.5.0

Teste feito em 26/11/2026 sobre o APK de produção (release, ofuscado com R8):

- **No aparelho:** emulador oficial **Android TV** (Android 7.0, 1920×1080, `leanback_only`), usando só o
  controle remoto. Foi feito com e sem acesso **root**, simulando alguém que tenta tirar dados de uma TV Box.
- **No código e no APK:** manifesto, componentes, rede, WebView, logs e assinatura.

## Resultado

| # | Verificação | 1.4.0 | 1.5.0 | Como foi testado |
|---|---|---|---|---|
| 1 | Senha, usuário e servidor da lista no armazenamento do app | Cifrados | **Cifrados** | Root: busca por `SenhaSecreta123`, usuário e servidor em todos os arquivos → nada encontrado |
| 2 | Lista M3U, retratos e catálogo de canais | Cifrados | **Cifrados** | Root: todos os arquivos começam com a marca de cifra `IPE1` |
| 3 | Catálogo do painel Xtream (`library/*.json`) | **Sem cifra** | **Cifrado** | Root: `get_vod_streams.json` etc. agora começam com `IPE1` |
| 4 | Cache do guia (`epg/*.bin`) | **Sem cifra** | **Cifrado** | Root: arquivo do guia começa com `IPE1`; "Flamengo" não aparece em texto |
| 5 | Ajustes (SharedPreferences) | Cifrados | **Cifrados** | Root: valores `enc1:…` (listas, recentes, validade, PIN) |
| 6 | Cache de imagens | Sem dados sensíveis | **Sem dados sensíveis** | Nomes são hashes; nenhuma URL do servidor gravada |
| 7 | Log do sistema (logcat) | Risco: logs do player | **Limpo** | Log completo do aparelho após usar e errar canais: 0 ocorrências de senha, usuário, servidor ou links |
| 8 | Ler dados sem root (`run-as`) | Bloqueado | **Bloqueado** | "Package is not debuggable" |
| 9 | Backup (`adb backup`, nuvem, troca de aparelho) | Desativado | **Desativado** | Backup gerado vazio (41 bytes, só cabeçalho) |
| 10 | Outros apps abrindo telas internas (Player, Ajustes, Detalhes, Multi-tela, Trailer) | Bloqueado | **Bloqueado** | Todas: "Permission Denial … not exported" — ninguém entra em Ajustes por fora para pular o PIN |
| 11 | Dados estranhos enviados à tela principal (única exportada) | — | **Ignorados** | Intent com `file://` de arquivo interno e extras inválidos: o app não abre nada nem trava |
| 12 | PIN dos pais — força bruta | **Tentativas ilimitadas** | **Bloqueio após 5 erros** | TV: 5º erro → "Tente de novo em 30 s"; durante o bloqueio até o PIN **certo** é recusado; tempo dobra a cada novo erro (até 15 min) e continua valendo após fechar o app ou reiniciar |
| 13 | PIN dos pais — armazenamento | Hash com sal | **Hash com sal cifrado no Keystore** | Root: `pin_s = enc1:…` (o arquivo copiado não permite testar PINs fora do aparelho) |
| 14 | Tela segura (prints, gravação, espelhamento, apps recentes) | **Só na tela inicial** | **Todas as telas** | Com a opção ligada, print da tela inicial e de Ajustes = 0 bytes; janela de login/PIN sempre protegida |
| 15 | Senha/PIN lidos por serviço de acessibilidade (Android 7) | **Expostos** | **Ocultos** | Leitura de acessibilidade do campo: antes `SenhaSecreta123`, agora `••••` |
| 16 | Sobreposição de tela (tapjacking) no PIN e no login da lista | Sem proteção | **Toques bloqueados com sobreposição** | `filterTouchesWhenObscured` |
| 17 | Página do trailer (WebView) | Parcial | **Endurecida** | Sem acesso a arquivos/conteúdo, só abre links `http/https`, ID do vídeo validado (11 caracteres) |
| 18 | Interceptação de HTTPS com certificado instalado pelo usuário | Bloqueada | **Bloqueada** | Só certificados do sistema (`network_security_config`) |
| 19 | Código legível no APK | Ofuscado | **Ofuscado** | R8: só as classes exigidas pelo manifesto mantêm o nome (demais: `n7`, `Fp`, `VD`…) |
| 20 | Chaves/senhas embutidas no APK | Nenhuma | **Nenhuma** | Busca no `classes.dex` |
| 21 | App depurável | Não | **Não** | `debuggable=false` |
| 22 | Assinatura do APK | **Chave de depuração** | **Chave de depuração** | Ver recomendação 1 |

## Correções feitas nesta versão

1. Catálogo Xtream e guia gravados cifrados (AES, chave protegida pelo Android Keystore); arquivos antigos
   continuam abrindo e passam a ser regravados cifrados.
2. PIN com bloqueio progressivo (5 erros → 30 s, dobrando até 15 min), guardado cifrado no Keystore.
3. "Tela segura" aplicada a todas as telas do app.
4. Logs internos do player desligados na versão final (os links das listas Xtream contêm usuário e senha).
5. Campos de senha e PIN não entregam o texto a serviços de acessibilidade.
6. Proteção contra sobreposição de tela no PIN e no login.
7. WebView do trailer: sem acesso a arquivos, só links web, ID validado.

## Riscos que continuam (e por quê)

- **Listas em HTTP.** A maioria dos servidores IPTV só funciona em HTTP, então o usuário e a senha da lista
  trafegam sem cifra na rede (é assim no painel do fornecedor, não no app). Use o endereço `https://` quando o
  fornecedor oferecer. O DNS seguro (Ajustes → DNS seguro) evita bloqueio e espionagem por DNS da operadora,
  mas não cifra o vídeo.
- **Aparelho com root.** Quem tem root e o próprio aparelho pode, em tese, usar o Keystore daquele aparelho
  para abrir os dados enquanto o app está instalado. Fora do aparelho (arquivo copiado), não.
- **Android 5.x.** Sem Keystore AES: a chave fica só no armazenamento privado do app.

## Recomendações

1. **Chave de assinatura definitiva.** O APK é assinado com uma chave de depuração criada no ambiente de
   build. Ela não é pública, mas se for perdida as próximas versões não instalam por cima (é preciso
   desinstalar e cadastrar as listas de novo). O ideal é gerar uma chave própria de produção, guardá-la
   fora do repositório (nunca no GitHub) e passar a assinar com ela — a troca exige desinstalar a versão
   atual uma única vez.
2. Ativar o PIN dos pais e "Proteger listas com PIN" em aparelhos compartilhados.
3. Ligar a "Tela segura" se não quiser que a lista apareça em prints ou espelhamento de tela.

## Atualização 1.8.0 — permissões

O APK declara **apenas** `android.permission.INTERNET`, uma permissão "normal" que o Android
concede na instalação, sem nenhuma pergunta ao usuário. Sem ela não é possível tocar a lista.

| Removida | Origem | Efeito |
|---|---|---|
| `ACCESS_NETWORK_STATE` | Media3 (estimativa de banda) | Media3 trata a falta (SecurityException) e usa a estimativa padrão |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | androidx.core | Receptor do PiP registrado direto; no Android < 13 aceita só intents com um segredo aleatório do processo |

Conferência: `aapt2 dump permissions app-release.apk` → somente `INTERNET`.
