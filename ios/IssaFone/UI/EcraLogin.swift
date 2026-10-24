import SwiftUI

struct EcraLogin: View {
    @EnvironmentObject private var model: AppModel
    @State private var c = Config()
    @State private var avancado = false

    private var podeEntrar: Bool { !c.servidor.isEmpty && !c.utilizador.isEmpty && !c.passwordSip.isEmpty }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(spacing: 8) {
                        Image("Logo")
                            .resizable()
                            .frame(width: 88, height: 88)
                            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                        Text("IssaFone").font(.title.bold())
                        Text("Chamadas e chat interno do Issabel")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .listRowBackground(Color.clear)
                }

                Section {
                    TextField("Extensão", text: $c.utilizador)
                        .keyboardType(.numberPad)
                        .textContentType(.username)
                    SecureField("Palavra-passe", text: $c.passwordSip)
                        .textContentType(.password)
                        .onSubmit { if podeEntrar { model.entrar(c) } }
                }

                Section {
                    Button("Entrar") { model.entrar(c) }
                        .frame(maxWidth: .infinity)
                        .disabled(!podeEntrar)
                }

                Section {
                    DisclosureGroup("Opções avançadas", isExpanded: $avancado) {
                        TextField("Servidor", text: $c.servidor)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        TextField("Domínio XMPP (vazio = igual ao servidor)", text: $c.dominioXmpp)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        SecureField("Palavra-passe do chat (vazio = a mesma)", text: $c.passwordXmpp)
                        Toggle("Chat cifrado (TLS)", isOn: $c.usarTls)
                    }
                } footer: {
                    Text("Versão \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")")
                }
            }
            .onAppear { c = model.config }
        }
    }
}
