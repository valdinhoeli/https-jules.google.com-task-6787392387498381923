# Como continuar o projeto fora do GitHub

O projeto inteiro (código, imagens, documentação, testes e o servidor de teste) funciona em qualquer
computador, sem depender do GitHub. Você recebeu 3 arquivos:

| Arquivo | O que é |
|---|---|
| `Eli-Player-4K-projeto-v1.11.0.zip` | O projeto completo (esta pasta), pronto para abrir e compilar |
| `Eli-Player-4K-historico.bundle` | Todo o histórico de versões (git), para guardar ou levar para outro serviço |
| `Eli-Player-4K-chave-assinatura.zip` | A chave que assina o APK (**guarde com cuidado**, veja abaixo) |

## 1. A chave de assinatura (o mais importante)

O Android só instala uma versão nova **por cima** da antiga se as duas tiverem a mesma assinatura.
Todos os APKs que você já instalou (1.0 a 1.7.2) foram assinados com a chave que está em
`Eli-Player-4K-chave-assinatura.zip`:

- certificado SHA-256: `D4:1F:1A:4E:8C:7B:47:B1:E1:B4:6E:8B:25:83:F8:B0:DE:47:DE:A6:70:47:87:81:82:96:27:1B:EB:5C:04:60`
- alias `androiddebugkey`, senha `android` (é a senha padrão — troque, veja abaixo)

Se perder essa chave, a próxima versão só instala depois de **desinstalar** o app (e as listas
salvas são apagadas). Por isso:

1. Guarde o arquivo `.jks` em 2 lugares seguros (pendrive + nuvem pessoal). **Nunca** coloque em
   repositório público.
2. Para compilar: copie `iptv-player-assinatura.jks` para esta pasta e copie
   `keystore.properties.exemplo` para `keystore.properties` (os dois já estão no `.gitignore`).
3. Recomendado: troque a senha (o JDK traz o `keytool`):
   ```
   keytool -storepasswd -keystore iptv-player-assinatura.jks          (senha atual: android)
   keytool -keypasswd -alias androiddebugkey -keystore iptv-player-assinatura.jks
   ```
   e coloque as senhas novas no `keystore.properties`.

Sem o `keystore.properties`, o Gradle assina com uma chave de teste do seu computador — o APK
funciona, mas **não atualiza** o app já instalado.

## 2. Abrir e compilar

**Com o Android Studio (mais fácil):**
1. Instale o [Android Studio](https://developer.android.com/studio) (Windows, Mac ou Linux).
2. *Open* → escolha a pasta `iptv-player` (a que tem `settings.gradle.kts`).
3. Espere o Gradle sincronizar (baixa as dependências na primeira vez).
4. Menu *Build → Generate App Bundles or APKs → Build APK(s)*, ou no terminal do Studio:
   `./gradlew assembleRelease` (Windows: `gradlew.bat assembleRelease`).
5. O APK sai em `app/build/outputs/apk/release/app-release.apk`.

**Só pela linha de comando:** JDK 17 + Android SDK (plataforma 35) e a variável `ANDROID_HOME`;
depois `./gradlew testDebugUnitTest assembleRelease`.

Para conferir a assinatura do APK novo (tem que mostrar o mesmo SHA-256 acima):
`apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`
(o `apksigner` fica em `Android/Sdk/build-tools/<versão>/`).

**Versão nova:** aumente `versionCode` (número inteiro, sempre maior) e `versionName` em
`app/build.gradle.kts`. Versão atual: `versionCode = 16`, `versionName = "1.7.2"`.

## 3. Histórico de versões (git)

O arquivo `.bundle` tem os 29 registros de alteração desde a primeira versão. Para usar:
```
git clone Eli-Player-4K-historico.bundle iptv-player-4k
cd iptv-player-4k
git checkout claude/android-iptv-player-apk-6mcleu
```
Para levar para outro serviço (GitLab, Bitbucket, Gitea, Azure DevOps…), crie um repositório vazio
**privado** lá e rode `git remote set-url origin <endereço novo>` e `git push -u origin --all`.
Também dá para trabalhar só no computador, sem nenhum serviço.

## 4. Testes

- Testes automáticos: `./gradlew testDebugUnitTest` (34 testes: lista M3U, Xtream, guia, controle dos pais,
  esportes, KIDS/ESPORTES/NOVELAS, validade).
- Painel simulado para testar com 1000 canais/filmes/séries: `tools/mock_xtream_1000.py`
  (instruções no topo do arquivo). No emulador, cadastre uma lista Xtream com servidor
  `http://10.0.2.2:8001` e qualquer usuário/senha.
- Relatórios: `docs/SEGURANCA.md`, `docs/TESTE-1000.md`, `docs/PERFORMANCE.md`.

## 5. Mapa do código (`app/src/main/java/com/iptv/player4k/`)

| Pasta | O que tem |
|---|---|
| `data/` | Listas (M3U, Xtream, arquivo), catálogo de filmes/séries, validade, KIDS/ESPORTES/NOVELAS, cache cifrado |
| `epg/` | Guia de programação (XMLTV), eventos esportivos ao vivo |
| `player/` | Player (Media3/ExoPlayer), painel de canais e grupos, prévia do guia |
| `ui/` | Tela inicial, seções, filmes/séries, detalhes, guia, ajustes, multi-tela, trailer |
| `security/` | Criptografia (Android Keystore), PIN dos pais, tela segura, campos de senha |

## 6. O GitHub

Nada foi apagado do GitHub: o repositório continua lá com tudo. Se quiser removê-lo, faça pelo site
(Settings → Delete this repository) **depois** de conferir que o projeto abre e compila no seu computador.
