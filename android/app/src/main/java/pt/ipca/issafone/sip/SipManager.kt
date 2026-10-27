package pt.ipca.issafone.sip

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.linphone.core.Account
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.MediaEncryption
import org.linphone.core.Reason
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
    val nome: String = "",              // nome enviado pelo Issabel (Display Name), se houver
    val emEspera: String = "",          // outra chamada em espera (número), se houver
    val chamadas: Int = 0,              // número de chamadas abertas (para alternar/juntar)
    val conferencia: Boolean = false,   // várias pessoas juntas na mesma chamada
    val participantes: Int = 0
)

/**
 * Chamadas e videochamadas via SIP contra o Asterisk do Issabel, usando o Linphone SDK.
 * Vídeo em VP8 ou H.264 — os mesmos formatos da webapp, porque o Asterisk não converte vídeo.
 */
class SipManager(context: Context) {

    private val factory = Factory.instance()
    private val core: Core = factory.createCore(null, null, context)
    private val rota = AudioRota(context)
    private var videoAplicado: Boolean? = null   // já se escolheu a saída do som para esta chamada?

    private val _registo = MutableStateFlow("Desligado")
    val registo: StateFlow<String> = _registo

    private val _chamada = MutableStateFlow(CallUi())
    val chamada: StateFlow<CallUi> = _chamada

    private val _camaraLigada = MutableStateFlow(true)
    val camaraLigada: StateFlow<Boolean> = _camaraLigada

    /** Som a sair pelo altifalante (alta voz). */
    private val _altaVoz = MutableStateFlow(false)
    val altaVoz: StateFlow<Boolean> = _altaVoz

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

        // Conferência criada/terminada: atualiza o ecrã
        override fun onConferenceStateChanged(core: Core, conference: org.linphone.core.Conference, state: org.linphone.core.Conference.State) {
            _chamada.value = calcularUi(null, null)
        }

