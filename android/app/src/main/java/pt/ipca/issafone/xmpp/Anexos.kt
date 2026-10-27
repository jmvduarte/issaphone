package pt.ipca.issafone.xmpp

import java.net.URLDecoder

/**
 * Anexos do chat (XEP-0363, plugin "HTTP File Upload" do Openfire): o ficheiro é enviado
 * para o servidor e a mensagem leva só o endereço. Quem recebe reconhece esse endereço
 * e mostra a imagem ou um cartão com o ficheiro.
 */
data class Anexo(val url: String, val nome: String) {
    val imagem get() = nome.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp")
    val extensao get() = nome.substringAfterLast('.', "").uppercase().take(4)
}

private val ReAnexo = Regex("""^https?://\S+/httpfileupload/\S+$""")

/** A mensagem é um anexo? (texto só com o endereço de um ficheiro enviado pelo servidor) */
fun anexoDe(texto: String): Anexo? {
    val t = texto.trim()
    if (!ReAnexo.matches(t)) return null
    val nome = runCatching { URLDecoder.decode(t.substringAfterLast('/').substringBefore('?'), "UTF-8") }
        .getOrDefault(t.substringAfterLast('/'))
    return Anexo(t, nome)
}

/** Texto curto para listas e notificações ("📷 Fotografia", "📎 relatorio.pdf"). */
fun resumoMensagem(texto: String): String = anexoDe(texto)?.let { if (it.imagem) "📷 Fotografia" else "📎 ${it.nome}" } ?: texto
