#!/bin/bash
# =============================================================================
#  IssaFone — configuração do servidor Issabel 4 (CentOS 7)
#
#  Faz, por esta ordem:
#   1. Verificações (DNS, Asterisk, Openfire)
#   2. Certificado Let's Encrypt para o domínio (+ renovação automática)
#   3. HTTPS no Apache com esse certificado
#   4. SIP sobre WebSocket seguro no Asterisk (porta 8089) — para a webapp
#   5. Opções WebRTC nas extensões da webapp
#   6. Openfire: certificado + contas de chat = extensões do Issabel
#      (autentica na base de dados do Issabel: mesma palavra-passe)
#   7. Firewall
#   8. Instala a webapp em https://DOMINIO/issafone/
#   9. Verificação final
#
#  Uso (como root):
#     curl -fsSL https://raw.githubusercontent.com/jmvduarte/issaphone/main/servidor/configurar-issabel.sh -o configurar-issabel.sh
#     bash configurar-issabel.sh
#
#  Pode ser corrido várias vezes: o que já estiver feito é mantido/atualizado.
# =============================================================================

DOMINIO="${DOMINIO:-rsi-a36357.ddns.net}"
EXT_WEB="${EXT_WEB:-201-210}"          # extensões da webapp (intervalo ou lista: 201,202)
REDE_LOCAL="${REDE_LOCAL:-192.168.0.0/16}"
EMAIL="${EMAIL:-}"                     # opcional: avisos de expiração do Let's Encrypt
REPO="${REPO:-jmvduarte/issaphone}"

set -uo pipefail
export LC_ALL=C

VERDE=$'\e[32m'; VERMELHO=$'\e[31m'; AMARELO=$'\e[33m'; NEGRITO=$'\e[1m'; FIM=$'\e[0m'
FALHAS=(); AVISOS=()
passo() { echo; echo "${NEGRITO}== $* ==${FIM}"; }
ok()    { echo "  ${VERDE}✔${FIM} $*"; }
aviso() { echo "  ${AMARELO}!${FIM} $*"; AVISOS+=("$*"); }
falha() { echo "  ${VERMELHO}✘${FIM} $*"; FALHAS+=("$*"); }
copia() { [ -f "$1" ] && [ ! -f "$1.antes-issafone" ] && cp -a "$1" "$1.antes-issafone"; }

[ "$(id -u)" = 0 ] || { echo "Corre como root."; exit 1; }

LE="/etc/letsencrypt/live/$DOMINIO"
OF_CONF=/opt/openfire/conf/openfire.xml
KEYTOOL=/usr/lib/jvm/jre-11/bin/keytool

# Expande "201-210" ou "201,202,205" numa lista
expandir() {
  local r=() p
  IFS=',' read -ra partes <<< "$1"
  for p in "${partes[@]}"; do
    if [[ $p =~ ^([0-9]+)-([0-9]+)$ ]]; then r+=($(seq "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}")); else r+=("$p"); fi
  done
  echo "${r[@]}"
}

# -----------------------------------------------------------------------------
passo "1. Verificações"
grep -q "release 7" /etc/redhat-release && ok "$(cat /etc/redhat-release)" || aviso "Sistema diferente do esperado (CentOS 7)"

if asterisk -rx "core show version" >/dev/null 2>&1; then ok "$(asterisk -rx 'core show version' | cut -d' ' -f1-2) a correr"
else falha "O Asterisk não está a correr"; fi

if [ -f "$OF_CONF" ] && grep -q "<setup>true</setup>" "$OF_CONF"; then ok "Openfire instalado e configurado"
else falha "O Openfire não está instalado ou o assistente (porta 9090) não foi concluído"; echo "  Conclui o assistente e volta a correr o script."; exit 1; fi

if grep -q "$DOMINIO" "$OF_CONF"; then ok "Openfire configurado para $DOMINIO"
else aviso "Não consegui confirmar que o XMPP Domain do Openfire é $DOMINIO (vê em Servidor → Informação do servidor)"; fi

