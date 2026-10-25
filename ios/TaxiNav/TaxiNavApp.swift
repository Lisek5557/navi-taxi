import SwiftUI

@main
struct TaxiNavApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                // Kod QR z serwera zeskanowany zwykłym aparatem: taxinav://setup?s=...&k=...
                .onOpenURL { url in
                    if !model.applySetup(url.absoluteString) { model.show("Nieprawidłowy kod konfiguracji") }
                }
        }
    }
}
