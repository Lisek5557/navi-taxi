import AVFoundation
import SwiftUI

// MARK: - Ustawienia

struct SettingsView: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var server = Prefs.server
    @State private var key = Prefs.apiKey
    @State private var result = ""
    @State private var name = ""
    @State private var account = SettingsView.accountText()
    @State private var scanning = false

    static func accountText() -> String {
        Prefs.userToken.isEmpty ? "" : "Zalogowano jako \(Prefs.userName)" + (Prefs.userRole == "admin" ? " (admin)" : "")
    }

    var body: some View {
        NavigationView {
            Form {
                Section {
                    Button { scanning = true } label: { Label("Zeskanuj kod QR z serwera", systemImage: "qrcode.viewfinder").font(.headline) }
                } footer: {
                    Text("Na komputerze z serwerem uruchom qr.bat. Kod możesz też zeskanować zwykłym aparatem iPhone'a.")
                }
                Section("Serwer") {
                    TextField("https://xxxx.trycloudflare.com", text: $server)
                        .keyboardType(.URL).textInputAutocapitalization(.never).disableAutocorrection(true)
                    TextField("Klucz API", text: $key)
                        .textInputAutocapitalization(.never).disableAutocorrection(true)
                    Button("Sprawdź połączenie") { save(); test() }
                    if !result.isEmpty { Text(result).font(.callout) }
                }
                Section {
                    if account.isEmpty {
                        TextField("Imię albo kod kierowcy", text: $name)
                        Button("Załóż konto") { register() }.disabled(name.trimmingCharacters(in: .whitespaces).count < 2)
                    } else {
                        Text(account)
                    }
                } header: { Text("Twoje konto") } footer: { Text("Konto jest potrzebne do zgłoszeń na mapie i ograniczeń prędkości.") }
                Section("Informacje") {
                    Text("TaxiNav na iPhone'a \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")")
                    Text("Aplikacja z AltStore działa 7 dni — odświeżaj ją przez AltServer (ten sam Wi-Fi co komputer).")
                        .font(.footnote).foregroundColor(.secondary)
                }
            }
            .navigationTitle("Ustawienia")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Zapisz") { save(); dismiss() } }
            }
            .sheet(isPresented: $scanning) {
                QRScannerView { code in
                    scanning = false
                    if m.applySetup(code) {
                        server = Prefs.server; key = Prefs.apiKey
                        test()
                    } else { result = "❌ To nie jest kod TaxiNav" }
                }
                .ignoresSafeArea()
            }
            .onAppear {
                // Ustawienia mogły przyjść z linku taxinav://setup (aparat).
                server = Prefs.server; key = Prefs.apiKey
            }
        }
    }

    private func normalized() -> String {
        var s = server.trimmingCharacters(in: .whitespaces)
        while s.hasSuffix("/") { s.removeLast() }
        if !s.isEmpty && !s.hasPrefix("http") { s = "https://" + s }
        return s
    }

    private func save() {
        Prefs.server = normalized()
        Prefs.apiKey = key
        m.settingsChanged()
    }

    private func test() {
        result = "Sprawdzam…"
        Task {
            do {
                let api = Api(server: normalized(), key: key.trimmingCharacters(in: .whitespaces), userToken: Prefs.userToken)
                let h = try await api.health()
                _ = try await api.geocode("Bydgoszcz", near: nil)   // sprawdza też klucz
                var s = "✅ Połączono.\n"
                let a = h.int("addresses")
                if a > 0 { s += "✅ Baza adresów: \(a / 1000) tys.\n" }
                s += h.bool("valhalla") ? "✅ Silnik tras gotowy\n" : "⏳ Silnik tras jeszcze buduje mapę Polski\n"
                s += h.bool("traffic") ? "✅ Korki TomTom włączone" : "⚠️ Korki wyłączone (brak TOMTOM_API_KEY na serwerze)"
                result = s
            } catch {
                result = "❌ \(error.localizedDescription)"
            }
        }
    }

    private func register() {
        save()
        Task {
            do {
                let j = try await Api().register(name: name.trimmingCharacters(in: .whitespaces))
                let u = j.obj("user") ?? [:]
                Prefs.userToken = j.str("token")
                Prefs.userName = u.str("name", name)
                Prefs.userRole = u.str("role", "driver")
                account = SettingsView.accountText()
            } catch {
                result = "❌ \(error.localizedDescription)"
            }
        }
    }
}

