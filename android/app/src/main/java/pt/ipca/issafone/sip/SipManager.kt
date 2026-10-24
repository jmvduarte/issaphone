package pt.ipca.issafone.sip

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.linphone.core.Account
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType

/** Estado da chamada, simplificado para a interface. */
data class CallUi(
    val estado: String = "",
    val remoto: String = "",
    val aTocar: Boolean = false,        // chamada recebida à espera de resposta
    val ativa: Boolean = false,
    val ofereceVideo: Boolean = false,  // a chamada recebida traz vídeo
    val comVideo: Boolean = false,      // a chamada em curso tem vídeo
    val nome: String = ""               // nome enviado pelo Issabel (Display Name), se houver
)

/**
 * Chamadas e videochamadas via SIP contra o Asterisk do Issabel, usando o Linphone SDK.
 * Vídeo em VP8 ou H.264 — os mesmos formatos da webapp, porque o Asterisk não converte vídeo.
 */
class SipManager(context: Context) {

    private val factory = Factory.instance()
    private val core: Core = factory.createCore(null, null, context)

    private val _registo = MutableStateFlow("Desligado")
    val registo: StateFlow<String> = _registo

    private val _chamada = MutableStateFlow(CallUi())
    val chamada: StateFlow<CallUi> = _chamada

    private val _camaraLigada = MutableStateFlow(true)
    val camaraLigada: StateFlow<Boolean> = _camaraLigada

    private var dominio = ""

    /** Registo de chamadas (feitas, recebidas, perdidas, recusadas). */
    val registoChamadas = RegistoChamadas(context)
    private var videoNaChamada = false