        // A Linphone muda de saída sozinha (ex.: videochamada → altifalante; auriculares ligados)
        override fun onAudioDeviceChanged(core: Core, audioDevice: AudioDevice) {
            _altaVoz.value = audioDevice.type == AudioDevice.Type.Speaker
        }

        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
            val remoto = call.remoteAddress.username ?: call.remoteAddress.asStringUriOnly()
            val video = call.currentParams.isVideoEnabled
            if (video || call.remoteParams?.isVideoEnabled == true && state == Call.State.IncomingReceived ||
                (state == Call.State.OutgoingInit && call.params.isVideoEnabled)) videoNaChamada = true
            if (state == Call.State.Released) registar(call, remoto)
            // Ao atender/ser atendido: se houver auriculares Bluetooth ligados, o som vai para lá
            if (state == Call.State.Connected) usarBluetoothSeHouver(call)
            // Som da chamada no Android: foco de áudio (pára a música) e Bluetooth do carro/auriculares
            // (só da primeira vez e quando a chamada passa a ter/deixa de ter vídeo, para não
            // desfazer o que a pessoa escolheu no botão Alta voz)
            if (state == Call.State.StreamsRunning) {
                val video = call.currentParams.isVideoEnabled
                if (video != videoAplicado) {
                    videoAplicado = video
                    if (rota.iniciar(video)) {
                        core.audioDevices.firstOrNull {
                            it.type == AudioDevice.Type.Speaker && it.hasCapability(AudioDevice.Capabilities.CapabilityPlay)
                        }?.let { call.outputAudioDevice = it }
                        _altaVoz.value = true
                    }
                }
            }
            if (state == Call.State.End || state == Call.State.Released) {
                if (core.calls.none { it.state !in terminadas }) {
                    _camaraLigada.value = true; _altaVoz.value = false; rota.terminar(); videoAplicado = null
                }
            }
            _chamada.value = calcularUi(if (state == Call.State.Error) "Erro: $message" else null, call)
        }
    }

    private val terminadas = setOf(Call.State.End, Call.State.Released, Call.State.Error)

    private fun numero(call: Call) = call.remoteAddress.username ?: call.remoteAddress.asStringUriOnly()

    /**
     * Estado para o ecrã, olhando para TODAS as chamadas: uma a tocar tem prioridade;
     * depois a conferência; depois a chamada ativa (com a outra, se houver, em espera).
     */
    private fun calcularUi(erro: String?, ultima: Call?): CallUi {
        val abertas = core.calls.filter { it.state !in terminadas }
        if (abertas.isEmpty()) return if (erro != null && ultima != null) CallUi(erro, numero(ultima)) else CallUi()
        val nomeDe = { c: Call -> c.remoteAddress.displayName?.takeIf { it.isNotBlank() && it != numero(c) } ?: "" }

        abertas.firstOrNull { it.state == Call.State.IncomingReceived || it.state == Call.State.IncomingEarlyMedia }?.let { c ->
            val oferece = c.remoteParams?.isVideoEnabled == true
            val outra = abertas.firstOrNull { it != c }
            return CallUi(if (oferece) "Videochamada a chegar" else "A receber chamada", numero(c),
                aTocar = true, ofereceVideo = oferece, nome = nomeDe(c),
                emEspera = outra?.let { numero(it) } ?: "", chamadas = abertas.size)
        }

        val conf = core.conference
        if (conf != null && conf.isIn && conf.participantCount > 0) {
            return CallUi("Em conferência", "Conferência", ativa = true, conferencia = true,
                participantes = conf.participantCount + 1, chamadas = abertas.size)
        }

        val principal = core.currentCall?.takeIf { it.state !in terminadas }
            ?: abertas.firstOrNull { it.state != Call.State.Paused } ?: abertas.first()
        val outra = abertas.firstOrNull { it != principal }
        val estado = when (principal.state) {
            Call.State.OutgoingInit, Call.State.OutgoingProgress,
            Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> "A chamar…"
            Call.State.Paused, Call.State.Pausing -> "Em espera"
            Call.State.PausedByRemote -> "Pôs-te em espera"
            else -> "Em chamada"
        }
        val video = if (estado == "A chamar…") principal.params.isVideoEnabled else principal.currentParams.isVideoEnabled
        return CallUi(estado, numero(principal), ativa = true, comVideo = video, nome = nomeDe(principal),
            emEspera = outra?.let { numero(it) } ?: "", chamadas = abertas.size)
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

        // Cifra: áudio e vídeo SRTP obrigatórios (o servidor também os exige)
        core.mediaEncryption = MediaEncryption.SRTP
        core.isMediaEncryptionMandatory = true
        // Só as cifras que o Asterisk 16 (libsrtp 1.5) suporta. A Linphone oferecia primeiro
        // AEAD_AES_128_GCM, que o Asterisk recusa ("Unsupported crypto suite").
        core.config.setString("sip", "srtp_crypto_suites", "AES_CM_128_HMAC_SHA1_80, AES_CM_128_HMAC_SHA1_32")
        // SIP sobre TLS: valida o certificado Let's Encrypt do servidor com as CAs do Android
        carregarCAsDoSistema()?.let { core.setRootCaData(it) }
        core.verifyServerCertificates(true)
        core.verifyServerCn(true)

        core.addListener(listener)
        core.start()
    }

    /** Junta os certificados de raiz do Android (inclui o ISRG Root X1 do Let's Encrypt). */
    private fun carregarCAsDoSistema(): String? = try {
        val bloco = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----")
        java.io.File("/system/etc/security/cacerts").listFiles().orEmpty()
            .flatMap { f -> runCatching { bloco.findAll(f.readText()).map { it.value }.toList() }.getOrDefault(emptyList()) }
            .joinToString("\n")
            .takeIf { it.isNotEmpty() }
    } catch (e: Exception) { null }

    /** Força novo registo (ex.: depois de o telemóvel mudar de rede). */
    fun refrescarRegisto() = core.refreshRegisters()

    fun ligar(utilizador: String, password: String, servidor: String, porta: Int = 5061) {
        dominio = servidor
        registoChamadas.usarConta("$utilizador@$servidor")
        // Limpa contas anteriores
        core.clearAccounts()
        core.clearAllAuthInfo()

        val auth = factory.createAuthInfo(utilizador, null, password, null, null, servidor, null)
        val params = core.createAccountParams()
        params.identityAddress = factory.createAddress("sip:$utilizador@$servidor")
        val servidorAddr = factory.createAddress("sip:$servidor:$porta")
        // SIP sobre TLS (porta 5061): o registo e as chamadas vão cifrados
        servidorAddr?.transport = TransportType.Tls
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
        val call = core.calls.firstOrNull { it.state == Call.State.IncomingReceived || it.state == Call.State.IncomingEarlyMedia }
            ?: chamadaAtual() ?: return
        // Chamada em espera: a que estava a decorrer fica em espera
        core.calls.filter { it != call && it.state == Call.State.StreamsRunning }.forEach { it.pause() }
        val params = core.createCallParams(call) ?: run { call.accept(); return }
        params.isVideoEnabled = video && call.remoteParams?.isVideoEnabled == true
        _camaraLigada.value = true
        call.acceptWithParams(params)
    }

    /** Recusa a chamada a tocar, ou desliga a chamada atual (ou a conferência inteira). */
    fun desligar() {
        core.calls.firstOrNull { it.state == Call.State.IncomingReceived || it.state == Call.State.IncomingEarlyMedia }
            ?.let { it.decline(Reason.Declined); return }
        val conf = core.conference
        if (conf != null && conf.isIn) { conf.terminate(); return }
        val call = chamadaAtual() ?: return
        call.terminate()
        // Se ficar uma chamada em espera, volta a ela
        core.calls.firstOrNull { it != call && it.state == Call.State.Paused }?.resume()
    }

    private fun endereco(destino: String) =
        core.interpretUrl(if (destino.startsWith("sip:")) destino else "sip:${destino.trim()}@$dominio")

    /** Transferência direta: a pessoa com quem falo passa a falar com [destino] e eu saio. */
    fun transferir(destino: String) {
        val call = core.currentCall ?: return
        endereco(destino)?.let { call.transferTo(it) }
    }

    /** Põe a chamada atual em espera e liga para outra extensão (para consultar, transferir ou juntar). */
    fun adicionar(destino: String) {
        core.currentCall?.pause()
        chamar(destino)
    }

    /** Troca entre a chamada ativa e a que está em espera. */
    fun alternar() {
        val ativa = core.currentCall
        val espera = core.calls.firstOrNull { it != ativa && it.state == Call.State.Paused } ?: return
        ativa?.pause()
        espera.resume()
    }

    /** Junta todas as chamadas numa conferência (o som é misturado neste telemóvel). */
    fun juntar() {
        val abertas = core.calls.filter { it.state !in terminadas }
        if (abertas.size < 2) return
        val params = core.createConferenceParams(null)
        params.isVideoEnabled = false
        val conf = core.conference ?: core.createConferenceWithParams(params) ?: return
        conf.addParticipants(abertas.filter { it.conference == null }.toTypedArray())
        conf.enter()
    }

    /** Transferência com consulta: liga a pessoa em espera à pessoa com quem estou a falar, e eu saio. */
    fun ligarOsDois() {
        val ativa = core.currentCall ?: return
        val espera = core.calls.firstOrNull { it != ativa && it.state == Call.State.Paused } ?: return
        espera.transferToAnother(ativa)
    }

    private fun usarBluetoothSeHouver(call: Call) {
        val bt = core.audioDevices.firstOrNull {
            it.type == AudioDevice.Type.Bluetooth && it.hasCapability(AudioDevice.Capabilities.CapabilityPlay)
        } ?: return
        call.outputAudioDevice = bt
        core.audioDevices.firstOrNull {
            it.type == AudioDevice.Type.Bluetooth && it.hasCapability(AudioDevice.Capabilities.CapabilityRecord)
        }?.let { call.inputAudioDevice = it }
        _altaVoz.value = false
    }

    /** Liga/desliga a alta voz. Ao desligar, usa os auriculares (Bluetooth/fios) se houver, senão o auscultador. */
    fun alternarAltaVoz() {
        val call = chamadaAtual() ?: return
        val ligar = !_altaVoz.value
        val noBluetooth = rota.usarAltifalante(ligar)
        val saidas = core.audioDevices.filter { it.hasCapability(AudioDevice.Capabilities.CapabilityPlay) }
        // O Android já pôs o som no Bluetooth e a Linphone não o conhece: não deixar que o troque pelo auscultador
        if (noBluetooth && saidas.none { it.type == AudioDevice.Type.Bluetooth }) { _altaVoz.value = false; return }
        val destino = if (ligar) {
            saidas.firstOrNull { it.type == AudioDevice.Type.Speaker }
        } else {
            saidas.firstOrNull { it.type == AudioDevice.Type.Bluetooth }
                ?: saidas.firstOrNull { it.type == AudioDevice.Type.Headset || it.type == AudioDevice.Type.Headphones }
                ?: saidas.firstOrNull { it.type == AudioDevice.Type.Earpiece }
        } ?: run { _altaVoz.value = ligar; return }
        call.outputAudioDevice = destino
        if (destino.type == AudioDevice.Type.Bluetooth) {
            core.audioDevices.firstOrNull {
                it.type == AudioDevice.Type.Bluetooth && it.hasCapability(AudioDevice.Capabilities.CapabilityRecord)
            }?.let { call.inputAudioDevice = it }
        }
        _altaVoz.value = ligar
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
