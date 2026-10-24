package pt.ipca.issafone.servico

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import pt.ipca.issafone.ConfigStore
import pt.ipca.issafone.IssaFoneApp

/**
 * Serviço em primeiro plano: mantém o processo vivo com a app fechada, para
 * que o registo SIP e a ligação XMPP continuem ativos e as chamadas toquem.
 */
class IssaFoneService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app get() = application as IssaFoneApp
    private var callbackRede: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        entrarEmPrimeiroPlano("A ligar…")
        observarEstado()
        observarChamadas()
        observarMensagens()
        observarRede()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Se o sistema matou e recriou o processo, volta a ligar com a configuração guardada
        val c = ConfigStore.carregar(this)
        if (!ConfigStore.sessaoAtiva(this) || !c.valida) {
            stopSelf()
            return START_NOT_STICKY
        }
        app.ligar(c)
        return START_STICKY
    }

    private fun entrarEmPrimeiroPlano(estado: String) {
        val tipo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notificacoes.ID_SERVICO, Notificacoes.servico(this, estado), tipo)
    }

    /** Atualiza o texto da notificação permanente com o estado SIP/XMPP. */
    private fun observarEstado() = scope.launch {
        combine(app.sip.registo, app.xmpp.estado) { sip, chat -> "Telefone: $sip · Chat: ${chat.take(40)}" }
            .collect { texto ->
                runCatching {
                    NotificationManagerCompat.from(this@IssaFoneService)
                        .notify(Notificacoes.ID_SERVICO, Notificacoes.servico(this@IssaFoneService, texto))
                }
            }
    }

    private fun observarChamadas() = scope.launch {
        app.sip.chamada.collect { c ->
            if (c.aTocar) Notificacoes.chamadaRecebida(this@IssaFoneService, c.remoto)
            else Notificacoes.cancelarChamada(this@IssaFoneService)
        }
    }

    /** Notifica mensagens novas só quando a app não está visível. */
    private fun observarMensagens() = scope.launch {
        var vistas = app.xmpp.mensagens.value.size
        app.xmpp.mensagens.collect { lista ->
            val novas = lista.drop(vistas).filter { !it.enviada }
            vistas = lista.size
            val visivel = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!visivel) novas.forEach { Notificacoes.mensagem(this@IssaFoneService, it.contacto, it.texto) }
        }
    }

    /** Ao mudar de rede (Wi-Fi ↔ dados), refaz o registo SIP de imediato. */
    private fun observarRede() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { app.sip.refrescarRegisto() }
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        callbackRede = cb
    }

    override fun onDestroy() {
        callbackRede?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        Notificacoes.cancelarChamada(this)
        scope.cancel()
        super.onDestroy()
    }
}
