package pt.ipca.issafone

import android.app.Application
import androidx.core.content.ContextCompat
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import pt.ipca.issafone.atualizacao.Atualizador
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
        // Ao abrir a app, procura logo uma versão nova
        viewModelScope.launch {
            val c = sip.chamada.value
            if (!c.ativa && !c.aTocar) Atualizador.verificarEInstalar(getApplication())
        }
    }

    val estadoAtualizacao = Atualizador.estado

    private val _tema = MutableStateFlow(ConfigStore.tema(app))
    val tema: StateFlow<pt.ipca.issafone.ui.ModoTema> = _tema
    fun mudarTema() {
        val novo = _tema.value.seguinte()
        ConfigStore.definirTema(getApplication(), novo)
        _tema.value = novo
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

    /** Progresso do anexo a enviar (0 a 1), ou null se não houver nenhum. */
    private val _anexo = MutableStateFlow<Float?>(null)
    val anexo: StateFlow<Float?> = _anexo

    /** Envia um ficheiro escolhido pelo utilizador (fotografia, PDF, etc.). */
    fun enviarAnexo(destino: String, uri: android.net.Uri) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        _anexo.value = 0f
        val ficheiro = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                var nome = "anexo"
                ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { nome = it }
                }
                // Nome seguro para o endereço (sem espaços nem caracteres estranhos)
                nome = nome.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(80).ifBlank { "anexo" }
                val pasta = java.io.File(ctx.cacheDir, "anexos").apply { mkdirs() }
                val f = java.io.File(pasta, "${System.currentTimeMillis()}_$nome")
                ctx.contentResolver.openInputStream(uri)!!.use { entrada -> f.outputStream().use { entrada.copyTo(it) } }
                // O nome que chega ao outro lado é o original (sem o prefixo de tempo)
                java.io.File(pasta, nome).also { destinoF -> destinoF.delete(); f.renameTo(destinoF) }
            }.getOrNull()
        }
        if (ficheiro == null) { _anexo.value = null; return@launch }
        xmpp.enviarFicheiro(destino, ficheiro) { _anexo.value = it }
        _anexo.value = null
    }
    fun adicionarContacto(u: String) = viewModelScope.launch { xmpp.adicionarContacto(u) }
    fun removerContacto(jid: String, depois: (Boolean) -> Unit) =
        viewModelScope.launch { depois(xmpp.removerContacto(jid)) }

    // Grupos
    fun criarGrupo(nome: String, membros: List<String>, depois: (String?) -> Unit) =
        viewModelScope.launch { depois(xmpp.criarGrupo(nome, membros)) }
    suspend fun membros(jid: String) = xmpp.membros(jid)
    fun adicionarMembro(jid: String, membro: String) = viewModelScope.launch { xmpp.adicionarMembro(jid, membro) }
    fun removerMembro(jid: String, membro: String) = viewModelScope.launch { xmpp.removerMembro(jid, membro) }
    fun sairGrupo(jid: String) = viewModelScope.launch { xmpp.sairGrupo(jid) }
    fun apagarGrupo(jid: String) = viewModelScope.launch { xmpp.apagarGrupo(jid) }

    // Atenção: não desligar nada em onCleared() — a sessão pertence ao serviço,
    // não ao ecrã. É isso que permite receber chamadas com a app fechada.
}
