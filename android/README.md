# IssaFone

App Android (Kotlin + Jetpack Compose) que junta, numa só aplicação:

- **Chamadas de voz** por SIP ao Asterisk do Issabel 4 (Linphone SDK)
- **Chat interno** por XMPP ao Openfire instalado no Issabel (Smack)

```
 Telemóvel Android (IssaFone)
   ├── SIP  UDP 5060 + RTP UDP 10000-20000 ──► Asterisk  (Issabel 4)
   └── XMPP TCP 5222 ───────────────────────► Openfire  (no mesmo servidor)
```

## 1. Preparar o Issabel 4

Segue o **[guia passo a passo](../GUIA-SERVIDOR.md)**: domínio, portas, Openfire, script de configuração e extensões.

## 2. Compilar a app

1. Abre a pasta `IssaFone` no **Android Studio** (Koala ou mais recente). Ele gera o Gradle Wrapper e sincroniza as dependências.
2. Liga um telemóvel com depuração USB e carrega em *Run*.
3. Aceita a permissão do microfone.

> O emulador do Android Studio serve para o chat, mas o áudio SIP no emulador é pouco fiável. Testa as chamadas num telemóvel real ligado à mesma rede (Wi-Fi) que o Issabel.

## 3. Usar

1. No ecrã de entrada: IP do Issabel, extensão, palavra-passe da extensão, domínio XMPP e, se for diferente, a palavra-passe do chat (Openfire).
2. No topo aparece o estado: *Telefone: Registado* e *Chat: Ligado como …*.
3. **Teclado**: marca uma extensão e carrega em *Ligar*. Durante a chamada podes silenciar o micro, enviar DTMF ou desligar.
4. **Chat**: adiciona um colega pelo número de extensão, abre a conversa e envia mensagens. O botão *Ligar* na conversa faz uma chamada SIP para a mesma extensão. É aqui que as duas partes da app se ligam.

## 4. Estrutura do código

```
app/src/main/java/pt/ipca/issafone/
├── IssaFoneApp.kt        Application: é aqui que vivem o SIP e o XMPP
├── Config.kt             Configuração guardada + "sessão ativa"
├── MainActivity.kt       Permissões; atender a partir da notificação; ecrã sobre o bloqueio
├── AppViewModel.kt       Ponte entre o ecrã e a sessão; arranca/pára o serviço
├── sip/SipManager.kt     Registo, chamadas, atender/desligar, mute, DTMF (Linphone)
├── xmpp/XmppManager.kt   Ligação, contactos/presença, enviar/receber (Smack)
├── servico/
│   ├── IssaFoneService.kt  Serviço em primeiro plano (mantém tudo ligado)
│   ├── Notificacoes.kt     Canais; chamada recebida (CallStyle); mensagens
│   └── Receivers.kt        Botão "Recusar"; arranque após reiniciar o telemóvel
└── ui/
    ├── EcraLogin.kt          Formulário de configuração
    ├── EcraPrincipal.kt      Teclado, painel de chamada, contactos e conversa
    └── AvisosSegundoPlano.kt Avisa se faltam permissões para tocar em segundo plano
```

## 5. Receber chamadas com a app fechada

Depois de entrares, o IssaFone arranca um **serviço em primeiro plano**. Aparece uma notificação permanente "IssaFone ativo" com o estado do telefone e do chat. Enquanto essa notificação existir:

