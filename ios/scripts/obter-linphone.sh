#!/usr/bin/env bash
# Descarrega o SDK Linphone para iOS já compilado (download.linphone.org) e
# prepara a pasta ios/LinphoneSDK, usada pelo project.yml.
#
# Porquê: o pacote Swift oficial está no gitlab.linphone.org, que não aceita
# ligações dos servidores do GitHub Actions. O mesmo SDK é publicado em zip
# no download.linphone.org, que funciona em todo o lado.
#
# Uso (a partir da pasta ios/):  ./scripts/obter-linphone.sh
set -euo pipefail

VERSAO="${LINPHONE_VERSAO:-5.3.5}"
DESTINO="$(cd "$(dirname "$0")/.." && pwd)/LinphoneSDK"
URL="https://download.linphone.org/releases/ios/linphone-sdk-ios-${VERSAO}.zip"

if [ -f "$DESTINO/.versao" ] && [ "$(cat "$DESTINO/.versao")" = "$VERSAO" ]; then
  echo "Linphone SDK $VERSAO já está em $DESTINO"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "A descarregar $URL (~150 MB)…"
curl -fL --retry 3 -o "$TMP/sdk.zip" "$URL"
unzip -q "$TMP/sdk.zip" -d "$TMP/sdk"
ORIGEM="$TMP/sdk/linphone-sdk/apple-darwin"

rm -rf "$DESTINO"
mkdir -p "$DESTINO/XCFrameworks" "$DESTINO/linphonesw"

# Bibliotecas necessárias (deixa de fora os "tester" e o plugin de exemplo)
for f in "$ORIGEM"/XCFrameworks/*.xcframework; do
  nome="$(basename "$f")"
  case "$nome" in
    *tester*|exampleplugin*) continue ;;
  esac
  cp -R "$f" "$DESTINO/XCFrameworks/"
done

# Interface Swift (compilada como o módulo "linphonesw")
cp "$ORIGEM/share/linphonesw/"*.swift "$DESTINO/linphonesw/"

echo "$VERSAO" > "$DESTINO/.versao"
echo "Linphone SDK $VERSAO pronto em $DESTINO:"
ls "$DESTINO/XCFrameworks"
