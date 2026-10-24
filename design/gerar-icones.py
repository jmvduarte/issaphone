#!/usr/bin/env python3
"""
Gera os ícones do IssaFone (Android e iOS) a partir de um único desenho SVG.

Desenho: balão de conversa (chat XMPP) com um auscultador e ondas de sinal
(chamadas SIP), sobre um degradê azul → verde-água.

Uso (na raiz do repositório):
    pip install cairosvg pillow
    python3 design/gerar-icones.py

Para mudar as cores, altera C1/C2 e volta a correr o script.
"""
from pathlib import Path
import io

import cairosvg
from PIL import Image, ImageChops, ImageDraw

RAIZ = Path(__file__).resolve().parent.parent
C1, C2 = "#2F5BFF", "#14C8B4"  # azul -> verde-água

# Auscultador (glifo genérico 24x24)
AUSCULTADOR = (
    "M20.01 15.38c-1.23 0-2.42-.2-3.53-.56-.35-.12-.74-.03-1.01.24l-1.57 1.97"
    "c-2.83-1.35-5.48-3.9-6.89-6.83l1.95-1.66c.27-.28.35-.67.24-1.02"
    "-.37-1.11-.56-2.3-.56-3.53 0-.54-.45-.99-.99-.99H4.19C3.65 3 3 3.24 3 3.99"
    " 3 13.28 10.73 21 20.01 21c.71 0 .99-.63.99-1.18v-3.45c0-.54-.45-.99-.99-.99z"
)
ONDAS = ["M13 3.6 A8.4 8.4 0 0 1 21.4 12", "M13 7.6 A4.4 4.4 0 0 1 17.4 12"]

DEFS = f"""<defs>
  <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="{C1}"/><stop offset="1" stop-color="{C2}"/>
  </linearGradient>
  <linearGradient id="gi" gradientUnits="userSpaceOnUse" x1="3" y1="3" x2="21" y2="21">
    <stop offset="0" stop-color="{C1}"/><stop offset="1" stop-color="{C2}"/>
  </linearGradient>
  <radialGradient id="brilho" cx="0.22" cy="0.12" r="0.75">
    <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.22"/>
    <stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>
  </radialGradient>
</defs>"""

FUNDO = '<rect width="1024" height="1024" fill="url(#g)"/><rect width="1024" height="1024" fill="url(#brilho)"/>'


def balao(cor="#FFFFFF"):
    return (f'<rect x="207" y="221" width="610" height="490" rx="175" fill="{cor}"/>'
            f'<path d="M292 640 C296 728 268 792 226 822 C344 824 428 780 478 702 Z" fill="{cor}"/>')


def glifo(cor="url(#gi)"):
    k = 17.5
    tx, ty = 512 - 12 * k, 466 - 12 * k
    ondas = "".join(f'<path d="{d}"/>' for d in ONDAS)
    return (f'<g transform="translate({tx} {ty}) scale({k})">'
            f'<path d="{AUSCULTADOR}" fill="{cor}"/>'
            f'<g fill="none" stroke="{cor}" stroke-width="2.1" stroke-linecap="round">{ondas}</g></g>')


def escalar(conteudo, escala):
    d = 512 * (1 - escala)
    return f'<g transform="translate({d} {d}) scale({escala})">{conteudo}</g>'


def svg(conteudo):
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" '
            f'viewBox="0 0 1024 1024">{DEFS}{conteudo}</svg>')


def render(svg_txt, tamanho):
    png = cairosvg.svg2png(bytestring=svg_txt.encode(), output_width=tamanho, output_height=tamanho)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def guardar(img, caminho):
    caminho.parent.mkdir(parents=True, exist_ok=True)
    img.save(caminho, optimize=True)
    print("  ", caminho.relative_to(RAIZ))


def main():
    completo = svg(FUNDO + balao() + glifo())
    (RAIZ / "design" / "icone.svg").write_text(completo)

    # ---------- iOS: um único 1024x1024, sem transparência ----------
    print("iOS")
    ios = render(completo, 1024).convert("RGB")
    guardar(ios, RAIZ / "ios/IssaFone/Assets.xcassets/AppIcon.appiconset/icone-1024.png")

    # ---------- Android: ícone adaptativo (camadas de 108dp) ----------
    # O conteúdo tem de caber na zona segura (círculo de 66dp), daí a escala 0,72.
    print("Android")
    res = RAIZ / "android/app/src/main/res"
    frente = svg(escalar(balao() + glifo(), 0.72))
    fundo = svg(FUNDO)
    # Monocromático (ícones temáticos do Android 13+): balão com o glifo recortado
    so_balao = svg(escalar(balao("#FFFFFF"), 0.72))
    so_glifo = svg(escalar(glifo("#FFFFFF"), 0.72))

    densidades = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
    for nome, px in densidades.items():
        pasta = res / f"mipmap-{nome}"
        guardar(render(frente, px), pasta / "ic_launcher_foreground.png")
        guardar(render(fundo, px).convert("RGB"), pasta / "ic_launcher_background.png")
        a = render(so_balao, px).getchannel("A")
        b = render(so_glifo, px).getchannel("A")
        mono = Image.new("RGBA", (px, px), (255, 255, 255, 0))
        mono.putalpha(ImageChops.subtract(a, b))
        guardar(mono, pasta / "ic_launcher_monochrome.png")

    # Logótipo para o ecrã de entrada da app Android
    guardar(render(completo, 384), res / "drawable-nodpi" / "logo_issafone.png")

    # ---------- Webapp (PWA) ----------
    print("Webapp")
    web = RAIZ / "web/icons"
    for px in (192, 512):
        guardar(render(completo, px), web / f"icone-{px}.png")
    # "maskable": o sistema pode recortar em círculo, por isso o conteúdo fica mais pequeno
    guardar(render(svg(FUNDO + escalar(balao() + glifo(), 0.88)), 512), web / "icone-maskable-512.png")
    guardar(render(completo, 180).convert("RGB"), web / "apple-touch-icon.png")
    guardar(render(completo, 64), web / "favicon.png")

    # ---------- Pré-visualização (README) ----------
    print("Pré-visualização")
    base = render(completo, 1024)
    def mascarado(tipo):
        m = Image.new("L", (1024, 1024), 0)
        d = ImageDraw.Draw(m)
        if tipo == "ios":
            d.rounded_rectangle((0, 0, 1023, 1023), radius=230, fill=255)
        else:
            d.ellipse((0, 0, 1023, 1023), fill=255)
        im = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
        im.paste(base, (0, 0), m)
        return im
    tela = Image.new("RGBA", (1024 * 2 + 120, 1104), (0, 0, 0, 0))
    for i, t in enumerate(["ios", "android"]):
        im = mascarado(t)
        tela.paste(im, (40 + i * 1064, 40), im)
    guardar(tela.resize((tela.width // 4, tela.height // 4), Image.LANCZOS), RAIZ / "design/previa.png")


if __name__ == "__main__":
    main()
