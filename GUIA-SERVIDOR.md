# Guia passo a passo: preparar o teu Issabel para o IssaLink

Este guia serve para quem quer usar o **IssaLink** (app Android), a **webapp** (PC e iPhone) e o **chat** na sua própria central **Issabel 4**. No fim tens:

- chamadas de voz e vídeo **cifradas** (SIP sobre TLS + SRTP);
- chat interno com grupos, anexos e histórico (Openfire);
- contactos automáticos: cada extensão criada no Issabel aparece sozinha na lista de todos;
- webapp em `https://O_TEU_DOMINIO/issafone/`, com notificações de chamadas no iPhone.

Tempo estimado: 45–60 minutos.

---

## 0. O que precisas

| | |
|---|---|
| Servidor | **Issabel 4** instalado a partir do ISO (CentOS 7), com acesso **root** por SSH |
| Rede | IP fixo na rede local para o Issabel (ex.: `192.168.1.10`) e acesso ao **router** para abrir portas |
| Domínio | um nome que aponte para o IP público de casa/empresa (ex.: `pbx.empresa.pt` ou um gratuito do No-IP, `nome.ddns.net`) |
| Telemóveis | Android 8 ou mais recente (IssaLink). iPhone e PC usam a webapp |

> Nos passos seguintes, troca sempre `O_TEU_DOMINIO` pelo teu domínio e `IP_DO_ISSABEL` pelo IP local do Issabel.

---

## 1. Domínio (DNS dinâmico)

Se não tens um domínio próprio, usa um gratuito:

