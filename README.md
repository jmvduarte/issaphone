<p align="center"><img src="design/previa.png" alt="Ícone do IssaFone" width="420"></p>

# IssaFone

Softphone com chat interno para o **Issabel 4**, com versões **Android**, **iOS** e **Web** (PC e iPhone no browser).
- Chamadas de voz por **SIP** (Asterisk) com o Linphone SDK
- Chat interno por **XMPP** (Openfire)

Projeto académico — CTESP Redes e Sistemas Informáticos, IPCA.

| Pasta | Plataforma | Tecnologia | Build automático |
|-------|------------|------------|------------------|
| [`android/`](android/README.md) | Android 8+ | Kotlin, Jetpack Compose, Linphone, Smack | `.apk` (GitHub Actions, Linux) |
| [`ios/`](ios/README.md) | iOS 16+ | Swift, SwiftUI, Linphone, XMPPFramework | `.ipa` sem assinatura (GitHub Actions, macOS) |
| [`web/`](web/README.md) | PC e iPhone (browser) | HTML/JS, JsSIP (WebRTC), Strophe.js | GitHub Pages: IssaFone https://jmvduarte.github.io/issaphone/ · IssaLink https://jmvduarte.github.io/issaphone/issalink/ |

A configuração do servidor Issabel está no [guia passo a passo](GUIA-SERVIDOR.md).

## IssaLink: usar na tua própria central Issabel

O **IssaLink** é a mesma app Android, mas **sem os dados do Issabel do projeto**: no ecrã de entrada pede o servidor, a extensão e a palavra-passe. Pode ficar instalada ao lado do IssaFone.

👉 **[Guia passo a passo para preparar o servidor e instalar as apps](GUIA-SERVIDOR.md)**, do domínio e das portas do router até ao primeiro telefonema.

Resumo:
1. Domínio a apontar para o IP público e portas abertas no router.
2. Openfire instalado no Issabel.
3. `DOMINIO=o.teu.dominio bash configurar-issabel.sh`
4. Extensões 100–150 (app) e 200–250 (webapp) criadas no Issabel.
5. `IssaLink.apk` da Release `issalink-N` mais recente, em [Releases](https://github.com/jmvduarte/issaphone/releases).
6. Webapp do IssaLink: `https://O_TEU_DOMINIO/issalink/`, instalada pelo script, ou https://jmvduarte.github.io/issaphone/issalink/, que pede o servidor.

## Enviar para o GitHub

1. Em github.com, cria um repositório **vazio** chamado `IssaFone`, sem README nem .gitignore. Torna-o **público** se quiseres os minutos de macOS grátis.
2. Nesta pasta:

```bash
git remote add origin https://github.com/O_TEU_UTILIZADOR/IssaFone.git
git push -u origin main
```

O repositório já vem inicializado com o primeiro commit. Se a pasta `.git` não vier (por exemplo, ao extrair no Windows), faz primeiro:

```bash
git init -b main
git add .
git commit -m "IssaFone: versões Android e iOS"
```

3. Abre o separador **Actions**: os dois builds arrancam sozinhos. Depois, cada `push` que mexa em `android/` ou `ios/` volta a compilar só essa plataforma. Também podes correr qualquer um à mão em *Run workflow*.

## Obter as apps

**Android**
- Em *Actions → Android build →* última execução, descarrega o artefacto **IssaFone-apk** e instala o `IssaFone-debug.apk` no telemóvel. É preciso autorizar "instalar apps de origem desconhecida".
- Para uma versão "oficial" com link fixo, cria uma tag e o APK fica publicado em *Releases*:
  ```bash
  git tag v1.0 && git push origin v1.0
  ```

**iOS**
- Em *Actions → iOS build*, descarrega **IssaFone-ipa** e instala-o com o Sideloadly. Os passos estão em [`ios/README.md`](ios/README.md).

## (Opcional) APK de release assinado

O APK de debug é assinado com uma chave temporária que muda em cada build. Na prática, isto obriga a desinstalar a versão anterior antes de instalar a nova. Para teres sempre a mesma assinatura:

1. Cria uma keystore (uma vez só e **guarda-a bem**, porque sem ela não consegues atualizar a app):
   ```bash
   keytool -genkeypair -v -keystore issafone.jks -alias issafone -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Converte-a para texto:
   - Linux/macOS: `base64 -w0 issafone.jks > issafone.txt`
   - Windows (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("issafone.jks")) > issafone.txt`
3. No GitHub, vai a *Settings → Secrets and variables → Actions → New repository secret* e cria:

| Nome do secret | Valor |
|--------|-------|
| `ANDROID_KEYSTORE_BASE64` | conteúdo do `issafone.txt` |
| `ANDROID_KEYSTORE_PASSWORD` | palavra-passe da keystore |
| `ANDROID_KEY_ALIAS` | `issafone` |
| `ANDROID_KEY_PASSWORD` | palavra-passe da chave |

A partir daí, cada build gera também o `IssaFone.apk` assinado. **Nunca faças commit da `.jks`**; o `.gitignore` já a exclui.

## Ícone

O ícone é gerado a partir de um único desenho por `design/gerar-icones.py`: um balão de conversa (o chat) com um auscultador (as chamadas). O script produz o ícone adaptativo do Android, incluindo a versão monocromática para os ícones temáticos do Android 13+, e o ícone de 1024 px do iOS. Para mudar as cores, altera `C1`/`C2` no script e corre:

```bash
pip install cairosvg pillow
python3 design/gerar-icones.py
```

