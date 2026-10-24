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
    val aTocar: Boolean = false,   // chamada recebida à espera de resposta
    val ativa: Boolean = false
)

/**
 * Chamadas de voz via SIP contra o Asterisk do Issabel, usando o Linphone SDK.
 */
class SipManager(context: Context) {

    private val factory = Factory.instance()
    private val core: Core = factory.createCore(null, null, context)

    private val _registo = MutableStateFlow("Desligado")
    val registo: StateFlow<String> = _registo

    private val _chamada = MutableStateFlow(CallUi())
    val chamada: StateFlow<CallUi> = _chamada

    private var dominio = ""

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
            _chamada.value = when (state) {
                Call.State.IncomingReceived -> CallUi("A receber chamada", remoto, aTocar = true)
                Call.State.OutgoingInit,
                Call.State.OutgoingProgress,
                Call.State.OutgoingRinging -> CallUi("A chamar…", remoto, ativa = true)
                Call.State.Connected,
                Call.State.StreamsRunning -> CallUi("Em chamada", remoto, ativa = true)
                Call.State.Paused -> CallUi("Em espera", remoto, ativa = true)
                Call.State.Error -> CallUi("Erro: $message", remoto)
                Call.State.End,
                Call.State.Released -> CallUi()
                else -> _chamada.value
            }
        }
    }

    init {
        // Toca com o toque de chamada do telemóvel (respeita volume e modo silêncio)
        core.isNativeRingingEnabled = true
        // Pacotes de keep-alive para manter o registo vivo através do NAT/router
        core.isKeepAliveEnabled = true
        core.addListener(listener)
        core.start()
    }

    /** Força novo registo (ex.: depois de o telemóvel mudar de rede). */
    fun refrescarRegisto() = core.refreshRegisters()

    fun ligar(utilizador: String, password: String, servidor: String, porta: Int = 5060) {
        dominio = servidor
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

    /** Liga para uma extensão (ex.: "101") ou número. */
    fun chamar(destino: String) {
        if (destino.isBlank()) return
        val uri = if (destino.startsWith("sip:")) destino else "sip:$destino@$dominio"
        val endereco = core.interpretUrl(uri) ?: return
        val params = core.createCallParams(null) ?: return
        core.inviteAddressWithParams(endereco, params)
    }

    fun atender() {
        core.currentCall?.accept() ?: core.calls.firstOrNull()?.accept()
    }

    fun desligar() {
        val call = core.currentCall ?: core.calls.firstOrNull() ?: return
        call.terminate()
    }

    fun alternarMute(): Boolean {
        core.isMicEnabled = !core.isMicEnabled
        return !core.isMicEnabled
    }

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
