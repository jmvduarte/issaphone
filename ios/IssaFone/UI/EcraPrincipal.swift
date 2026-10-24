import SwiftUI

struct EcraPrincipal: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var sip: SipManager
    @EnvironmentObject private var xmpp: XmppManager
    @State private var tab = 0

    var body: some View {
        ZStack {
            TabView(selection: $tab) {
                NavigationStack {
                    Teclado()
                        .toolbar { barra }
                }
                .tabItem { Label("Teclado", systemImage: "circle.grid.3x3.fill") }
                .tag(0)

                NavigationStack {
                    ListaContactos(onLigar: { ext in sip.chamar(ext); tab = 0 })
                        .toolbar { barra }
                }
                .tabItem { Label("Chat", systemImage: "bubble.left.and.bubble.right.fill") }
                .tag(1)
            }

            if sip.chamada.ativa || sip.chamada.aTocar {
                PainelChamada()
                    .transition(.opacity)
            }
        }
        .animation(.default, value: sip.chamada)
    }

    @ToolbarContentBuilder
    private var barra: some ToolbarContent {
        ToolbarItem(placement: .principal) {
            VStack(spacing: 0) {
                Text("Telefone: \(sip.registo)").font(.caption2)
                Text("Chat: \(xmpp.estado)").font(.caption2).lineLimit(1)
            }
            .foregroundStyle(.secondary)
        }
        ToolbarItem(placement: .navigationBarTrailing) {
            Button("Sair") { model.sair() }
        }
    }
}

// MARK: - Teclado

struct Teclado: View {
    @EnvironmentObject private var sip: SipManager
    @State private var numero = ""
    private let linhas = ["123", "456", "789", "*0#"]

    var body: some View {
        VStack(spacing: 14) {
            Spacer()
            Text(numero.isEmpty ? "Extensão ou número" : numero)
                .font(.system(size: 32, weight: .light))
                .foregroundStyle(numero.isEmpty ? .secondary : .primary)
                .padding(.bottom, 16)
            ForEach(linhas, id: \.self) { linha in
                HStack(spacing: 24) {
                    ForEach(Array(linha), id: \.self) { tecla in
                        Button { numero.append(tecla) } label: {
                            Text(String(tecla))
                                .font(.system(size: 28))
                                .frame(width: 76, height: 76)
                                .background(Circle().fill(Color(.secondarySystemBackground)))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            HStack(spacing: 40) {
                Button { numero = String(numero.dropLast()) } label: {
                    Image(systemName: "delete.left").font(.title2)
                }
                .disabled(numero.isEmpty)

                Button { sip.chamar(numero) } label: {
                    Image(systemName: "phone.fill")
                        .font(.title)
                        .foregroundStyle(.white)
                        .frame(width: 76, height: 76)
                        .background(Circle().fill(.green))
                }
                .disabled(numero.isEmpty)

                Color.clear.frame(width: 28)
            }
            .padding(.top, 12)
            Spacer()
        }
    }
}

// MARK: - Painel de chamada

struct PainelChamada: View {
    @EnvironmentObject private var sip: SipManager
    @State private var dtmf = ""

    var body: some View {
        ZStack {
            Color.black.opacity(0.92).ignoresSafeArea()
            VStack(spacing: 16) {
                Text(sip.chamada.remoto).font(.system(size: 36)).foregroundStyle(.white)
                Text(sip.chamada.estado).foregroundStyle(.gray)

                if sip.chamada.ativa {
                    TextField("Teclas (DTMF)", text: $dtmf)
                        .keyboardType(.phonePad)
                        .textFieldStyle(.roundedBorder)
                        .frame(maxWidth: 220)
                        .onChange(of: dtmf) { [anterior = dtmf] novo in
                            // só envia quando se acrescenta uma tecla (ignora apagar)
                            if novo.count > anterior.count, let ultima = novo.last { sip.dtmf(ultima) }
                        }
                }

                HStack(spacing: 40) {
                    if sip.chamada.aTocar {
                        botao("phone.fill", cor: .green) { sip.atender() }
                    } else {
                        botao(sip.mute ? "mic.slash.fill" : "mic.fill", cor: .gray) { sip.alternarMute() }
                    }
                    botao("phone.down.fill", cor: .red) { sip.desligar() }
                }
                .padding(.top, 30)
            }
            .padding()
        }
    }

    private func botao(_ icone: String, cor: Color, acao: @escaping () -> Void) -> some View {
        Button(action: acao) {
            Image(systemName: icone)
                .font(.title)
                .foregroundStyle(.white)
                .frame(width: 76, height: 76)
                .background(Circle().fill(cor))
        }
    }
}

// MARK: - Chat

struct ListaContactos: View {
    @EnvironmentObject private var xmpp: XmppManager
    let onLigar: (String) -> Void
    @State private var novo = ""

    var body: some View {
        List {
            Section {
                HStack {
                    TextField("Adicionar colega (ex.: 102)", text: $novo)
                        .textInputAutocapitalization(.never)
                    Button {
                        xmpp.adicionarContacto(novo); novo = ""
                    } label: { Image(systemName: "plus.circle.fill") }
                    .disabled(novo.isEmpty)
                }
            }
            Section {
                if xmpp.contactos.isEmpty {
                    Text("Sem contactos. Adiciona colegas acima ou cria grupos partilhados no Openfire.")
                        .foregroundStyle(.secondary)
                }
                ForEach(xmpp.contactos) { c in
                    NavigationLink {
                        Conversa(jid: c.jid, onLigar: onLigar)
                    } label: {
                        HStack(spacing: 12) {
                            Circle().fill(c.online ? .green : .gray).frame(width: 10, height: 10)
                            VStack(alignment: .leading) {
                                Text(c.nome).font(.headline)
                                Text(c.jid).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

struct Conversa: View {
    @EnvironmentObject private var xmpp: XmppManager
    let jid: String
    let onLigar: (String) -> Void
    @State private var texto = ""

    private var msgs: [Mensagem] {
        xmpp.mensagens.filter { $0.contacto.caseInsensitiveCompare(jid) == .orderedSame }
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(msgs) { m in
                            HStack {
                                if m.enviada { Spacer(minLength: 60) }
                                Text(m.texto)
                                    .padding(.horizontal, 12).padding(.vertical, 8)
                                    .background(m.enviada ? Color.accentColor : Color(.secondarySystemBackground))
                                    .foregroundStyle(m.enviada ? .white : .primary)
                                    .clipShape(RoundedRectangle(cornerRadius: 16))
                                if !m.enviada { Spacer(minLength: 60) }
                            }
                            .id(m.id)
                        }
                    }
                    .padding()
                }
                .onChange(of: msgs.count) { _ in
                    if let ultima = msgs.last { withAnimation { proxy.scrollTo(ultima.id, anchor: .bottom) } }
                }
            }
            Divider()
            HStack {
                TextField("Mensagem", text: $texto, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...4)
                Button("Enviar") { xmpp.enviar(para: jid, texto: texto); texto = "" }
                    .disabled(texto.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(8)
        }
        .navigationTitle(jid.components(separatedBy: "@").first ?? jid)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { onLigar(jid.components(separatedBy: "@").first ?? jid) } label: {
                    Image(systemName: "phone.fill")
                }
            }
        }
    }
}
