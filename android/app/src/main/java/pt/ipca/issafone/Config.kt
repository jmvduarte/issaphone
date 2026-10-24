package pt.ipca.issafone

import android.content.Context

/** Dados de ligação ao Issabel. O mesmo utilizador serve SIP e XMPP. */
data class Config(
    val servidor: String = "",       // IP do Issabel
    val utilizador: String = "",     // extensão, ex.: 101
    val passwordSip: String = "",    // "secret" da extensão
    val dominioXmpp: String = "",    // XMPP Domain do Openfire
    val passwordXmpp: String = "",   // password do utilizador no Openfire
    val usarTls: Boolean = true
) {
    val valida get() = servidor.isNotBlank() && utilizador.isNotBlank() && passwordSip.isNotBlank()
}

/**
 * Guarda a configuração. Nota: SharedPreferences simples é aceitável para um
 * projeto académico; em produção usar EncryptedSharedPreferences.
 */
object ConfigStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("issafone", Context.MODE_PRIVATE)

    fun carregar(ctx: Context): Config = with(prefs(ctx)) {
        Config(
            servidor = getString("servidor", null)?.ifBlank { null } ?: BuildConfig.SERVIDOR_PADRAO,
            utilizador = getString("utilizador", "") ?: "",
            passwordSip = getString("passwordSip", "") ?: "",
            dominioXmpp = getString("dominioXmpp", "") ?: "",
            passwordXmpp = getString("passwordXmpp", "") ?: "",
            // O Openfire tem certificado Let's Encrypt: o chat vai cifrado por omissão
            usarTls = getBoolean("usarTls", true)
        )
    }

    fun guardar(ctx: Context, c: Config) {
        prefs(ctx).edit()
            .putString("servidor", c.servidor)
            .putString("utilizador", c.utilizador)
            .putString("passwordSip", c.passwordSip)
            .putString("dominioXmpp", c.dominioXmpp)
            .putString("passwordXmpp", c.passwordXmpp)
            .putBoolean("usarTls", c.usarTls)
            .apply()
    }

    /** true depois de entrar; false depois de "Sair". Decide se o serviço arranca no boot. */
    fun sessaoAtiva(ctx: Context) = prefs(ctx).getBoolean("sessaoAtiva", false)

    /** Tema escolhido: AUTO (segue o sistema), CLARO ou ESCURO. */
    fun tema(ctx: Context) = runCatching {
        pt.ipca.issafone.ui.ModoTema.valueOf(prefs(ctx).getString("tema", "AUTO")!!)
    }.getOrDefault(pt.ipca.issafone.ui.ModoTema.AUTO)
    fun definirTema(ctx: Context, m: pt.ipca.issafone.ui.ModoTema) =
        prefs(ctx).edit().putString("tema", m.name).apply()
    fun definirSessaoAtiva(ctx: Context, ativa: Boolean) =
        prefs(ctx).edit().putBoolean("sessaoAtiva", ativa).apply()
}
