import Foundation
import linphonesw

/// Estado da chamada, simplificado para a interface.
struct CallUi: Equatable {
    var estado = ""
    var remoto = ""
    var aTocar = false   // chamada recebida à espera de resposta
    var ativa = false
}

/// Chamadas de voz via SIP contra o Asterisk do Issabel, usando o Linphone SDK.
@MainActor
final class SipManager: ObservableObject {

    @Published private(set) var registo = "Desligado"
    @Published private(set) var chamada = CallUi()
    @Published private(set) var mute = false

    private var core: Core!
    private var delegate: CoreDelegate!
    private var dominio = ""

    init() {
        do {
            LoggingService.Instance.logLevel = .Warning
            core = try Factory.Instance.createCore(configPath: "", factoryConfigPath: "", systemContext: nil)
            delegate = CoreDelegateStub(
                onCallStateChanged: { [weak self] _, call, state, message in
                    Task { @MainActor in self?.atualizarChamada(call: call, state: state, message: message) }
                },
                onAccountRegistrationStateChanged: { [weak self] _, _, state, message in
                    Task { @MainActor in self?.atualizarRegisto(state: state, message: message) }
                }
            )
            core.addDelegate(delegate: delegate)
            try core.start()
        } catch {
            registo = "Erro ao iniciar SIP: \(error)"
        }
    }

    func ligar(utilizador: String, password: String, servidor: String, porta: Int = 5060) {
        guard let core else { return }
        dominio = servidor
        do {
            core.clearAccounts()
            core.clearAllAuthInfo()

            let auth = try Factory.Instance.createAuthInfo(
                username: utilizador, userid: nil, passwd: password,
                ha1: nil, realm: nil, domain: servidor)
            let params = try core.createAccountParams()
            let identidade = try Factory.Instance.createAddress(addr: "sip:\(utilizador)@\(servidor)")
            try params.setIdentityaddress(newValue: identidade)
            let servidorAddr = try Factory.Instance.createAddress(addr: "sip:\(servidor):\(porta)")
            try servidorAddr.setTransport(newValue: .Udp)
            try params.setServeraddress(newValue: servidorAddr)
            params.registerEnabled = true

            let conta = try core.createAccount(params: params)
            core.addAuthInfo(info: auth)
            try core.addAccount(account: conta)
            core.defaultAccount = conta
        } catch {
            registo = "Erro: \(error)"
        }
    }

    /// Liga para uma extensão (ex.: "101") ou número.
    func chamar(_ destino: String) {
        guard let core, !destino.isEmpty else { return }
        let uri = destino.hasPrefix("sip:") ? destino : "sip:\(destino)@\(dominio)"
        do {
            let endereco = try Factory.Instance.createAddress(addr: uri)
            let params = try core.createCallParams(call: nil)
            _ = core.inviteAddressWithParams(addr: endereco, params: params)
        } catch {
            chamada = CallUi(estado: "Erro: \(error)", remoto: destino)
        }
    }

    func atender() {
        try? (core.currentCall ?? core.calls.first)?.accept()
    }

    func desligar() {
        try? (core.currentCall ?? core.calls.first)?.terminate()
    }

    func alternarMute() {
        core.micEnabled.toggle()
        mute = !core.micEnabled
    }

    /// Envia tons DTMF (ex.: para menus IVR).
    func dtmf(_ tecla: Character) {
        guard let c = tecla.asciiValue else { return }
        try? core.currentCall?.sendDtmf(dtmf: CChar(c))
    }

    func terminar() {
        if let conta = core.defaultAccount, let p = conta.params?.clone() {
            p.registerEnabled = false
            conta.params = p
        }
    }

    // MARK: - Callbacks

    private func atualizarRegisto(state: RegistrationState, message: String) {
        switch state {
        case .Ok: registo = "Registado"
        case .Progress: registo = "A registar…"
        case .Failed: registo = "Falhou: \(message)"
        case .Cleared, .None: registo = "Desligado"
        default: registo = "\(state)"
        }
    }

    private func atualizarChamada(call: Call, state: Call.State, message: String) {
        let remoto = call.remoteAddress?.username ?? call.remoteAddress?.asStringUriOnly() ?? ""
        switch state {
        case .IncomingReceived:
            chamada = CallUi(estado: "A receber chamada", remoto: remoto, aTocar: true)
        case .OutgoingInit, .OutgoingProgress, .OutgoingRinging:
            chamada = CallUi(estado: "A chamar…", remoto: remoto, ativa: true)
        case .Connected, .StreamsRunning:
            chamada = CallUi(estado: "Em chamada", remoto: remoto, ativa: true)
        case .Paused:
            chamada = CallUi(estado: "Em espera", remoto: remoto, ativa: true)
        case .Error:
            chamada = CallUi(estado: "Erro: \(message)", remoto: remoto)
        case .End, .Released:
            chamada = CallUi()
            mute = false
            core.micEnabled = true
        default:
            break
        }
    }
}