// MARK: - Skaner QR

struct QRScannerView: UIViewControllerRepresentable {
    let onCode: (String) -> Void
    func makeUIViewController(context: Context) -> ScannerVC { let v = ScannerVC(); v.onCode = onCode; return v }
    func updateUIViewController(_ vc: ScannerVC, context: Context) {}
}

final class ScannerVC: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    private let session = AVCaptureSession()
    private var done = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        guard let dev = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: dev), session.canAddInput(input) else {
            let l = UILabel(); l.text = "Brak dostępu do aparatu"; l.textColor = .white; l.frame = view.bounds; l.textAlignment = .center
            view.addSubview(l); return
        }
        session.addInput(input)
        let out = AVCaptureMetadataOutput()
        if session.canAddOutput(out) {
            session.addOutput(out)
            out.setMetadataObjectsDelegate(self, queue: .main)
            out.metadataObjectTypes = [.qr]
        }
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.frame = view.bounds
        layer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(layer)
        let hint = UILabel()
        hint.text = "Skieruj aparat na kod QR z serwera"
        hint.textColor = .white
        hint.textAlignment = .center
        hint.backgroundColor = UIColor.black.withAlphaComponent(0.5)
        hint.frame = CGRect(x: 0, y: 60, width: view.bounds.width, height: 44)
        hint.autoresizingMask = [.flexibleWidth]
        view.addSubview(hint)
        DispatchQueue.global(qos: .userInitiated).async { [session] in session.startRunning() }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        session.stopRunning()
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !done, let code = (objects.first as? AVMetadataMachineReadableCodeObject)?.stringValue else { return }
        done = true
        session.stopRunning()
        onCode?(code)
    }
}

// MARK: - Historia tras

