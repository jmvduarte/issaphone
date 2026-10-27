#!/bin/bash
# =============================================================================
#  IssaFone/IssaLink — mantém a webapp do servidor (https://DOMINIO/issafone/ ou /issalink/) igual à
#  última versão do GitHub. Só descarrega quando há alterações.
#
#  Instalar (uma vez, como root) — atualiza já e fica a verificar de 15 em 15 min:
#     curl -fsSL https://raw.githubusercontent.com/jmvduarte/issaphone/main/servidor/atualizar-webapp.sh | bash -s instalar
#
#  Atualizar à mão a qualquer momento:   issafone-atualizar-web
# =============================================================================
REPO="${REPO:-jmvduarte/issaphone}"
# Marca da webapp: "issafone" (servidor do projeto) ou "issalink" (qualquer outro Issabel)
MARCA="${MARCA:-$(cat /etc/issafone/marca 2>/dev/null || echo issafone)}"
DEST="${DEST:-/var/www/html/$MARCA}"
EXE=/usr/local/sbin/issafone-atualizar-web

if [ "${1:-}" = "instalar" ]; then
  curl -fsSL "https://raw.githubusercontent.com/$REPO/main/servidor/atualizar-webapp.sh" -o "$EXE" && chmod +x "$EXE" \
    || { echo "Não foi possível descarregar o atualizador"; exit 1; }
  mkdir -p /etc/issafone && echo "$MARCA" > /etc/issafone/marca
  echo "*/15 * * * * root $EXE >/dev/null 2>&1" > /etc/cron.d/issafone-web
  echo "✔ Atualizador instalado ($EXE, verifica de 15 em 15 minutos)"
  exec "$EXE" --mostrar
fi

mostrar() { [ "${1:-}" = "--mostrar" ] && echo "$2"; }

# Última versão (commit) do ramo principal
SHA=$(curl -fsS --max-time 15 -H "Accept: application/vnd.github.sha" "https://api.github.com/repos/$REPO/commits/main") \
  || { mostrar "${1:-}" "✘ Sem resposta do GitHub"; exit 1; }
if [ -f "$DEST/.versao" ] && [ "$(cat "$DEST/.versao")" = "$SHA" ]; then
  mostrar "${1:-}" "✔ A webapp já está na versão mais recente (${SHA:0:8})"
  exit 0
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
curl -fsSL --max-time 120 "https://github.com/$REPO/archive/$SHA.tar.gz" | tar xz -C "$TMP" \
  || { mostrar "${1:-}" "✘ Não foi possível descarregar a webapp"; exit 1; }

rm -rf "$DEST.novo"
cp -a "$TMP"/*/web "$DEST.novo"
echo "$SHA" > "$DEST.novo/.versao"
[ "$MARCA" = issalink ] && sh "$DEST.novo/marcar.sh" "$DEST.novo" IssaLink false
rm -f "$DEST.novo/marcar.sh"
# Versão nova da cache do service worker: os browsers vão buscar os ficheiros novos
sed -i "s/const VERSAO = '.*';/const VERSAO = '$MARCA-${SHA:0:8}';/" "$DEST.novo/sw.js"
chown -R asterisk:asterisk "$DEST.novo" 2>/dev/null

# Troca quase instantânea (sem ficar a meio para quem está a usar)
rm -rf "$DEST.antigo"
[ -d "$DEST" ] && mv "$DEST" "$DEST.antigo"
mv "$DEST.novo" "$DEST"
rm -rf "$DEST.antigo"

logger -t issafone "Webapp atualizada para ${SHA:0:8}"
mostrar "${1:-}" "✔ Webapp atualizada para a versão ${SHA:0:8}"
