package pt.ipca.issafone

import android.app.Application
import androidx.core.content.ContextCompat
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import pt.ipca.issafone.servico.IssaFoneService

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val issa = app as IssaFoneApp
    val sip get() = issa.sip
    val xmpp get() = issa.xmpp

    private val _config = MutableStateFlow(ConfigStore.carregar(app))
    val config: StateFlow<Config> = _config

    private val _ligado = MutableStateFlow(ConfigStore.sessaoAtiva(app) && _config.value.valida)
    val ligado: StateFlow<Boolean> = _ligado

    init {
        // Reabrir a app com sessão ativa: garante que o serviço está a correr
        if (_ligado.value) iniciarServico()
    }

    fun entrar(c: Config) {
        ConfigStore.guardar(getApplication(), c)
        ConfigStore.definirSessaoAtiva(getApplication(), true)
        _config.value = c
        issa.ligar(c, forcar = true)
        iniciarServico()
        _ligado.value = true
    }

    fun sair() {
        ConfigStore.definirSessaoAtiva(getApplication(), false)
        getApplication<Application>().stopService(Intent(getApplication(), IssaFoneService::class.java))
        issa.desligar()
        _ligado.value = false
    }

    private fun iniciarServico() {
        val ctx = getApplication<Application>()
        ContextCompat.startForegroundService(ctx, Intent(ctx, IssaFoneService::class.java))
    }

    /** Conversa a abrir quando se toca numa notificação de mensagem. */
    private val _abrirConversa = MutableStateFlow<String?>(null)
    val abrirConversa: StateFlow<String?> = _abrirConversa
    fun pedirConversa(jid: String?) { _abrirConversa.value = jid }

    fun enviar(destino: String, texto: String) = viewModelScope.launch { xmpp.enviar(destino, texto) }
    fun adicionarContacto(u: String) = viewModelScope.launch { xmpp.adicionarContacto(u) }

    // Atenção: não desligar nada em onCleared() — a sessão pertence ao serviço,
    // não ao ecrã. É isso que permite receber chamadas com a app fechada.
}
