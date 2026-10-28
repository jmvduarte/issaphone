# IssaFone Web

Versão browser do IssaFone, para **PC (Windows, macOS, Linux)** e **iPhone/iPad**. Tem as mesmas funções das apps: teclado, chamadas com silenciar e teclas DTMF, contactos com presença, chat e o botão "Ligar" na conversa.

- Chamadas: **SIP sobre WebSocket + WebRTC** ([JsSIP](https://jssip.net)) → Asterisk do Issabel
- Chat: **XMPP sobre WebSocket** ([Strophe.js](https://strophe.im/strophejs/)) → Openfire
- É uma página estática, sem servidor próprio nem compilação. As bibliotecas estão em `vendor/`, por isso funciona sem Internet numa rede local.
- O histórico do chat fica guardado no browser.

```
 Browser (IssaFone Web)
   ├── WSS 8089  (SIP)  + áudio SRTP/DTLS (UDP 10000-20000) ──► Asterisk
   └── WSS 7443  (XMPP) ─────────────────────────────────────► Openfire
```

## Onde funciona bem e onde não

| | PC | iPhone / iPad |
|---|---|---|
| Fazer e receber chamadas | ✅ com o separador aberto, mesmo minimizado | ✅ só com a app aberta no ecrã |
| Notificações | ✅ chamadas e mensagens, com o separador em segundo plano | ⚠️ só com a app aberta |
| "Instalar" como app | Chrome/Edge: ícone de instalar na barra de endereço | Safari → Partilhar → *Adicionar ao ecrã principal* |

No iPhone, a limitação é a mesma da app nativa: o iOS suspende as páginas que não estão no ecrã. No PC não há esse problema, e a webapp é a melhor solução para quem trabalha ao computador.

## 1. Requisito obrigatório: HTTPS com certificado válido

Os browsers só dão acesso ao microfone em páginas HTTPS, e as ligações WebSocket seguras (`wss://`) ao Asterisk e ao Openfire também têm de ter um certificado válido. O iPhone recusa certificados autoassinados sem mostrar nenhum aviso.

1. Arranja um **domínio** para o Issabel, por exemplo `pbx.exemplo.pt` ou um subdomínio grátis do DuckDNS, e aponta-o para o IP público.
2. No Issabel (CentOS 7), obtém um certificado Let's Encrypt:
   ```bash
   yum install -y certbot
   certbot certonly --webroot -w /var/www/html -d pbx.exemplo.pt
   ```
3. Copia-o para o Asterisk:
   ```bash
   mkdir -p /etc/asterisk/keys
   cp /etc/letsencrypt/live/pbx.exemplo.pt/{fullchain,privkey}.pem /etc/asterisk/keys/
   chown -R asterisk:asterisk /etc/asterisk/keys
   ```
4. No Apache, em `/etc/httpd/conf.d/ssl.conf`, aponta `SSLCertificateFile` e `SSLCertificateKeyFile` para os mesmos ficheiros e corre `systemctl restart httpd`.

> **Só para testar no PC, sem domínio:** abre uma vez `https://IP:8089/ws` e `https://IP:7443/ws/` no Chrome e aceita os avisos de certificado. Isto não funciona no iPhone.

## 2. Asterisk: SIP sobre WebSocket (WSS)

Confirma a versão com `asterisk -V`. É preciso **13.15 ou superior**: as versões mais antigas não suportam `rtcp_mux`, que o Chrome exige.

Em `/etc/asterisk/http_custom.conf` (ou `http.conf`):

```ini
[general]
enabled=yes
bindaddr=0.0.0.0
bindport=8088
tlsenable=yes
tlsbindaddr=0.0.0.0:8089
tlscertfile=/etc/asterisk/keys/fullchain.pem
tlsprivatekey=/etc/asterisk/keys/privkey.pem
```

```bash
asterisk -rx "module reload http"
asterisk -rx "http show status"      # deve mostrar o HTTPS na porta 8089
asterisk -rx "module show like websocket"
asterisk -rx "module show like srtp"   # res_srtp tem de estar carregado
```

## 3. Extensões próprias para a web

Uma extensão preparada para WebRTC exige áudio cifrado, e por isso deixa de funcionar com a app Android, e vice-versa. **Cria extensões separadas para a web**, por exemplo 201, 202… As extensões web e as Android ligam-se umas às outras normalmente, porque o Asterisk faz a ponte entre elas.

1. Em *PBX → Extensions*, cria as extensões 201, 202… do tipo **SIP**.
2. Acrescenta as opções WebRTC em `/etc/asterisk/sip_custom_post.conf`, um bloco por extensão:

```ini
[201](+)
transport=wss
avpf=yes
force_avp=yes
icesupport=yes
encryption=yes
dtlsenable=yes
dtlsverify=no
dtlscertfile=/etc/asterisk/keys/fullchain.pem
dtlsprivatekey=/etc/asterisk/keys/privkey.pem
dtlssetup=actpass
rtcp_mux=yes
nat=force_rport,comedia
allow=!all,ulaw,alaw,opus
```

```bash
asterisk -rx "sip reload"
asterisk -rx "sip show peer 201"   # confirma: Transport, Encryption, AVPF
```

## 4. Openfire: XMPP sobre WebSocket

1. Em *Server → Server Settings → HTTP Binding*:
   - ativa o serviço, nas portas 7070 (normal) e **7443** (segura);
   - em *CORS*, autoriza o endereço onde a webapp está alojada (ou `*` em testes).

   O WebSocket fica em `wss://pbx.exemplo.pt:7443/ws/`.
2. Em *Server → TLS/SSL Certificates*, importa o certificado Let's Encrypt (`fullchain.pem` + `privkey.pem`) e reinicia o Openfire.
3. Os utilizadores do chat têm de ter o **mesmo nome das extensões web** (201, 202…). O grupo partilhado que criaste para a lista de contactos também serve para eles.

## 5. Firewall

| Porta | Protocolo | Uso |
|-------|-----------|-----|
| 443 | TCP | Página da webapp (HTTPS) |
| 8089 | TCP | SIP sobre WebSocket seguro |
| 7443 | TCP | XMPP sobre WebSocket seguro |
| 10000-20000 | UDP | Áudio (SRTP) |

## 6. Alojar a webapp

**Opção A, no próprio Issabel (recomendada).** A webapp fica no mesmo domínio e com o mesmo certificado:

```bash
cd /var/www/html
curl -L https://github.com/jmvduarte/issaphone/archive/refs/heads/main.tar.gz \
  | tar xz --strip-components=1 issaphone-main/web
mv web issafone
```

Abre `https://pbx.exemplo.pt/issafone/`. Para atualizar, repete os comandos depois de apagar a pasta `issafone`.

**Opção B, GitHub Pages.** O workflow `.github/workflows/web.yml` publica a pasta `web/` em `https://jmvduarte.github.io/issaphone/` a cada alteração. O servidor Issabel continua a precisar do certificado válido (pontos 1 a 4).

## 7. Entrar

No ecrã de entrada preenche:
- **domínio do Issabel**;
- **extensão web** (201…) e a palavra-passe dela;
- **palavra-passe do chat**, se for diferente da da extensão.

Os endereços WebSocket são preenchidos automaticamente (`wss://domínio:8089/ws` e `wss://domínio:7443/ws/`). Se usares outras portas, altera-os em *Opções avançadas*. O campo STUN só é preciso para usar a webapp fora da rede local.

## Problemas comuns

| Sintoma | Causa provável |
|---------|----------------|
| "Telefone: sem ligação" | Porta 8089 fechada, certificado inválido ou `tlsenable` desligado |
| Regista, mas a chamada falha com "Áudio incompatível" | A extensão não tem as opções WebRTC (ponto 3) |
| A chamada liga mas não há som | Porta UDP 10000-20000 fechada ou `res_srtp` não carregado |
| "Chat: sem ligação" | Porta 7443 fechada, HTTP Binding desligado ou certificado do Openfire inválido |
| No iPhone não liga nada | Certificado autoassinado: o iOS exige um válido |

## Ficheiros

```
web/
├── index.html            Estrutura da página
├── css/app.css           Aspeto (claro/escuro, telemóvel e PC)
├── js/app.js             Lógica: SIP (JsSIP), XMPP (Strophe), interface
├── sw.js                 Service worker (abre sem rede, notificações)
├── manifest.webmanifest  Instalação como app (PWA)
├── icons/                Ícones (gerados por design/gerar-icones.py)
└── vendor/               JsSIP 3.13.9 e Strophe.js 3.1.1
```
