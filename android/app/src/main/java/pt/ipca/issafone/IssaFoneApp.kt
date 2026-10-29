package pt.ipca.issafone

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pt.ipca.issafone.servico.Notificacoes
import pt.ipca.issafone.sip.SipManager
import pt.ipca.issafone.xmpp.XmppManager

/**
 * O SIP e o XMPP vivem aqui (e não no ViewModel) para sobreviverem ao fecho
 * do ecrã: o serviço em primeiro plano mantém este processo vivo.
 */
class IssaFoneApp : Application() {

    lateinit var sip: SipManager
        private set
    lateinit var xmpp: XmppManager
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Evita voltar a ligar se a sessão já estiver ativa (ex.: serviço reiniciado). */
    private var configLigada: Config? = null

    override fun onCreate() {
        super.onCreate()
        guardarErros()
        // Instalações antigas por terminar (do tempo em que a app se atualizava sozinha)
        runCatching { packageManager.packageInstaller.mySessions.forEach { packageManager.packageInstaller.abandonSession(it.sessionId) } }
        sip = SipManager(this)
        xmpp = XmppManager(this)
        Notificacoes.criarCanais(this)
    }

    fun ligar(c: Config, forcar: Boolean = false) {
        if (!forcar && configLigada == c) return
        configLigada = c
        // "pbx.empresa.pt:5070" → servidor + porta SIP própria (como no Zoiper)
        val host = c.servidor.substringBefore(':')
        val porta = c.servidor.substringAfter(':', "").toIntOrNull()
        scope.launch {
            val ligacao = SipManager.Ligacao.escolher(c.transporte, host)
            sip.ligar(c.utilizador, c.passwordSip, host, ligacao, porta)
        }
        scope.launch {
            xmpp.ligar(
                utilizador = c.utilizador,
                password = c.passwordXmpp.ifBlank { c.passwordSip },
                dominio = c.dominioXmpp.ifBlank { host },
                host = host,
                usarTls = c.usarTls
            )
        }
    }

    fun desligar() {
        configLigada = null
        sip.desregistar()
        scope.launch { xmpp.desligar() }
    }

    /**
     * Se a app fechar com um erro, guarda-o e mostra-o numa notificação (para se poder
     * enviar uma captura); na próxima abertura aparece também no ecrã.
     */
    private fun guardarErros() {
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val texto = "IssaFone ${BuildConfig.VERSION_NAME} · ${java.util.Date()}\n" + e.stackTraceToString().take(6000)
                java.io.File(filesDir, ERRO).writeText(texto)
                val nm = getSystemService(android.app.NotificationManager::class.java)
                nm.createNotificationChannel(android.app.NotificationChannel("erros", "Erros", android.app.NotificationManager.IMPORTANCE_DEFAULT))
                nm.notify(99, androidx.core.app.NotificationCompat.Builder(this, "erros")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("${BuildConfig.NOME_APP} fechou com um erro")
                    .setContentText(e.toString().take(120))
                    .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(texto.take(1500)))
                    .build())
            }
            anterior?.uncaughtException(t, e)
        }
    }

    companion object { const val ERRO = "ultimo-erro.txt" }
}
