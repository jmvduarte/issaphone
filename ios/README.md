# IssaFone para iOS

Versão iPhone do IssaFone (Swift + SwiftUI), com as mesmas funções da app Android:

- **Chamadas de voz** por SIP ao Asterisk do Issabel 4 (Linphone SDK, `linphonesw`)
- **Chat interno** por XMPP ao Openfire (XMPPFramework)

Usa a mesma configuração de servidor da versão Android: extensões SIP, Openfire e as portas 5060/UDP, 10000-20000/UDP e 5222/TCP. Os passos do Issabel estão em `../android/README.md`.

## Requisitos

- Um **Mac** com **Xcode 15** ou mais recente. Sem Mac não se compilam apps iOS.
- Um iPhone com iOS 16 ou mais recente.
- Uma conta Apple (Apple ID). A conta gratuita chega para instalar no teu iPhone, mas a app expira ao fim de 7 dias e tens de a voltar a instalar a partir do Xcode. A conta paga do Apple Developer Program (99 €/ano) dá 1 ano e permite TestFlight.

## Gerar o projeto Xcode

O projeto é descrito em `project.yml` e gerado com o XcodeGen. Assim evita-se o ficheiro `.xcodeproj`, que é ilegível e difícil de versionar.

```bash
cd ios
./scripts/obter-linphone.sh   # descarrega o SDK Linphone (~150 MB) para ios/LinphoneSDK
brew install xcodegen
xcodegen
open IssaFone.xcodeproj
```

O SDK Linphone vem em zip do `download.linphone.org`, e não do pacote Swift do `gitlab.linphone.org`, porque este último recusa ligações dos servidores do GitHub Actions. O script também deixa a interface Swift da Linphone pronta como o módulo `linphonesw`. O XMPPFramework é descarregado pelo próprio Xcode.

### Alternativa sem XcodeGen
1. Xcode → *File → New → Project → iOS App* (Interface SwiftUI), nome `IssaFone`.
2. Apaga os ficheiros gerados e arrasta para lá a pasta `IssaFone/` deste projeto.
3. *File → Add Package Dependencies…* e adiciona:
   - `https://gitlab.linphone.org/BC/public/linphone-sdk-swift-ios.git` (branch `novideo/stable`, produto `linphonesw`). No teu Mac, o GitLab da Linphone funciona; só os servidores do GitHub é que são bloqueados.
   - `https://github.com/robbiehanson/XMPPFramework.git` (branch `master`)
   - `https://github.com/CocoaLumberjack/CocoaLumberjack.git` (versão exata `3.8.5`, para o XMPPFramework compilar)
4. Em *Signing & Capabilities*:
   - adiciona *Background Modes* → *Audio* e *Voice over IP*;
   - no Info, acrescenta `NSMicrophoneUsageDescription` e `NSLocalNetworkUsageDescription`.

## Correr no iPhone

1. Liga o iPhone por cabo e seleciona-o como destino no Xcode.
2. Em *Signing & Capabilities*, escolhe a tua equipa (Apple ID) e, se for preciso, muda o Bundle ID para algo único (ex.: `pt.ipca.issafone.jorge`).
3. Carrega em *Run*.
4. No iPhone, autoriza o programador em *Definições → Geral → VPN e gestão de dispositivos*.
5. Aceita as permissões de **microfone** e de **rede local**. Sem a de rede local, o iOS bloqueia a ligação ao Issabel.

> O simulador do iOS serve para testar o chat e a interface. As chamadas funcionam no simulador com o micro do Mac, mas é melhor testar num iPhone real na mesma rede Wi-Fi do Issabel.

## Compilar no GitHub (sem Mac)

O workflow `.github/workflows/ios.yml` (na raiz do repositório) compila a app num Mac do GitHub e gera um `.ipa` **sem assinatura**. A assinatura e a instalação fazem-se depois no Windows, com o teu Apple ID.

1. Envia o repositório para o GitHub (ver README da raiz).
2. Em *Actions → iOS build → Run workflow*, ou simplesmente com um `push` para `main`, o build arranca. Demora 10-20 min (a primeira vez é mais lenta por causa do Linphone).
3. No fim da execução, descarrega o artefacto **IssaFone-ipa** (vem dentro de um .zip).
4. No Windows, instala o **iTunes** (versão do site da Apple, não a da Microsoft Store) e o **Sideloadly**.
5. Liga o iPhone por cabo, arrasta o `IssaFone-unsigned.ipa` para o Sideloadly, mete o teu Apple ID e carrega em *Start*. O Sideloadly assina a app e instala-a.
6. No iPhone, autoriza o programador em *Definições → Geral → VPN e gestão de dispositivos* e, no iOS 16+, ativa o *Modo de programador* em *Definições → Privacidade e segurança*.

Notas:
- Com Apple ID gratuito, a app expira ao fim de **7 dias**. Volta a instalar com o Sideloadly, sem precisares de compilar de novo.
- Repositório **público**: minutos de Actions gratuitos. Repositório **privado**: os minutos de macOS gastam a quota do plano gratuito muito mais depressa do que os de Linux. Para um projeto académico, um repositório público é o mais simples.
- Se o build falhar, descarrega o artefacto **xcodebuild-log** e manda-me as linhas com `error:`.

## Estrutura do código

```
IssaFone/
├── IssaFoneApp.swift        Entrada; mostra login ou ecrã principal
├── AppModel.swift           Junta SIP + XMPP, guarda a configuração
├── SIP/SipManager.swift     Registo, chamadas, atender/desligar, mute, DTMF (Linphone)
├── XMPP/XmppManager.swift   Ligação, contactos/presença, enviar/receber (XMPPFramework)
└── UI/
    ├── EcraLogin.swift      Formulário de configuração
    └── EcraPrincipal.swift  Teclado, painel de chamada, contactos e conversa
```

A estrutura é a mesma da versão Android (`SipManager`/`XmppManager`/ecrãs), o que dá jeito para comparar as duas implementações no relatório.

## Limitações conhecidas

- **Segundo plano:** no iOS isto é mais restritivo do que no Android. Com a app em segundo plano ou fechada, o sistema suspende-a e deixa de receber chamadas e mensagens. Receber chamadas com a app fechada exige **CallKit + PushKit** (notificações VoIP), e isso obriga a ter um servidor de push e a conta paga da Apple. Fica como trabalho futuro.
- Mensagens só em memória: o histórico perde-se ao fechar a app.
- Configuração em UserDefaults; em produção, as passwords iriam para a Keychain.
- Chat sem TLS obrigatório e aceitação do certificado autoassinado. SIP em UDP sem SRTP. Serve para laboratório, não para a Internet.
- O XMPPFramework não tem versão oficial recente com suporte a Swift Package Manager, por isso usa-se o branch `master`.
- O Linphone SDK é GPLv3: sem problema para uso académico.