- o telemóvel continua registado no Asterisk, mesmo com a app fechada ou o ecrã apagado;
- uma chamada a entrar **toca com o toque do telemóvel** e mostra o ecrã de chamada com *Atender*/*Recusar*, também por cima do ecrã de bloqueio;
- as mensagens do chat chegam como notificação, e tocar nelas abre a conversa;
- depois de reiniciar o telemóvel, o IssaFone volta a ligar-se sozinho;
- ao mudar entre Wi-Fi e dados móveis, o registo SIP é refeito de imediato.

Só o botão **Sair** desliga tudo. Deslizar a app para fora dos recentes não a desliga.

### Definições que tens de autorizar
Ao entrar, aparece um cartão vermelho no topo enquanto faltar alguma destas autorizações:

| Definição | Porquê |
|-----------|--------|
| Notificações | Sem elas não há ecrã de chamada nem avisos de mensagens |
| Ignorar otimização de bateria | Senão o Android "adormece" a app com o ecrã apagado (modo Doze) |
| Ecrã de chamada (Android 14+) | Permite acender o ecrã com o telemóvel bloqueado |

### Marcas com gestão de bateria agressiva
Alguns fabricantes matam apps em segundo plano mesmo com tudo isto autorizado. Se a app deixar de tocar ao fim de algum tempo:
- **Xiaomi/Redmi/POCO:** *Definições → Apps → IssaFone → Arranque automático* ON e *Poupança de bateria → Sem restrições*.
- **Samsung:** *Definições → Bateria → Limites de utilização em segundo plano* → tira o IssaFone de "Apps suspensas" e adiciona-o a "Apps nunca suspensas".
- **Huawei/Honor:** *Bateria → Iniciar aplicações* → IssaFone em *Gerir manualmente*, com tudo ligado.
- **OnePlus/Oppo/Realme:** *Bateria → Otimização* → *Não otimizar*, e bloquear a app nos recentes (cadeado).

O site [dontkillmyapp.com](https://dontkillmyapp.com) tem instruções para cada marca.

### Como funciona (para o relatório)
É a abordagem "ligação permanente": o telemóvel mantém o registo SIP e a sessão XMPP abertos, com keep-alives e registos de 120 s. A alternativa usada por apps comerciais é o **push** (Firebase Cloud Messaging): o telemóvel fica desligado e o servidor "acorda-o" quando há uma chamada. Gasta menos bateria, mas exige um servidor intermédio (por exemplo o Flexisip) e uma conta Firebase. Para um PBX interno, a ligação permanente é mais simples e não depende de serviços externos; o custo é algum consumo de bateria a mais.

O serviço usa o tipo `specialUse`, adequado a uma app instalada por APK. Para publicar na Play Store, a Google exige justificar este tipo; nesse caso o caminho seria o push.

## 6. Atualizações automáticas

Cada build do GitHub publica uma Release `android-N`. A app procura versões novas ao abrir e de 6 em 6 horas, através do serviço em segundo plano; nunca o faz durante uma chamada. Quando encontra uma, descarrega o APK e instala-o com o `PackageInstaller` do Android.

- **Na primeira atualização**, o Android pede para autorizar o IssaFone a "instalar apps desconhecidas" e para confirmar.
- **Depois disso**, no Android 12 ou superior, a app atualiza-se sozinha, sem perguntar.
- Com a app fechada, se for preciso confirmar, aparece uma notificação "Nova versão do IssaFone".

Para isto funcionar, todos os APK têm de estar assinados com a mesma chave. Por isso, a chave de debug (`app/issafone-debug.jks`, palavra-passe `android`) está no repositório. Não é segura para publicar na Play Store, mas para testes é o padrão do Android. A versão (`versionCode`) é o número do build no GitHub Actions.

O código está em `atualizacao/Atualizador.kt`.

## 7. Limitações conhecidas (para mencionar no relatório)

- Não usa notificações push: a ligação permanente gasta mais bateria do que uma solução com FCM (ver secção 5).
- As mensagens ficam só em memória. O histórico perde-se ao fechar a app (solução: Room/SQLite ou XEP-0313 MAM no Openfire).
- As mensagens de quem não está na lista de contactos são recebidas, mas só aparecem depois de adicionares esse contacto.
- Palavras-passe guardadas em SharedPreferences simples (em produção: EncryptedSharedPreferences).
- O chat sem TLS e o SIP em UDP sem SRTP não são cifrados. É aceitável em laboratório, mas não na Internet.
- O Linphone SDK é GPLv3. Para uso académico não há problema; numa app comercial fechada seria preciso licença.
