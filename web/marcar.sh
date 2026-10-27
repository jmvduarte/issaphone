#!/bin/sh
# Aplica uma marca a uma cópia da webapp (usado ao publicar o IssaLink).
# Uso: marcar.sh PASTA NOME PEDIR_SERVIDOR
#   PEDIR_SERVIDOR = true  → o ecrã de entrada pede o servidor (webapp alojada fora do Issabel)
#                    false → usa o domínio da própria página (webapp alojada no Issabel)
D="$1"; N="$2"; P="${3:-false}"
printf "window.MARCA = { nome: '%s', pedirServidor: %s };\n" "$N" "$P" > "$D/js/marca.js"
sed -i -e "s/\"name\": \"[^\"]*\"/\"name\": \"$N\"/" -e "s/\"short_name\": \"[^\"]*\"/\"short_name\": \"$N\"/" \
       -e "s/\"description\": \"[^\"]*\"/\"description\": \"Chamadas e chat para a tua central Issabel\"/" "$D/manifest.webmanifest"
sed -i -e "s|<title>[^<]*</title>|<title>$N</title>|" -e "s|name=\"apple-mobile-web-app-title\" content=\"[^\"]*\"|name=\"apple-mobile-web-app-title\" content=\"$N\"|" "$D/index.html"
