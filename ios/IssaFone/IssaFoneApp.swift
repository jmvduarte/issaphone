import SwiftUI

@main
struct IssaFoneApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            Group {
                if model.ligado {
                    EcraPrincipal()
                } else {
                    EcraLogin()
                }
            }
            .environmentObject(model)
            .environmentObject(model.sip)
            .environmentObject(model.xmpp)
        }
    }
}
