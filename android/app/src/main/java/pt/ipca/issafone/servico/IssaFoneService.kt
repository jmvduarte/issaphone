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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import pt.ipca.issafone.ConfigStore
import pt.ipca.issafone.IssaFoneApp
import pt.ipca.issafone.atualizacao.Atualizador

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
        observarPerdidas()
        observarRede()
        procurarAtualizacoes()
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
            if (c.aTocar) Notificacoes.chamadaRecebida(
                this@IssaFoneService, c.nome.ifBlank { app.xmpp.nomeExt(c.remoto) }, c.ofereceVideo
            )
            else Notificacoes.cancelarChamada(this@IssaFoneService)
        }
    }

    /**
     * Notifica mensagens novas só quando a app não está visível. Não notifica as
     * próprias nem o histórico que os grupos reenviam ao entrar.
     */
    private fun observarMensagens() = scope.launch {
        var vistas = app.xmpp.mensagens.value.toSet()
        app.xmpp.mensagens.collect { lista ->
            val novas = lista.filter { it !in vistas && !it.enviada && !it.historico }
            vistas = lista.toSet()
            val visivel = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!visivel) novas.forEach {
                val texto = it.autor?.let { a -> "${app.xmpp.nomeExt(a)}: ${it.texto}" } ?: it.texto
                Notificacoes.mensagem(this@IssaFoneService, it.contacto, app.xmpp.nomeDe(it.contacto), texto)
            }
        }
    }

    /** Avisa das chamadas perdidas quando a app não está visível. */
    private fun observarPerdidas() = scope.launch {
        var conhecidas = app.sip.registoChamadas.lista.value.toSet()
        app.sip.registoChamadas.lista.collect { lista ->
            val novas = lista.filter { it !in conhecidas && it.tipo == "perdida" }
            conhecidas = lista.toSet()
            val visivel = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!visivel) novas.forEach {
                Notificacoes.chamadaPerdida(this@IssaFoneService, app.xmpp.nomeExt(it.numero).let { n -> if (n != it.numero) n else it.nome })
            }
        }
    }

    /** De hora a hora procura uma versão nova (nunca durante uma chamada). */
    private fun procurarAtualizacoes() = scope.launch {
        delay(60_000) // deixa o registo SIP estabilizar primeiro
        while (isActive) {
            val c = app.sip.chamada.value
            if (!c.ativa && !c.aTocar) Atualizador.verificarEInstalar(this@IssaFoneService)
            delay(60 * 60 * 1000L)
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