1. Cria conta em [noip.com](https://www.noip.com) e cria um *hostname* (ex.: `minhaempresa.ddns.net`) a apontar para o teu IP público.
2. Para o nome acompanhar o IP quando a operadora o muda, ativa o **DDNS** no router (menu *DDNS / DNS dinâmico*, escolhe No-IP e põe a conta) ou instala o cliente do No-IP num PC da rede.
3. Confirma, num PC: `nslookup O_TEU_DOMINIO` deve devolver o teu IP público.

---

## 2. Abrir portas no router

No router, cria estas regras de reencaminhamento (*port forwarding / NAT*) para o `IP_DO_ISSABEL`:

| Porta | Protocolo | Para quê |
|---|---|---|
| 80 | TCP | Certificado Let's Encrypt (obrigatória) |
| 443 | TCP | Webapp, e chamadas/chat em redes que bloqueiam outras portas |
| 5061 | TCP | Chamadas da app (SIP cifrado, TLS) |
| 5222 | TCP | Chat da app |
| 7443 | TCP | Chat da webapp |
| 8089 | TCP | Chamadas da webapp |
| 5060 | UDP | SIP |
| 10000–20000 | UDP | Áudio e vídeo das chamadas |

Opcional, para entrares por SSH de fora: porta externa **2222** TCP → `IP_DO_ISSABEL` porta **22**.

> Testa no fim com um verificador online (ex.: *Open Port Checker*) se a 80 e a 443 aparecem abertas.

---

## 3. Entrar no servidor e corrigir os repositórios

Entra por SSH como root (no Windows: Terminal / PowerShell):

```bash
ssh root@IP_DO_ISSABEL
```

O CentOS 7 já não é suportado e os repositórios antigos deixaram de funcionar. Aponta-os para o arquivo oficial (*vault*):

```bash
sed -i -e 's/^mirrorlist=/#mirrorlist=/' \
       -e 's|^#\s*baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|' \
       /etc/yum.repos.d/CentOS-*.repo
yum clean all && yum makecache
```

Deve terminar sem erros (`Metadata Cache Created`).

---

## 4. Instalar o Openfire (servidor de chat)

```bash
# Java 11
yum install -y java-11-openjdk

# Openfire 4.9.2
curl -L -o /tmp/openfire.rpm "https://www.igniterealtime.org/downloadServlet?filename=openfire/openfire-4.9.2-1.noarch.rpm"
rpm -ivh --nodeps /tmp/openfire.rpm
systemctl enable openfire
service openfire start
```

> Se o Openfire não arrancar (`service openfire status`), indica-lhe o Java:
> `echo 'JAVA_HOME=/usr/lib/jvm/jre-11' >> /etc/sysconfig/openfire && service openfire restart`

### 4.1 Assistente de configuração do Openfire

O painel do Openfire (porta 9090) normalmente só aceita ligações do próprio servidor. Para lá chegares a partir do teu PC, abre um túnel SSH **numa janela nova do Terminal** e deixa-a aberta:

```bash
ssh -L 19090:127.0.0.1:9090 root@IP_DO_ISSABEL
```

No browser do PC abre **http://localhost:19090** e segue o assistente:

1. **Idioma:** Português (ou o que preferires).
2. **Configurações do servidor:**
   - *Nome do domínio XMPP*: `O_TEU_DOMINIO`
   - *FQDN do servidor*: `O_TEU_DOMINIO`
   - o resto fica como está.
3. **Base de dados:** *Base de dados embutida* (Embedded Database).
4. **Perfil:** *Predefinido* (Default).
5. **Conta de administrador:** email e uma palavra-passe forte (é para entrares no painel; guarda-a).
6. No fim, entra no painel com `admin` e essa palavra-passe, para confirmar que funciona.

---

## 5. Correr o script de configuração

De volta ao SSH do servidor:

```bash
curl -fsSL https://raw.githubusercontent.com/jmvduarte/issaphone/main/servidor/configurar-issabel.sh -o configurar-issabel.sh
DOMINIO=O_TEU_DOMINIO bash configurar-issabel.sh
```

O script faz tudo o resto sozinho (e pode ser corrido de novo sempre que quiseres atualizar):

- certificado **Let's Encrypt** para o domínio (renovado automaticamente) no Apache, Asterisk e Openfire;
- **WebRTC** no Asterisk (porta 8089) e reencaminhamento pela 443;
- **SIP sobre TLS** (5061), **SRTP obrigatório** e NAT nas extensões das apps;
- **vídeo** (VP8/H.264);
- Openfire a usar as **extensões do Issabel como contas de chat** (mesma palavra-passe);
- plugin de **anexos** do chat (até 50 MB);
- **notificações push** para a webapp no iPhone;
- **webapp** em `https://O_TEU_DOMINIO/issafone/`, atualizada automaticamente;
- regras de **firewall**.

No fim aparece um resumo. O objetivo é **"Tudo configurado."**. Se aparecer algum ✘, vê a secção [Problemas comuns](#9-problemas-comuns).

> **Aviso do Openfire "ainda usa os fornecedores da base de dados":** no painel (http://localhost:19090) vai a *Servidor → Gestão do servidor → Propriedades do sistema* e muda as propriedades indicadas pelo script; depois `service openfire restart` e corre o script outra vez.

---

## 6. Contactos automáticos (uma vez só)

No painel do Openfire: **Utilizadores/Grupos → Grupos → IssaFone → Partilha da lista de contactos**, ativa *Partilhar o grupo na lista de contactos* para **todos os utilizadores** e grava.

A partir daqui, cada extensão vê todas as outras no chat, sem ter de as adicionar.

---

## 7. Criar as extensões no Issabel

No painel web do Issabel (`https://IP_DO_ISSABEL`): **PBX → PBX Configuration → Extensions → Add Extension → Generic SIP Device**.

| Campo | O que pôr |
|---|---|
| User Extension | o número (ver intervalos abaixo) |
| Display Name | o nome da pessoa: é o nome que aparece nos contactos e nas chamadas |
| Secret | uma palavra-passe **forte** (é a que a pessoa usa para entrar na app/webapp) |

| Intervalo | Para quem |
|---|---|
| **100–150** (ou qualquer outra fora de 200–250) | app **IssaLink** (Android) |
| **200–250** | **webapp** (PC e iPhone) |

Carrega em **Submit** e depois no botão vermelho **Apply Config**. Em poucos segundos o servidor prepara a extensão (WebRTC ou TLS/SRTP) e ela aparece nos contactos de todos.

> Cada pessoa deve ter **uma extensão por aparelho**: se o mesmo número estiver ligado em dois sítios, só um deles toca.

---

## 8. Instalar nos telemóveis e PCs

### Android: IssaLink
1. No telemóvel, abre [Releases](https://github.com/jmvduarte/issaphone/releases), procura a mais recente **issalink-N** e descarrega o **IssaLink.apk** (autoriza "instalar apps de origem desconhecida").
2. Abre a app e entra com:
   - **Servidor:** `O_TEU_DOMINIO` (o domínio, **não** o IP: o certificado é do domínio);
   - **Extensão** e **palavra-passe** (*secret*).
3. Aceita as permissões (microfone, câmara, notificações, dispositivos próximos) e os avisos de bateria que a app mostrar, para tocar com a app fechada.
4. No topo deve aparecer **Telefone · registado** e **Chat · ligado**. A app atualiza-se sozinha.

### PC e iPhone: webapp
1. Abre `https://O_TEU_DOMINIO/issafone/` e entra com uma extensão **200–250**.
2. **iPhone:** no Safari, *Partilhar → Adicionar ao ecrã principal*, abre a webapp pelo ícone e toca no 🔔 para ativar as notificações (é assim que recebes chamadas com a página fechada).

---

## 9. Problemas comuns

| Sintoma | Causa provável | Solução |
|---|---|---|
| O script falha no Let's Encrypt | A porta 80 não chega ao Issabel ou o domínio aponta para outro IP | Confirma o passo 1 e a regra da porta 80 no router |
| App: "Falhou: io error" ou deixou de ligar de repente | O **fail2ban** bloqueou o IP (palavra-passe errada várias vezes) | No servidor: `fail2ban-client set asterisk unbanip IP` (vê o IP do telemóvel em ifconfig.me) |
| Chat: `not-authorized` | Palavra-passe errada | É a mesma do *secret* da extensão |
| Webapp: "recusado: … extensão não é web" | Extensão fora de 200–250, ou palavra-passe errada | Usa uma extensão 200–250 |
| Chamadas sem som ou que caem aos ~30 s | Portas UDP 10000–20000 / TCP 5061 não reencaminhadas | Revê o passo 2 e corre o script de novo |
| Rede de empresa/escola: não liga | A rede bloqueia portas que não sejam a 443 | Usa a webapp (funciona só pela 443) ou os dados móveis |
| Contactos vazios | Falta o passo 6 | Ativa a partilha do grupo IssaFone no Openfire |
| Não consigo enviar anexos | Plugin não instalado | Painel do Openfire → *Plugins → Plugins disponíveis → HTTP File Upload* |

Para não ficares bloqueado tu próprio pelo fail2ban, põe a rede local na lista de exceções:

```bash
printf '[DEFAULT]\nignoreip = 127.0.0.1/8 192.168.0.0/16\n' >> /etc/fail2ban/jail.local && systemctl restart fail2ban
```

### Comandos úteis

```bash
asterisk -rx "sip show peers"                 # extensões registadas
asterisk -rx "sip show peer 101" | grep -iE "addr|encryption|rport"
fail2ban-client status asterisk               # IPs bloqueados
journalctl -t issafone                        # o que o serviço automático fez
issafone-atualizar-web                        # atualizar a webapp agora
tail -f /var/log/asterisk/full                # registo do Asterisk em tempo real
```