    private val listener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(
            core: Core, account: Account, state: RegistrationState, message: String
        ) {
            _registo.value = when (state) {
                RegistrationState.Ok -> "Registado"
                RegistrationState.Progress -> "A registar…"
                RegistrationState.Failed -> "Falhou: $message"
                RegistrationState.Cleared -> "Desligado"
                else -> state.name
            }
        }

        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
            val remoto = call.remoteAddress.username ?: call.remoteAddress.asStringUriOnly()
            val video = call.currentParams.isVideoEnabled
            if (video || call.remoteParams?.isVideoEnabled == true && state == Call.State.IncomingReceived ||
                (state == Call.State.OutgoingInit && call.params.isVideoEnabled)) videoNaChamada = true
            if (state == Call.State.Released) registar(call, remoto)
            val nome = call.remoteAddress.displayName?.takeIf { it.isNotBlank() && it != remoto } ?: ""
            val novo = when (state) {
                Call.State.IncomingReceived -> {
                    val oferece = call.remoteParams?.isVideoEnabled == true
                    CallUi(if (oferece) "Videochamada a chegar" else "A receber chamada", remoto,
                        aTocar = true, ofereceVideo = oferece)
                }
                Call.State.OutgoingInit,
                Call.State.OutgoingProgress,
                Call.State.OutgoingRinging -> CallUi("A chamar…", remoto, ativa = true, comVideo = call.params.isVideoEnabled)
                Call.State.Connected,
                Call.State.StreamsRunning,
                Call.State.Updating,
                Call.State.UpdatedByRemote -> CallUi("Em chamada", remoto, ativa = true, comVideo = video)
                Call.State.Paused -> CallUi("Em espera", remoto, ativa = true)
                Call.State.Error -> CallUi("Erro: $message", remoto)
                Call.State.End,
                Call.State.Released -> CallUi().also { _camaraLigada.value = true }
                else -> _chamada.value
            }
            _chamada.value = if (novo.remoto.isNotEmpty() && novo.nome.isEmpty()) novo.copy(nome = nome) else novo
        }
    }

    /** Junta a chamada que acabou ao registo. */
    private fun registar(call: Call, numero: String) {
        val log = call.callLog
        val tipo = when {
            call.dir == Call.Dir.Outgoing -> "feita"
            log.status == Call.Status.Success -> "recebida"
            log.status == Call.Status.Declined -> "recusada"
            else -> "perdida"
        }
        val nome = call.remoteAddress.displayName?.takeIf { it.isNotBlank() } ?: numero
        registoChamadas.juntar(
            Chamada(numero, nome, tipo, videoNaChamada, log.startDate * 1000L,
                if (log.status == Call.Status.Success) log.duration else 0)
        )
        videoNaChamada = false
    }

    init {
        // Toca com o toque de chamada do telemóvel (respeita volume e modo silêncio)
        core.isNativeRingingEnabled = true
        // Pacotes de keep-alive para manter o registo vivo através do NAT/router
        core.isKeepAliveEnabled = true

        // Vídeo: disponível, mas só é usado quando se escolhe videochamada
        core.isVideoCaptureEnabled = true
        core.isVideoDisplayEnabled = true
        core.videoActivationPolicy = factory.createVideoActivationPolicy().apply {
            automaticallyInitiate = false
            automaticallyAccept = false
        }
        core.videoPayloadTypes.forEach { it.enable(it.mimeType.uppercase() in setOf("VP8", "H264")) }
        usarCamaraFrontal()

        core.addListener(listener)
        core.start()
    }

    /** Força novo registo (ex.: depois de o telemóvel mudar de rede). */
    fun refrescarRegisto() = core.refreshRegisters()

    fun ligar(utilizador: String, password: String, servidor: String, porta: Int = 5060) {
        dominio = servidor
        registoChamadas.usarConta("$utilizador@$servidor")
        // Limpa contas anteriores
        core.clearAccounts()
        core.clearAllAuthInfo()

        val auth = factory.createAuthInfo(utilizador, null, password, null, null, servidor, null)
        val params = core.createAccountParams()
        params.identityAddress = factory.createAddress("sip:$utilizador@$servidor")
        val servidorAddr = factory.createAddress("sip:$servidor:$porta")
        servidorAddr?.transport = TransportType.Udp
        params.serverAddress = servidorAddr
        params.isRegisterEnabled = true
        // Registo curto: o Asterisk fica a saber depressa se o telemóvel saiu da rede
        params.expires = 120

        val conta = core.createAccount(params)
        core.addAuthInfo(auth)
        core.addAccount(conta)
        core.defaultAccount = conta
    }

    private fun chamadaAtual(): Call? = core.currentCall ?: core.calls.firstOrNull()

    /** Liga para uma extensão (ex.: "101") ou número; [video] = videochamada. */
    fun chamar(destino: String, video: Boolean = false) {
        if (destino.isBlank()) return
        val uri = if (destino.startsWith("sip:")) destino else "sip:$destino@$dominio"
        val endereco = core.interpretUrl(uri) ?: return
        val params = core.createCallParams(null) ?: return
        params.isVideoEnabled = video
        _camaraLigada.value = true
        core.inviteAddressWithParams(endereco, params)
    }

    /** Atende; com [video] = true envia também a câmara (se a chamada trouxer vídeo). */
    fun atender(video: Boolean = false) {
        val call = chamadaAtual() ?: return
        val params = core.createCallParams(call) ?: run { call.accept(); return }
        params.isVideoEnabled = video && call.remoteParams?.isVideoEnabled == true
        _camaraLigada.value = true
        call.acceptWithParams(params)
    }

    fun desligar() {
        chamadaAtual()?.terminate()
    }

    fun alternarMute(): Boolean {
        core.isMicEnabled = !core.isMicEnabled
        return !core.isMicEnabled
    }

    /** Liga/desliga a câmara a meio da chamada, sem a terminar. */
    fun alternarCamara() {
        val call = chamadaAtual() ?: return
        val params = core.createCallParams(call) ?: return
        val ligar = !_camaraLigada.value
        params.isVideoEnabled = true
        params.videoDirection = if (ligar) org.linphone.core.MediaDirection.SendRecv
                                else org.linphone.core.MediaDirection.RecvOnly
        call.update(params)
        _camaraLigada.value = ligar
    }

    /** Passa a videochamada (ou a voltar a só áudio) a meio da chamada. */
    fun alternarVideo() {
        val call = chamadaAtual() ?: return
        val params = core.createCallParams(call) ?: return
        params.isVideoEnabled = !call.currentParams.isVideoEnabled
        _camaraLigada.value = true
        call.update(params)
    }

    /** Troca entre câmara frontal e traseira. */
    fun trocarCamara() {
        val camaras = core.videoDevicesList.filter { !it.contains("StaticImage", ignoreCase = true) }
        if (camaras.size < 2) return
        val atual = camaras.indexOf(core.videoDevice)
        core.videoDevice = camaras[(atual + 1) % camaras.size]
        chamadaAtual()?.let { it.update(core.createCallParams(it)) }
    }

    private fun usarCamaraFrontal() {
        core.videoDevicesList.firstOrNull { it.contains("Front", ignoreCase = true) }
            ?.let { core.videoDevice = it }
    }

    /** Ligam as vistas do ecrã ao vídeo: a remota (TextureView) e a pré-visualização da câmara. */
    fun definirVistaRemota(vista: Any?) { core.nativeVideoWindowId = vista }
    fun definirVistaLocal(vista: Any?) { core.nativePreviewWindowId = vista }

    /** Envia tons DTMF (ex.: para menus IVR). */
    fun dtmf(tecla: Char) {
        core.currentCall?.sendDtmf(tecla)
    }

    /** Retira o registo no Asterisk ("Sair"); o Core continua pronto para nova sessão. */
    fun desregistar() {
        core.calls.forEach { it.terminate() }
        core.defaultAccount?.let { conta ->
            val p = conta.params.clone()
            p.isRegisterEnabled = false
            conta.params = p
        }
    }
}
