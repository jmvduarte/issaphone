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

### 1.1 Extensões (chamadas)
1. *PBX → PBX Configuration → Extensions → Add Extension* (tipo **SIP**, chan_sip).
2. Define a extensão (ex.: `101`) e o **secret**. Esta é a password SIP da app.
3. Em *Device Options* deixa `nat=yes` e `qualify=yes`.
4. Cria pelo menos duas extensões (101 e 102) para testar chamadas entre telemóveis.

### 1.2 Openfire (chat)
O Issabel 4 não traz o Openfire ativo por defeito. Na consola do servidor (SSH como root):

```bash
# Java (o Openfire 4.7 funciona com Java 11)
yum install -y java-11-openjdk

# Descarregar o RPM do Openfire a partir de https://www.igniterealtime.org/downloads/
# e instalar:
yum localinstall -y openfire-4.7.*.noarch.rpm
systemctl enable --now openfire
```

Depois abre `http://IP_DO_ISSABEL:9090` e conclui o assistente:
- **XMPP Domain**: podes deixar o hostname ou usar o IP. Anota-o, porque é o campo "XMPP Domain" da app.
- Base de dados: *Embedded Database* chega para o projeto.
- Em *Users/Groups → Create New User*, cria utilizadores com o **mesmo nome das extensões** (101, 102…). Se usares também a mesma password do secret, na app basta preencher a password SIP.
- (Opcional) Em *Users/Groups → Groups*, cria um grupo "Empresa", ativa *Contact List (Roster) Sharing* e mete lá todos. Assim os colegas aparecem automaticamente na lista da app.
- Em *Server Settings → Client Connections*, mantém o STARTTLS como **opcional**. A app liga sem TLS por defeito porque o certificado do Openfire é autoassinado.

### 1.3 Firewall do Issabel
Em *Security → Firewall* (ou `firewall-cmd`), garante que estão abertas:

| Porta | Protocolo | Uso |
|-------|-----------|-----|
| 5060 | UDP | SIP |
| 10000-20000 | UDP | Áudio (RTP) |
| 5222 | TCP | Chat XMPP |
| 9090 | TCP | Consola Openfire (só rede interna) |

## 2. Compilar a app

1. Abre a pasta `IssaFone` no **Android Studio** (Koala ou mais recente). Ele gera o Gradle Wrapper e sincroniza as dependências.
2. Liga um telemóvel com depuração USB e carrega em *Run*.
3. Aceita a permissão do microfone.

> O emulador do Android Studio serve para o chat, mas o áudio SIP no emulador é pouco fiável. Testa as chamadas num telemóvel real ligado à mesma rede (Wi-Fi) que o Issabel.

## 3. Usar

1. No ecrã de entrada: IP do Issabel, extensão, secret, XMPP Domain e, se for diferente, a password do Openfire.
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

## 6. Limitações conhecidas (para mencionar no relatório)

- Não usa notificações push: a ligação permanente gasta mais bateria do que uma solução com FCM (ver secção 5).
- As mensagens ficam só em memória. O histórico perde-se ao fechar a app (solução: Room/SQLite ou XEP-0313 MAM no Openfire).
- As mensagens de quem não está na lista de contactos são recebidas, mas só aparecem depois de adicionares esse contacto.
- Passwords guardadas em SharedPreferences simples (em produção: EncryptedSharedPreferences).
- O chat sem TLS e o SIP em UDP sem SRTP não são cifrados. É aceitável em laboratório, mas não na Internet.
- O Linphone SDK é GPLv3. Para uso académico não há problema; numa app comercial fechada seria preciso licença.
