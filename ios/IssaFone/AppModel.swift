import Foundation
import AVFoundation

/// Dados de ligação ao Issabel. O mesmo utilizador serve SIP e XMPP.
struct Config: Codable, Equatable {
    var servidor = ""       // IP do Issabel
    var utilizador = ""     // extensão, ex.: 101
    var passwordSip = ""    // "secret" da extensão
    var dominioXmpp = ""    // XMPP Domain do Openfire
    var passwordXmpp = ""   // password no Openfire (vazio = igual ao SIP)
    var usarTls = false
}

@MainActor
final class AppModel: ObservableObject {

    let sip = SipManager()
    let xmpp = XmppManager()

    @Published var config: Config
    @Published var ligado = false

    private let chave = "issafone.config"

    init() {
        // Nota: UserDefaults é aceitável num projeto académico;
        // em produção as passwords devem ir para a Keychain.
        if let data = UserDefaults.standard.data(forKey: chave),
           let c = try? JSONDecoder().decode(Config.self, from: data) {
            config = c
        } else {
            config = Config()
        }
    }

    func entrar(_ c: Config) {
        config = c
        if let data = try? JSONEncoder().encode(c) {
            UserDefaults.standard.set(data, forKey: chave)
        }

        AVAudioSession.sharedInstance().requestRecordPermission { _ in }

        sip.ligar(utilizador: c.utilizador, password: c.passwordSip, servidor: c.servidor)
        xmpp.ligar(
            utilizador: c.utilizador,
            password: c.passwordXmpp.isEmpty ? c.passwordSip : c.passwordXmpp,
            dominio: c.dominioXmpp.isEmpty ? c.servidor : c.dominioXmpp,
            host: c.servidor,
            usarTls: c.usarTls
        )
        ligado = true
    }

    func sair() {
        xmpp.desligar()
        sip.terminar()
        ligado = false
    }
}
