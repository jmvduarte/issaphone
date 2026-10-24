import Foundation
import XMPPFramework

struct Mensagem: Identifiable, Equatable {
    let id = UUID()
    let contacto: String   // JID "utilizador@dominio"
    let texto: String
    let enviada: Bool
    let hora = Date()
}

struct Contacto: Identifiable, Equatable {
    var id: String { jid }
    let jid: String
    let nome: String
    let online: Bool
}

/// Chat interno via XMPP contra o Openfire instalado no Issabel, usando o XMPPFramework.
final class XmppManager: NSObject, ObservableObject {

    @Published private(set) var estado = "Desligado"
    @Published private(set) var mensagens: [Mensagem] = []
    @Published private(set) var contactos: [Contacto] = []

    private let stream = XMPPStream()
    // Os inicializadores Objective-C do XMPPFramework devolvem opcionais em Swift
    private let rosterStorage: XMPPRosterMemoryStorage = XMPPRosterMemoryStorage()!
    private lazy var roster: XMPPRoster = XMPPRoster(rosterStorage: rosterStorage)
    private let reconnect = XMPPReconnect()

    private var password = ""
    private var dominio = ""
    private var usarTls = false

    override init() {
        super.init()
        stream.addDelegate(self, delegateQueue: .main)

        roster.autoFetchRoster = true
        roster.autoAcceptKnownPresenceSubscriptionRequests = true
        roster.activate(stream)
        // Recebe também os eventos do armazenamento em memória (xmppRosterDidChange)
        roster.addDelegate(self, delegateQueue: .main)
        reconnect.activate(stream)
    }

    /// - Parameters:
    ///   - dominio: XMPP Domain configurado no Openfire (ver consola :9090)
    ///   - host: IP do servidor Issabel
    ///   - usarTls: false para servidores de laboratório com certificado autoassinado
    func ligar(utilizador: String, password: String, dominio: String, host: String,
               porta: UInt16 = 5222, usarTls: Bool = false) {
        desligar()
        self.password = password
        self.dominio = dominio
        self.usarTls = usarTls

        stream.myJID = XMPPJID(string: "\(utilizador)@\(dominio)/IssaFone")
        stream.hostName = host
        stream.hostPort = porta
        stream.startTLSPolicy = usarTls ? .required : .allowed

        estado = "A ligar…"
        do {
            try stream.connect(withTimeout: 15)
        } catch {
            estado = "Erro: \(error.localizedDescription)"
        }
    }

    func desligar() {
        guard stream.isConnected || stream.isConnecting else { return }
        stream.send(XMPPPresence(type: "unavailable"))
        stream.disconnect()
        estado = "Desligado"
    }

    /// Aceita "101" (completa com o domínio) ou "101@dominio".
    func enviar(para destino: String, texto: String) {
        let corpo = texto.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !corpo.isEmpty else { return }
        let jidTexto = destino.contains("@") ? destino : "\(destino)@\(dominio)"
        guard let jid = XMPPJID(string: jidTexto) else { return }
        let msg = XMPPMessage(type: "chat", to: jid)
        msg.addBody(corpo)
        stream.send(msg)
        mensagens.append(Mensagem(contacto: jidTexto, texto: corpo, enviada: true))
    }

    /// Adiciona um colega à lista de contactos (pede subscrição de presença).
    func adicionarContacto(_ utilizador: String) {
        let jidTexto = utilizador.contains("@") ? utilizador : "\(utilizador)@\(dominio)"
        guard let jid = XMPPJID(string: jidTexto) else { return }
        roster.addUser(jid, withNickname: utilizador.components(separatedBy: "@").first)
    }

    private func atualizarContactos() {
        let users = (rosterStorage.unsortedUsers() as? [XMPPUserMemoryStorageObject]) ?? []
        contactos = users.map {
            Contacto(jid: $0.jid.bare,
                     nome: $0.nickname ?? $0.jid.user ?? $0.jid.bare,
                     online: $0.isOnline)
        }
        .sorted { ($0.online ? 0 : 1, $0.nome) < ($1.online ? 0 : 1, $1.nome) }
    }
}

// MARK: - XMPPStreamDelegate

extension XmppManager: XMPPStreamDelegate {

    func xmppStreamDidConnect(_ sender: XMPPStream) {
        do {
            try stream.authenticate(withPassword: password)
        } catch {
            estado = "Erro na autenticação: \(error.localizedDescription)"
        }
    }

    func xmppStreamDidAuthenticate(_ sender: XMPPStream) {
        stream.send(XMPPPresence())
        estado = "Ligado como \(sender.myJID?.bare ?? "")"
    }

    func xmppStream(_ sender: XMPPStream, didNotAuthenticate error: DDXMLElement) {
        estado = "Utilizador ou password do chat errados"
    }

    func xmppStreamDidDisconnect(_ sender: XMPPStream, withError error: Error?) {
        estado = error.map { "Desligado: \($0.localizedDescription)" } ?? "Desligado"
    }

    /// O Openfire usa um certificado autoassinado: em laboratório aceitamo-lo.
    func xmppStream(_ sender: XMPPStream, willSecureWithSettings settings: NSMutableDictionary) {
        settings[GCDAsyncSocketManuallyEvaluateTrust] = true
    }

    func xmppStream(_ sender: XMPPStream, didReceive trust: SecTrust,
                    completionHandler: @escaping (Bool) -> Void) {
        completionHandler(true)
    }

    func xmppStream(_ sender: XMPPStream, didReceive message: XMPPMessage) {
        guard message.isChatMessageWithBody, let corpo = message.body,
              let de = message.from?.bare else { return }
        mensagens.append(Mensagem(contacto: de, texto: corpo, enviada: false))
    }
}

// MARK: - Roster

extension XmppManager: XMPPRosterDelegate, XMPPRosterMemoryStorageDelegate {
    func xmppRosterDidChange(_ sender: XMPPRosterMemoryStorage) {
        atualizarContactos()
    }

    func xmppRosterDidPopulate(_ sender: XMPPRosterMemoryStorage) {
        atualizarContactos()
    }
}
