# Relatório de segurança — IPTV Player 4K v1.5.0

Teste feito em 26/11/2026 sobre o APK de produção (release, R8) no emulador Android TV
(Android 7.0, 1920×1080) e por análise do código e do APK.

## Resumo

| # | Verificação | Antes (1.4.0) | Agora (1.5.0) |
|---|---|---|---|
| 1 | Componentes exportados (outros apps abrindo telas internas) | OK — só a tela inicial | OK |
| 2 | App depurável / `run-as` | OK — não depurável | OK |
| 3 | Backup (adb backup, nuvem, troca de aparelho) | OK — desativado | OK |
| 4 | Listas, usuário e senha no aparelho | OK — cifrados (Keystore) | OK |
| 5 | Catálogo do painel Xtream (`library/*.json`) | **Falha — sem cifra** | Corrigido — cifrado |
| 6 | Cache do guia (`epg/*.bin`) | **Falha — sem cifra** | Corrigido — cifrado |
| 7 | PIN dos pais — tentativas | **Falha — ilimitadas** | Corrigido — bloqueio progressivo |
| 8 | PIN dos pais — armazenamento | Hash com sal (fora do Keystore) | Hash com sal **cifrado** no Keystore |
| 9 | "Tela segura" (prints/gravação) | **Falha — só na tela inicial** | Corrigido — todas as telas |
| 10 | Log do sistema com links (usuário/senha) | **Risco — logs do player** | Corrigido — desligados na versão final |
| 11 | Página do trailer (WebView) | Parcial | Endurecida (sem arquivos, só links web, ID validado) |
| 12 | Sobreposição de tela (tapjacking) no PIN/login | Sem proteção | Corrigido — toques bloqueados se houver sobreposição |
| 13 | Certificados instalados pelo usuário (interceptação HTTPS) | OK — só do sistema | OK |
| 14 | Ofuscação do código | OK — R8 com reempacotamento | OK |
| 15 | Segredos embutidos no APK | OK — nenhum | OK |
| 16 | Assinatura do APK | **Atenção — chave de depuração** | Ver recomendações |