struct HistoryView: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var trips = Prefs.trips
    @State private var selected: Trip?
    @State private var confirmClear = false

    private static let fmt: DateFormatter = { let f = DateFormatter(); f.dateFormat = "d.MM HH:mm"; return f }()

    var body: some View {
        NavigationView {
            List {
                Section { Text(todaySummary).font(.headline) }
                if trips.isEmpty {
                    Text("Brak tras. Każdy kurs z nawigacją zapisze się tutaj.").foregroundColor(.secondary)
                }
                ForEach(trips) { t in
                    Button { selected = t } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("\(t.from.label) → \(t.to.label)").font(.system(size: 16, weight: .semibold)).foregroundColor(.primary)
                            if !t.stops.isEmpty {
                                Text("przez: " + t.stops.map { $0.label }.joined(separator: ", ")).font(.footnote).foregroundColor(.secondary)
                            }
                            Text("\(HistoryView.fmt.string(from: t.startedAt)) · \(String(format: "%.1f", t.km).replacingOccurrences(of: ".", with: ",")) km · \(t.minutes) min"
                                 + (t.fare.map { " · ≈ \(Fare.zl($0))" } ?? "") + (t.arrived ? "" : " · przerwana"))
                                .font(.footnote).foregroundColor(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("Historia tras")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Zamknij") { dismiss() } }
                ToolbarItem(placement: .destructiveAction) { if !trips.isEmpty { Button("Wyczyść") { confirmClear = true } } }
            }
            .confirmationDialog(selected.map { "\($0.from.label) → \($0.to.label)" } ?? "", isPresented: Binding(get: { selected != nil }, set: { if !$0 { selected = nil } }), titleVisibility: .visible) {
                if let t = selected {
                    Button("Jedź ponownie do: \(t.to.label)") { dismiss(); m.routeFromHistory(t, back: false) }
                    Button("Trasa powrotna do: \(t.from.label)") { dismiss(); m.routeFromHistory(t, back: true) }
                }
                Button("Anuluj", role: .cancel) {}
            }
            .alert("Usunąć całą historię?", isPresented: $confirmClear) {
                Button("Usuń", role: .destructive) { Prefs.trips = []; trips = [] }
                Button("Anuluj", role: .cancel) {}
            }
        }
    }

    private var todaySummary: String {
        let today = trips.filter { Calendar.current.isDateInToday($0.startedAt) }
        let km = today.reduce(0) { $0 + $1.km }
        let zl = today.reduce(0) { $0 + ($1.fare ?? 0) }
        return "Dziś: \(today.count) tras · \(String(format: "%.1f", km).replacingOccurrences(of: ".", with: ",")) km" + (zl > 0 ? " · ≈ \(Fare.zl(zl))" : "")
    }
}

// MARK: - Szacowana cena

struct FareView: View {
    let route: Route?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            ScrollView {
                Text(text).font(.system(size: 17)).frame(maxWidth: .infinity, alignment: .leading).padding()
            }
            .navigationTitle("Szacowana cena")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("OK") { dismiss() } } }
        }
    }

    private var text: String {
        guard let f = route?.fare else { return "Brak danych o cenie." }
        func km(_ v: Double) -> String { String(format: "%.2f", v).replacingOccurrences(of: ".", with: ",") }
        var s = "Opłata początkowa: \(Fare.zl(f.startFee))\n"
        s += "Taryfa \(f.tariffCity) · miejska (\(km(f.cityKm)) km): \(Fare.zl(f.cityCost))\n"
        if f.outKm > 0.01 { s += "Taryfa \(f.tariffOut) · zamiejska (\(km(f.outKm)) km): \(Fare.zl(f.outCost))\n" }
        if f.trafficMin > 0 { s += "Korki / postój (~\(f.trafficMin) min): \(Fare.zl(f.trafficCost))\n" }
        if f.discountPct > 0 { s += "Rabat −\(f.discountPct)% (\(f.discountReason ?? "")): −\(Fare.zl(f.before - f.total))\n" }
        s += "\nRAZEM (taksometr): \(Fare.zl(f.total))\n"
        if let v = f.flatValue { s += "Ryczałt \((f.flatCity ?? "").capitalized): \(Fare.zl(v))\n" }
        s += "\n\(f.night ? "Taryfa nocna / niedziela / święto" : "Taryfa dzienna") · \(f.tariffName)"
        if f.districtA != nil || f.districtB != nil { s += "\nStart: \(f.districtA ?? "—")\nCel: \(f.districtB ?? "—")" }
        s += "\n\nTo szacunek — kwota na taksometrze zależy od faktycznej trasy i postojów."
        return s
    }
}

// MARK: - Kolejność przystanków

struct StopsView: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let all = m.stops + (m.destPlace.map { [$0] } ?? [])
        return NavigationView {
            List {
                ForEach(Array(all.enumerated()), id: \.offset) { i, p in
                    HStack {
                        Text((i == all.count - 1 ? "🏁 " : "\(i + 1). ") + p.label)
                        Spacer()
                        Button { m.moveStop(i, by: -1) } label: { Image(systemName: "arrow.up") }.disabled(i == 0).buttonStyle(.borderless)
                        Button { m.moveStop(i, by: 1) } label: { Image(systemName: "arrow.down") }.disabled(i == all.count - 1).buttonStyle(.borderless)
                        Button { m.removeStop(i) } label: { Image(systemName: "xmark") }.disabled(all.count < 2).buttonStyle(.borderless)
                    }
                }
            }
            .navigationTitle("Kolejność przystanków")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Gotowe") { dismiss() } } }
        }
    }
}

// MARK: - Dodaj ulubione

struct AddFavoriteView: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""

    var body: some View {
        NavigationView {
            Form {
                Section { Text(m.destPlace.map { $0.detail.isEmpty ? $0.label : "\($0.label)\n\($0.detail)" } ?? "") }
                Section("Nazwa") {
                    TextField("np. Baza", text: $name)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(["Baza", "Dworzec", "Lotnisko", "Szpital", "Dom"], id: \.self) { n in
                                Button(n) { name = n }.buttonStyle(.bordered)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Dodaj do szybkich celów")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Anuluj") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Zapisz") { m.addFavorite(name: name); dismiss() } }
            }
            .onAppear { if let p = m.destPlace, p.label != "Punkt na mapie" { name = p.label } }
        }
    }
}
