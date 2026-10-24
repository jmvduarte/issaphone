import SwiftUI

struct EcraLogin: View {
    @EnvironmentObject private var model: AppModel
    @State private var c = Config()

    var body: some View {
        NavigationStack {
            Form {
                Section("Servidor") {
                    TextField("IP do Issabel", text: $c.servidor)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                Section("Telefone (SIP)") {
                    TextField("Extensão", text: $c.utilizador)
                        .keyboardType(.numberPad)
                    SecureField("Secret da extensão", text: $c.passwordSip)
                }
                Section {
                    TextField("XMPP Domain (vazio = IP)", text: $c.dominioXmpp)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("Password Openfire (vazio = igual ao SIP)", text: $c.passwordXmpp)
                    Toggle("Exigir TLS no chat", isOn: $c.usarTls)
                } header: {
                    Text("Chat (XMPP / Openfire)")
                } footer: {
                    Text("Sem TLS obrigatório, a app aceita o certificado autoassinado do Openfire.")
                }
                Section {
                    Button("Entrar") { model.entrar(c) }
                        .frame(maxWidth: .infinity)
                        .disabled(c.servidor.isEmpty || c.utilizador.isEmpty || c.passwordSip.isEmpty)
                }
            }
            .navigationTitle("IssaFone")
            .onAppear { c = model.config }
        }
    }
}