IP_DNS=$(getent ahostsv4 "$DOMINIO" | awk 'NR==1{print $1}')
IP_PUB=$(curl -fs4 --max-time 8 https://api.ipify.org || true)
if [ -z "$IP_DNS" ]; then falha "O domínio $DOMINIO não resolve (DNS do No-IP?)"
elif [ -n "$IP_PUB" ] && [ "$IP_DNS" != "$IP_PUB" ]; then
  aviso "$DOMINIO aponta para $IP_DNS, mas o IP público é $IP_PUB — atualiza o No-IP"
else ok "$DOMINIO → $IP_DNS (IP público)"; fi

# -----------------------------------------------------------------------------
passo "2. Pacotes"
yum install -y -q epel-release >/dev/null 2>&1
if yum install -y -q certbot crudini mod_ssl openssl curl >/dev/null 2>&1; then ok "certbot, crudini, mod_ssl, openssl"
else falha "Não foi possível instalar pacotes (yum)"; fi

# -----------------------------------------------------------------------------
passo "3. Certificado Let's Encrypt"
if [ -f "$LE/fullchain.pem" ]; then
  ok "Certificado já existe (expira: $(openssl x509 -enddate -noout -in "$LE/cert.pem" | cut -d= -f2))"
else
  mkdir -p /var/www/html/.well-known/acme-challenge
  if [ -n "$EMAIL" ]; then CONTA=(-m "$EMAIL"); else CONTA=(--register-unsafely-without-email); fi
  if certbot certonly --webroot -w /var/www/html -d "$DOMINIO" --agree-tos -n "${CONTA[@]}"; then
    ok "Certificado emitido para $DOMINIO"
  else
    falha "Let's Encrypt falhou: confirma que a porta 80/TCP do router está redirecionada para este servidor"
  fi
fi

# Hook de renovação: copia o certificado para o Asterisk e o Openfire e reinicia-os
mkdir -p /etc/letsencrypt/renewal-hooks/deploy
cat > /etc/letsencrypt/renewal-hooks/deploy/issafone.sh <<EOF
#!/bin/bash
# Gerado pelo configurar-issabel.sh (IssaFone)
D="$DOMINIO"; LE="/etc/letsencrypt/live/\$D"
mkdir -p /etc/asterisk/keys
cp -f "\$LE/fullchain.pem" "\$LE/privkey.pem" /etc/asterisk/keys/
chown asterisk:asterisk /etc/asterisk/keys/*.pem; chmod 640 /etc/asterisk/keys/privkey.pem

KS=/opt/openfire/resources/security/keystore
openssl pkcs12 -export -in "\$LE/fullchain.pem" -inkey "\$LE/privkey.pem" -name "\$D" \\
  -out /tmp/issafone-of.p12 -passout pass:changeit
for a in \$($KEYTOOL -list -keystore "\$KS" -storepass changeit 2>/dev/null | awk -F, '/PrivateKeyEntry/{print \$1}'); do
  $KEYTOOL -delete -alias "\$a" -keystore "\$KS" -storepass changeit
done
$KEYTOOL -importkeystore -noprompt -srckeystore /tmp/issafone-of.p12 -srcstoretype PKCS12 -srcstorepass changeit \\
  -destkeystore "\$KS" -deststorepass changeit -destkeypass changeit -alias "\$D" >/dev/null
rm -f /tmp/issafone-of.p12
chown daemon:daemon "\$KS" 2>/dev/null

systemctl reload httpd 2>/dev/null
asterisk -rx "sip reload" >/dev/null 2>&1
# O certificado HTTPS do Asterisk só é relido ao reiniciar (quando não houver chamadas)
asterisk -rx "core restart when convenient" >/dev/null 2>&1
service openfire restart >/dev/null 2>&1
EOF
chmod +x /etc/letsencrypt/renewal-hooks/deploy/issafone.sh

if [ -f "$LE/fullchain.pem" ]; then
  /etc/letsencrypt/renewal-hooks/deploy/issafone.sh && ok "Certificado copiado para o Asterisk e o Openfire"
  if systemctl enable --now certbot-renew.timer >/dev/null 2>&1; then ok "Renovação automática ativa (certbot-renew.timer)"
  else echo "17 3 * * * root certbot renew -q" > /etc/cron.d/issafone-certbot; ok "Renovação automática ativa (cron)"; fi
fi

# -----------------------------------------------------------------------------
passo "4. HTTPS no Apache"
SSL=/etc/httpd/conf.d/ssl.conf
if [ -f "$LE/fullchain.pem" ] && [ -f "$SSL" ]; then
  copia "$SSL"
  sed -i -E "s|^\s*#?\s*SSLCertificateFile .*|SSLCertificateFile $LE/cert.pem|" "$SSL"
  sed -i -E "s|^\s*#?\s*SSLCertificateKeyFile .*|SSLCertificateKeyFile $LE/privkey.pem|" "$SSL"
  if grep -qE "^\s*#?\s*SSLCertificateChainFile" "$SSL"; then
    sed -i -E "s|^\s*#?\s*SSLCertificateChainFile .*|SSLCertificateChainFile $LE/chain.pem|" "$SSL"
  else
    sed -i "/^SSLCertificateKeyFile/a SSLCertificateChainFile $LE/chain.pem" "$SSL"
  fi
  if apachectl configtest >/dev/null 2>&1; then systemctl reload httpd && ok "Apache com o certificado Let's Encrypt"
  else cp -a "$SSL.antes-issafone" "$SSL"; falha "Configuração do Apache inválida — reposta a original"; fi
else
  aviso "Apache não alterado (sem certificado ou sem $SSL)"
fi

# -----------------------------------------------------------------------------
passo "5. Asterisk: SIP sobre WebSocket seguro (8089)"
# O Issabel gera o http_additional.conf (com o certificado autoassinado) e
# inclui depois o http_custom.conf, reservado para personalizações: é aí que
# fica o certificado Let's Encrypt, para não ser reescrito pelo Issabel.
HTTP=/etc/asterisk/http.conf
HTTPC=/etc/asterisk/http_custom.conf
touch "$HTTPC"; copia "$HTTPC"
sed -i '/^; >>> IssaFone/,/^; <<< IssaFone/d' "$HTTPC"
cat >> "$HTTPC" <<'EOF'
; >>> IssaFone — certificado Let's Encrypt no WSS (8089) (gerado pelo configurar-issabel.sh)
enabled=yes
tlsenable=yes
tlsbindaddr=0.0.0.0:8089
tlscertfile=/etc/asterisk/keys/fullchain.pem
tlsprivatekey=/etc/asterisk/keys/privkey.pem
; <<< IssaFone
EOF
grep -q "http_custom.conf" "$HTTP" 2>/dev/null || { copia "$HTTP"; echo "#include http_custom.conf" >> "$HTTP"; }

cert_8089() { echo | timeout 8 openssl s_client -connect 127.0.0.1:8089 -servername "$DOMINIO" 2>/dev/null | openssl x509 -noout -issuer 2>/dev/null; }
asterisk -rx "module reload http" >/dev/null; sleep 1
if [ -f "$LE/fullchain.pem" ] && ! cert_8089 | grep -q "Let's Encrypt"; then
  # O reload não volta a ler o certificado: só um reinício do Asterisk o faz
  if [ "$(asterisk -rx 'core show channels count' | awk '/active call/{print $1}')" = "0" ]; then
    echo "  (a reiniciar o Asterisk para carregar o certificado…)"
    asterisk -rx "core restart now" >/dev/null 2>&1
    for i in $(seq 1 30); do sleep 2; asterisk -rx "core show version" >/dev/null 2>&1 && break; done
    sleep 3
  else
    aviso "Há chamadas a decorrer: o Asterisk reinicia quando terminarem (core restart when convenient)"
    asterisk -rx "core restart when convenient" >/dev/null 2>&1
  fi
fi
if asterisk -rx "http show status" | grep -q "8089"; then ok "HTTPS/WSS do Asterisk na porta 8089"
else falha "O Asterisk não abriu a porta 8089 (vê: asterisk -rx 'http show status')"; fi
for m in res_http_websocket res_srtp res_crypto; do
  asterisk -rx "module show like $m" | grep -q "$m" && ok "Módulo $m carregado" || falha "Módulo $m não está carregado"
done

# -----------------------------------------------------------------------------
passo "5b. Videochamadas (VP8 e H.264)"
# O Asterisk não converte vídeo: só o reencaminha. VP8 e H.264 são suportados
# pela Linphone (apps) e pelos browsers (webapp), por isso todos falam o mesmo.
GEN=/etc/asterisk/sip_general_custom.conf
touch "$GEN"; copia "$GEN"
sed -i '/^; >>> IssaFone/,/^; <<< IssaFone/d' "$GEN"
cat >> "$GEN" <<'EOF'
; >>> IssaFone — videochamadas (gerado pelo configurar-issabel.sh)
videosupport=yes
allow=vp8
allow=h264
; <<< IssaFone
EOF
grep -q "sip_general_custom.conf" /etc/asterisk/sip.conf || aviso "O sip.conf não inclui o sip_general_custom.conf — o vídeo pode ficar desligado"
asterisk -rx "sip reload" >/dev/null; sleep 1
if asterisk -rx "sip show settings" | grep -iE "Videosupport:\s*Yes" >/dev/null; then ok "Vídeo ativo no Asterisk (VP8, H.264)"
else falha "O Asterisk não ativou o vídeo (vê: asterisk -rx 'sip show settings' | grep -i video)"; fi

# -----------------------------------------------------------------------------
passo "6. Extensões da webapp com WebRTC ($EXT_WEB)"
# As opções WebRTC só podem ser acrescentadas a extensões que existem: um bloco
# "[205](+)" para uma extensão inexistente faz falhar o sip.conf inteiro.
# Por isso, um pequeno serviço vigia o sip_additional.conf (que o Issabel
# reescreve ao carregar em "Aplicar") e aplica as opções às extensões web
# que existirem — criar uma extensão nova não obriga a correr este script.
POST=/etc/asterisk/sip_custom_post.conf
touch "$POST"; copia "$POST"

cat > /usr/local/sbin/issafone-webrtc <<EOF
#!/bin/bash
# IssaFone: aplica as opções WebRTC às extensões web ($EXT_WEB) que existem no Issabel.
# Gerado pelo configurar-issabel.sh. Corre sozinho quando as extensões mudam.
EXT_WEB="$EXT_WEB"
POST=/etc/asterisk/sip_custom_post.conf
ADD=/etc/asterisk/sip_additional.conf
EOF
cat >> /usr/local/sbin/issafone-webrtc <<'EOF'
expandir() {
  local r=() p
  IFS=',' read -ra partes <<< "$1"
  for p in "${partes[@]}"; do
    if [[ $p =~ ^([0-9]+)-([0-9]+)$ ]]; then r+=($(seq "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}")); else r+=("$p"); fi
  done
  echo "${r[@]}"
}
novo=$(
  echo "; >>> IssaFone — opções WebRTC (gerado automaticamente; não editar à mão)"
  for e in $(expandir "$EXT_WEB"); do
    grep -q "^\[$e\]" "$ADD" 2>/dev/null || continue
    printf '%s\n' "[$e](+)" transport=wss,ws,udp avpf=yes force_avp=yes icesupport=yes encryption=yes \
      dtlsenable=yes dtlsverify=no dtlscertfile=/etc/asterisk/keys/fullchain.pem \
      dtlsprivatekey=/etc/asterisk/keys/privkey.pem dtlssetup=actpass rtcp_mux=yes \
      nat=force_rport,comedia disallow=all allow=ulaw,alaw,opus,vp8,h264 ""
  done
  echo "; <<< IssaFone"
)
atual=$(sed -n '/^; >>> IssaFone/,/^; <<< IssaFone/p' "$POST" 2>/dev/null)
[ "$novo" = "$atual" ] && exit 0
touch "$POST"
sed -i '/^; >>> IssaFone/,/^; <<< IssaFone/d' "$POST"
printf '%s\n' "$novo" >> "$POST"
asterisk -rx "sip reload" >/dev/null 2>&1
logger -t issafone "Opções WebRTC atualizadas"
EOF
chmod +x /usr/local/sbin/issafone-webrtc

cat > /etc/systemd/system/issafone-webrtc.service <<'EOF'
[Unit]
Description=IssaFone: opcoes WebRTC nas extensoes web

[Service]
Type=oneshot
ExecStartPre=/bin/sleep 3
ExecStart=/usr/local/sbin/issafone-webrtc
EOF
cat > /etc/systemd/system/issafone-webrtc.path <<'EOF'
[Unit]
Description=IssaFone: vigiar alteracoes as extensoes do Issabel

[Path]
PathChanged=/etc/asterisk/sip_additional.conf

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now issafone-webrtc.path >/dev/null 2>&1 \
  && ok "Serviço automático ativo: extensões web novas ficam com WebRTC ao carregar em \"Aplicar\" no Issabel" \
  || falha "Não foi possível ativar o serviço issafone-webrtc.path"

grep -q "sip_custom_post.conf" /etc/asterisk/sip.conf || aviso "O sip.conf não inclui o sip_custom_post.conf — as opções WebRTC podem não ser lidas"
# Limpa a versão antiga deste bloco (se existir) e aplica já
sed -i '/^; >>> IssaFone/,/^; <<< IssaFone/d' "$POST"
/usr/local/sbin/issafone-webrtc
sleep 1
if asterisk -rx "sip show peers" >/dev/null 2>&1 && asterisk -rx "sip show peers" | grep -q "sip peers"; then ok "O sip.conf carregou sem erros"
else falha "Problema a carregar o sip.conf — vê: asterisk -rx 'sip show peers'"; fi
for e in $(expandir "$EXT_WEB"); do
  if ! grep -q "^\[$e\]" /etc/asterisk/sip_additional.conf 2>/dev/null; then
    echo "  · $e ainda não existe (quando a criares no Issabel, fica preparada automaticamente)"
  elif asterisk -rx "sip show peer $e" | grep -qiE "Allowed.Tr[a-z]*p.*WSS"; then ok "Extensão $e preparada para WebRTC"
  else falha "A extensão $e não ficou com transporte WSS"; fi
done

# -----------------------------------------------------------------------------
passo "7. Openfire: contas de chat = extensões do Issabel"
MYSQL_ROOT=$(awk -F= '/^mysqlrootpwd=/{print $2}' /etc/issabel.conf 2>/dev/null)
OF_DB_PASS_FILE=/root/.issafone-openfire-db
[ -f "$OF_DB_PASS_FILE" ] || openssl rand -hex 16 > "$OF_DB_PASS_FILE"; chmod 600 "$OF_DB_PASS_FILE"
OF_DB_PASS=$(cat "$OF_DB_PASS_FILE")

if [ -z "$MYSQL_ROOT" ]; then
  falha "Não encontrei a palavra-passe do MySQL em /etc/issabel.conf"
else
  mysql -uroot -p"$MYSQL_ROOT" -e "GRANT SELECT ON asterisk.sip TO 'openfire'@'localhost' IDENTIFIED BY '$OF_DB_PASS';
    GRANT SELECT ON asterisk.sip TO 'openfire'@'127.0.0.1' IDENTIFIED BY '$OF_DB_PASS';
    GRANT SELECT ON asterisk.users TO 'openfire'@'localhost';
    GRANT SELECT ON asterisk.users TO 'openfire'@'127.0.0.1'; FLUSH PRIVILEGES;" 2>/dev/null \
    && ok "Utilizador 'openfire' (só leitura) na base de dados do Issabel" || falha "Não foi possível criar o utilizador MySQL"
  N=$(mysql -uopenfire -p"$OF_DB_PASS" -h127.0.0.1 -N -e "SELECT COUNT(*) FROM asterisk.sip WHERE keyword='secret'" 2>/dev/null)
  [ -n "$N" ] && ok "$N extensões SIP encontradas (serão contas de chat)" || falha "Não consegui ler as extensões (asterisk.sip)"

  copia "$OF_CONF"
  python - "$OF_CONF" "$OF_DB_PASS" <<'PY'
# -*- coding: utf-8 -*-
import re, sys
caminho, senha = sys.argv[1], sys.argv[2]
xml = open(caminho).read()
xml = re.sub(r'\s*<!-- >>> IssaFone.*?<!-- <<< IssaFone -->', '', xml, flags=re.S)
bloco = """
  <!-- >>> IssaFone: utilizadores e palavras-passe vêm das extensões do Issabel -->
  <jdbcProvider>
    <driver>com.mysql.cj.jdbc.Driver</driver>
    <connectionString>jdbc:mysql://127.0.0.1:3306/asterisk?user=openfire&amp;password=%s&amp;useSSL=false&amp;serverTimezone=UTC&amp;characterEncoding=UTF-8</connectionString>
  </jdbcProvider>
  <provider>
    <auth><className>org.jivesoftware.openfire.auth.HybridAuthProvider</className></auth>
    <user><className>org.jivesoftware.openfire.user.HybridUserProvider</className></user>
    <group><className>org.jivesoftware.openfire.group.JDBCGroupProvider</className></group>
  </provider>
  <hybridAuthProvider>
    <primaryProvider><className>org.jivesoftware.openfire.auth.JDBCAuthProvider</className></primaryProvider>
    <secondaryProvider><className>org.jivesoftware.openfire.auth.DefaultAuthProvider</className></secondaryProvider>
  </hybridAuthProvider>
  <hybridUserProvider>
    <primaryProvider><className>org.jivesoftware.openfire.user.JDBCUserProvider</className></primaryProvider>
    <secondaryProvider><className>org.jivesoftware.openfire.user.DefaultUserProvider</className></secondaryProvider>
  </hybridUserProvider>
  <jdbcAuthProvider>
    <passwordSQL>SELECT data FROM sip WHERE id=? AND keyword='secret'</passwordSQL>
    <passwordType>plain</passwordType>
  </jdbcAuthProvider>
  <jdbcUserProvider>
    <loadUserSQL>SELECT COALESCE(NULLIF(name, ''), extension), NULL FROM users WHERE extension=?</loadUserSQL>
    <userCountSQL>SELECT COUNT(*) FROM sip WHERE keyword='secret'</userCountSQL>
    <allUsersSQL>SELECT id FROM sip WHERE keyword='secret' ORDER BY id</allUsersSQL>
    <searchSQL>SELECT id FROM sip WHERE keyword='secret' AND</searchSQL>
    <usernameField>id</usernameField>
    <nameField>data</nameField>
    <emailField>data</emailField>
  </jdbcUserProvider>
  <jdbcGroupProvider>
    <groupCountSQL>SELECT 1</groupCountSQL>
    <allGroupsSQL>SELECT 'IssaFone'</allGroupsSQL>
    <userGroupsSQL>SELECT 'IssaFone' FROM sip WHERE id=? AND keyword='secret'</userGroupsSQL>
    <descriptionSQL>SELECT 'Todas as extensoes do Issabel' FROM DUAL WHERE ?='IssaFone'</descriptionSQL>
    <loadMembersSQL>SELECT id FROM sip WHERE keyword='secret' AND ?='IssaFone' ORDER BY id</loadMembersSQL>
    <loadAdminsSQL>SELECT id FROM sip WHERE 1=0 AND ?='IssaFone'</loadAdminsSQL>
  </jdbcGroupProvider>
  <!-- <<< IssaFone -->
""" % senha
xml = xml.replace('</jive>', bloco + '</jive>', 1)
open(caminho, 'w').write(xml)
PY
  chown daemon:daemon "$OF_CONF" 2>/dev/null
  ok "Openfire configurado para usar as extensões do Issabel"
fi

LOG_OF=/opt/openfire/logs/openfire.log
LINHAS_ANTES=$(wc -l < "$LOG_OF" 2>/dev/null || echo 0)
service openfire restart >/dev/null 2>&1
echo "  (a aguardar o arranque do Openfire…)"
for i in $(seq 1 40); do ss -tln | grep -q ":5222 " && break; sleep 2; done
ss -tln | grep -q ":5222 " && ok "Openfire a correr (5222)" || falha "O Openfire não arrancou — vê /opt/openfire/logs/openfire.log"
sleep 3
# O assistente do Openfire guarda o fornecedor de autenticação na base de dados,
# que tem prioridade sobre o openfire.xml: nesse caso é preciso mudá-lo no painel.
# Só as linhas escritas depois deste reinício (os avisos antigos não contam)
# O nome das contas vem do "Display Name" das extensões; se a consulta antiga já
# estiver na base de dados do Openfire, também tem de ser mudada no painel.
if tail -n +"$((LINHAS_ANTES + 1))" "$LOG_OF" | grep -q "XML Property 'jdbcUserProvider\.loadUserSQL' differs"; then
  falha "Para os contactos aparecerem com o nome da extensão, no painel do Openfire (Propriedades do Sistema) muda:"
  echo "      jdbcUserProvider.loadUserSQL = SELECT COALESCE(NULLIF(name, ''), extension), NULL FROM users WHERE extension=?"
  echo "      e depois: service openfire restart"
fi
if tail -n +"$((LINHAS_ANTES + 1))" "$LOG_OF" | grep -q "XML Property 'provider\.\(auth\|user\|group\)\.className' differs"; then
  falha "O Openfire ainda usa os fornecedores da base de dados. No painel (porta 9090), Servidor → Gestão do Servidor → Propriedades do Sistema, muda:"
  echo "      provider.auth.className  = org.jivesoftware.openfire.auth.HybridAuthProvider"
  echo "      provider.user.className  = org.jivesoftware.openfire.user.HybridUserProvider   (se existir)"
  echo "      provider.group.className = org.jivesoftware.openfire.group.JDBCGroupProvider    (se existir)"
  echo "      e depois: service openfire restart"
fi
if tail -n +"$((LINHAS_ANTES + 1))" "$LOG_OF" | grep -iE "SQLException|ClassNotFound|Communications link" | tail -3 | grep -q .; then
  falha "Erros de base de dados no Openfire:"; tail -n +"$((LINHAS_ANTES + 1))" "$LOG_OF" | grep -iE "SQLException|ClassNotFound|Communications link" | tail -3 | sed 's/^/      /'
fi

# -----------------------------------------------------------------------------
passo "8. Firewall"
abrir() { iptables -C INPUT "$@" -j ACCEPT 2>/dev/null || iptables -I INPUT "$@" -j ACCEPT; }
abrir -p tcp --dport 80
abrir -p tcp --dport 443
abrir -p tcp --dport 8089
abrir -p tcp --dport 5222
abrir -p tcp --dport 7443
abrir -p udp --dport 5060
abrir -p udp --dport 10000:20000
abrir -p tcp --dport 9090 -s "$REDE_LOCAL"
service iptables save >/dev/null 2>&1 && ok "Regras guardadas (80, 443, 8089, 5222, 7443/TCP; 5060, 10000-20000/UDP; 9090 só na rede local)" \
  || aviso "Regras aplicadas mas não guardadas (service iptables save falhou)"
aviso "Se usares o Firewall na página do Issabel, acrescenta lá as mesmas portas (ao aplicá-lo, pode substituir estas regras)"

# -----------------------------------------------------------------------------
passo "9. Webapp em https://$DOMINIO/issafone/"
TMP=$(mktemp -d)
if curl -fsSL "https://github.com/$REPO/archive/refs/heads/main.tar.gz" | tar xz -C "$TMP"; then
  rm -rf /var/www/html/issafone
  cp -a "$TMP"/*/web /var/www/html/issafone
  chown -R asterisk:asterisk /var/www/html/issafone 2>/dev/null
  ok "Webapp instalada em /var/www/html/issafone"
else
  falha "Não foi possível descarregar a webapp do GitHub"
fi
rm -rf "$TMP"

# -----------------------------------------------------------------------------
passo "10. Verificação final"
verificar_tls() { # porta, nome
  local s
  s=$(echo | timeout 8 openssl s_client -connect "127.0.0.1:$1" -servername "$DOMINIO" 2>/dev/null | openssl x509 -noout -issuer -subject 2>/dev/null)
  if echo "$s" | grep -q "Let's Encrypt" && echo "$s" | grep -q "$DOMINIO"; then ok "$2 ($1): certificado Let's Encrypt válido"
  elif [ -n "$s" ]; then falha "$2 ($1): certificado errado → $(echo "$s" | tr '\n' ' ')"
  else falha "$2 ($1): sem resposta TLS"; fi
}
verificar_tls 443  "Apache / webapp"
verificar_tls 8089 "Asterisk WSS"
verificar_tls 7443 "Openfire WebSocket"
CODE=$(curl -s -o /dev/null -w '%{http_code}' --resolve "$DOMINIO:443:127.0.0.1" "https://$DOMINIO/issafone/")
[ "$CODE" = 200 ] && ok "https://$DOMINIO/issafone/ responde" || falha "A webapp respondeu HTTP $CODE"

echo
echo "${NEGRITO}== Resumo ==${FIM}"
if [ ${#FALHAS[@]} -eq 0 ]; then echo "${VERDE}Tudo configurado.${FIM}"
else echo "${VERMELHO}${#FALHAS[@]} problema(s):${FIM}"; printf '  - %s\n' "${FALHAS[@]}"; fi
[ ${#AVISOS[@]} -gt 0 ] && { echo "${AMARELO}Avisos:${FIM}"; printf '  - %s\n' "${AVISOS[@]}"; }
cat <<EOF

No router, redireciona para $(hostname -I | awk '{print $1}'):
  TCP 80, 443, 5222, 7443, 8089   e   UDP 5060, 10000-20000

Webapp:        https://$DOMINIO/issafone/   (extensões $EXT_WEB)
Apps:          entrar com o número da extensão e a respetiva palavra-passe (secret)
Chat:          cada extensão do Issabel já é uma conta de chat, com a mesma palavra-passe.
               Lista de contactos automática: no Openfire, Utilizadores/Grupos → Grupos →
               IssaFone → ativar "Partilha da lista de contactos".
Cópias dos ficheiros originais: *.antes-issafone
EOF
