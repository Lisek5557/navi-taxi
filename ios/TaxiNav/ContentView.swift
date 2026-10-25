import MapLibre
import SwiftUI

enum Theme {
    static let panel = Color(red: 0x1F / 255, green: 0x23 / 255, blue: 0x2B / 255).opacity(0.96)
    static let card = Color(red: 0x2A / 255, green: 0x2F / 255, blue: 0x39 / 255)
    static let accent = Color(red: 1, green: 0xC6 / 255, blue: 0x29 / 255)
    static let secondary = Color(white: 0.72)
}

struct MapViewRep: UIViewRepresentable {
    let map: MapController
    func makeUIView(context: Context) -> MLNMapView { map.mapView }
    func updateUIView(_ uiView: MLNMapView, context: Context) {}
}

struct ContentView: View {
    @EnvironmentObject var m: AppModel
    @FocusState private var searchFocused: Bool
    @State private var showSettings = false
    @State private var showHistory = false
    @State private var showFare = false
    @State private var showReport = false
    @State private var showLimit = false
    @State private var showStops = false
    @State private var showAddFavorite = false

    var body: some View {
        ZStack {
            MapViewRep(map: m.map).ignoresSafeArea()

            VStack(spacing: 8) {
                if m.navigating { navBanner } else { searchPanel }
                if let t = m.toast {
                    Text(t).font(.subheadline.weight(.semibold)).foregroundColor(.white)
                        .padding(.horizontal, 14).padding(.vertical, 9)
                        .background(Capsule().fill(Color.black.opacity(0.8)))
                        .transition(.opacity)
                }
                Spacer()
            }
            .padding(.horizontal, 10)

            HStack(alignment: .bottom) {
                speedBox
                Spacer()
                sideButtons
            }
            .padding(.horizontal, 10)
            .padding(.bottom, 12)
            .frame(maxHeight: .infinity, alignment: .bottom)
            .padding(.bottom, bottomSpace)

            VStack(spacing: 8) {
                Spacer()
                if let r = m.voteReport { voteBar(r) }
                if m.resp != nil || m.navigating { bottomPanel }
            }
            .padding(.horizontal, 8)

            if m.listening { listeningOverlay }
            if !m.configured { setupPrompt }
        }
        .preferredColorScheme(.dark)
        .animation(.easeInOut(duration: 0.2), value: m.toast)
        .sheet(isPresented: $showSettings, onDismiss: { m.settingsChanged() }) { SettingsView().environmentObject(m) }
        .sheet(isPresented: $showHistory) { HistoryView().environmentObject(m) }
        .sheet(isPresented: $showFare) { FareView(route: m.resp?.chosen) }
        .sheet(isPresented: $showStops) { StopsView().environmentObject(m) }
        .sheet(isPresented: $showAddFavorite) { AddFavoriteView().environmentObject(m) }
        .confirmationDialog("Zgłoś na mapie", isPresented: $showReport, titleVisibility: .visible) {
            ForEach(0..<AppModel.reportTypes.count, id: \.self) { i in Button(AppModel.reportTypes[i].1) { m.addReport(AppModel.reportTypes[i].0) } }
            Button("🚸  Inne ograniczenie prędkości…") { showLimit = true }
            Button("Anuluj", role: .cancel) {}
        }
        .confirmationDialog("Jakie jest tu ograniczenie?", isPresented: $showLimit, titleVisibility: .visible) {
            ForEach(AppModel.limitValues, id: \.self) { v in Button("\(v) km/h") { m.reportLimit(v) } }
            Button("Anuluj", role: .cancel) {}
        }
        .alert("Potrzebne konto", isPresented: $m.needAccount) {
            Button("Ustawienia") { showSettings = true }
            Button("Anuluj", role: .cancel) {}
        } message: {
            Text("Żeby zgłaszać zdarzenia i ograniczenia, załóż konto w Ustawieniach (wystarczy imię lub kod).")
        }
    }

    private var bottomSpace: CGFloat { (m.resp != nil || m.navigating) ? m.bottomInset + 8 : 0 }

    // MARK: - Wyszukiwarka

    private var searchPanel: some View {
        VStack(spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass").foregroundColor(Theme.secondary)
                TextField("Dokąd jedziemy?", text: $m.searchText)
                    .focused($searchFocused)
                    .submitLabel(.search)
                    .disableAutocorrection(true)
                    .onSubmit { m.search() }
                    .onChange(of: m.searchText) { m.searchChanged($0) }
                    .onChange(of: searchFocused) { f in if f && m.searchText.isEmpty { m.showRecent() } }
                    .font(.system(size: 18))
                if !m.searchText.isEmpty {
                    Button { m.searchText = ""; m.results = [] } label: { Image(systemName: "xmark.circle.fill").foregroundColor(Theme.secondary) }
                }
                if m.busy { ProgressView().padding(.horizontal, 4) }
                iconButton("mic.fill") { m.startVoiceInput() }
                iconButton("clock.arrow.circlepath") { showHistory = true }
                iconButton("gearshape.fill") { showSettings = true }
            }
            .padding(.leading, 12).padding(.trailing, 4).frame(height: 52)
            .background(RoundedRectangle(cornerRadius: 14).fill(Theme.panel))

            if !m.results.isEmpty && (searchFocused || !m.resultsAreRecent) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(m.results) { p in
                            Button { searchFocused = false; m.choose(p) } label: {
                                HStack(alignment: .top, spacing: 10) {
                                    Image(systemName: m.resultsAreRecent ? "clock" : "mappin.circle").foregroundColor(Theme.secondary).padding(.top, 2)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(p.label).foregroundColor(.white).font(.system(size: 17))
                                        if !p.detail.isEmpty { Text(p.detail).foregroundColor(Theme.secondary).font(.system(size: 14)) }
                                    }
                                    Spacer()
                                }
                                .padding(.horizontal, 12).padding(.vertical, 10)
                                .contentShape(Rectangle())
                            }
                            Divider().background(Color.white.opacity(0.1))
                        }
                    }
                }
                .frame(maxHeight: 320)
                .background(RoundedRectangle(cornerRadius: 14).fill(Theme.panel))
            } else if m.resp == nil {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(m.favorites) { f in
                            chip("⭐ \(f.name)") { m.pick(Place(label: f.name, detail: f.label, lat: f.lat, lon: f.lon)) }
                                .contextMenu { Button(role: .destructive) { m.removeFavorite(f) } label: { Label("Usuń „\(f.name)”", systemImage: "trash") } }
                        }
                        chip("+ Dodaj ulubione") {
                            if m.destPlace == nil { m.show("Najpierw wybierz cel (wyszukaj albo przytrzymaj palec na mapie)") }
                            else { showAddFavorite = true }
                        }
                    }
                }
            }
        }
        .padding(.top, 4)
    }

    private func chip(_ t: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(t).font(.system(size: 15)).foregroundColor(.white)
                .padding(.horizontal, 14).padding(.vertical, 9)
                .background(Capsule().fill(Theme.panel))
        }
    }

    private func iconButton(_ name: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: name).font(.system(size: 19)).foregroundColor(.white).frame(width: 42, height: 44)
        }
    }

    // MARK: - Baner nawigacji

    private var navBanner: some View {
        let st = m.navState
        let next = st?.nextManeuver
        return VStack(spacing: 6) {
            HStack(spacing: 14) {
                Text(Maneuvers.arrow(next?.type ?? 8)).font(.system(size: 46, weight: .bold)).foregroundColor(.white).frame(minWidth: 56)
                VStack(alignment: .leading, spacing: 2) {
                    Text(st.map { Speech.short($0.distanceToNextM) } ?? "—").font(.system(size: 30, weight: .bold)).foregroundColor(.white)
                    Text(next?.instruction ?? "Jedź zgodnie z trasą").font(.system(size: 18)).foregroundColor(.white).lineLimit(2)
                }
                Spacer()
            }
            if let lanes = st?.lanes {
                HStack(spacing: 4) {
                    ForEach(Array(lanes.lanes.enumerated()), id: \.offset) { _, l in
                        Text(Lanes.label(l)).font(.system(size: 26, weight: l.ok ? .bold : .regular))
                            .foregroundColor(l.ok ? .white : .white.opacity(0.4)).padding(.horizontal, 8)
                    }
                }
                .padding(.vertical, 2).background(RoundedRectangle(cornerRadius: 8).fill(Color.black.opacity(0.35)))
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(red: 0x1B / 255, green: 0x6B / 255, blue: 0x3A / 255)))
        .background(GeometryReader { g in Color.clear.onAppear { m.topInset = g.size.height } })
        .padding(.top, 4)
    }

    // MARK: - Prędkość i przyciski boczne

    private var speedBox: some View {
        VStack(spacing: 6) {
            if let lim = m.speedLimit {
                ZStack {
                    Circle().fill(Color.white)
                    Circle().stroke(Color.red, lineWidth: 7).padding(3.5)
                    Text("\(lim)").font(.system(size: 24, weight: .heavy)).foregroundColor(.black)
                }
                .frame(width: 62, height: 62)
                .scaleEffect(m.overSpeed ? 1.12 : 1)
                .animation(m.overSpeed ? .easeInOut(duration: 0.5).repeatForever() : .default, value: m.overSpeed)
            }
            if let v = m.speedKmh {
                Text("\(v) km/h").font(.system(size: 16, weight: .bold))
                    .foregroundColor(m.overSpeed ? .red : .white)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(Capsule().fill(Theme.panel))
            }
        }
    }

    private var sideButtons: some View {
        VStack(spacing: 10) {
            roundButton("exclamationmark.triangle.fill", tint: Theme.accent) { if m.requireAccount() { showReport = true } }
            roundButton(m.theme == "day" ? "sun.max.fill" : (m.theme == "night" ? "moon.fill" : "circle.lefthalf.filled")) { m.cycleTheme() }
            roundButton("car.2.fill", tint: m.trafficOn ? .white : .white.opacity(0.4)) { m.toggleTraffic() }
            roundButton(m.muted ? "speaker.slash.fill" : "speaker.wave.2.fill") { m.toggleMute() }
            if m.navigating { roundButton(m.overview ? "location.north.line.fill" : "map.fill") { m.toggleOverview() } }
            roundButton((m.navigating && !m.following) ? "location.fill" : "location") { m.recenter() }
        }
    }

    private func roundButton(_ name: String, tint: Color = .white, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: name).font(.system(size: 20)).foregroundColor(tint)
                .frame(width: 52, height: 52).background(Circle().fill(Theme.panel))
        }
    }

    // MARK: - Dolny panel

    private var bottomPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            if m.navigating { navSummary } else if let resp = m.resp { preview(resp) }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 18).fill(Theme.panel))
        .background(GeometryReader { g in
            Color.clear.onAppear { m.bottomInset = g.size.height }.onChange(of: g.size.height) { m.bottomInset = $0 }
        })
        .padding(.bottom, 4)
    }

    private func preview(_ resp: RouteResponse) -> some View {
        let r = resp.chosen
        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                ForEach(RouteModes.all, id: \.self) { mode in
                    let rm = resp.routeFor(mode)
                    Button { m.selectMode(mode) } label: {
                        VStack(spacing: 2) {
                            Text(RouteModes.label(mode)).font(.system(size: 13, weight: .semibold))
                            Text(Speech.minutes(rm.trafficTimeS)).font(.system(size: 17, weight: .bold))
                            Text(Speech.short(Double(rm.distanceM))).font(.system(size: 13))
                            if let f = rm.fare { Text("≈ \(Fare.zlShort(f.shown))").font(.system(size: 13, weight: .semibold)).foregroundColor(Theme.accent) }
                        }
                        .foregroundColor(.white).frame(maxWidth: .infinity).padding(.vertical, 8)
                        .background(RoundedRectangle(cornerRadius: 12).fill(m.mode == mode ? Color(red: 0.2, green: 0.47, blue: 0.96) : Theme.card))
                    }
                }
            }
            HStack {
                Text(stopsLine).font(.system(size: 14)).foregroundColor(Theme.secondary).lineLimit(2)
                Spacer()
                if !m.stops.isEmpty { Button("Kolejność") { showStops = true }.font(.system(size: 14, weight: .semibold)) }
                Button(m.addingStop ? "Wyszukaj przystanek…" : "＋ Przystanek") {
                    m.addingStop.toggle()
                    if m.addingStop { m.show("Wyszukaj albo przytrzymaj palec na mapie, aby dodać przystanek") }
                }
                .font(.system(size: 14, weight: .semibold))
            }
            Button { if r.fare != nil { showFare = true } } label: {
                VStack(alignment: .leading, spacing: 3) {
                    Text("\(Speech.minutes(r.trafficTimeS)) · \(Speech.short(Double(r.distanceM)))" +
                         (r.fare.map { "  ·  ≈ \(Fare.zl($0.shown))" + ($0.flatValue != nil ? " (ryczałt)" : "") } ?? ""))
                        .font(.system(size: 20, weight: .bold)).foregroundColor(.white)
                    Text(detailLine(r)).font(.system(size: 13)).foregroundColor(Theme.secondary).multilineTextAlignment(.leading)
                }
            }
            HStack(spacing: 8) {
                Button { m.clearRoute() } label: { panelLabel("Anuluj", bg: Theme.card) }
                Button { if let u = m.googleMapsURL() { UIApplication.shared.open(u) } } label: { panelLabel("Google Maps", bg: Theme.card) }
                Button { m.startNavigation() } label: {
                    panelLabel(m.countdown.map { "START (\($0))" } ?? "START", bg: Theme.accent, fg: .black)
                }
            }
        }
    }

    private var stopsLine: String {
        let d = m.destPlace?.label ?? ""
        if m.stops.isEmpty { return "Cel: \(d)" }
        return m.stops.enumerated().map { "\($0.offset + 1). \($0.element.label)" }.joined(separator: " → ") + " → 🏁 \(d)"
    }

    private func detailLine(_ r: Route) -> String {
        var s: String
        switch m.mode {
        case "fastest": s = "Najszybsza (z korkami)"
        case "shortest": s = "Najkrótsza (najmniej km)"
        default: s = "Optymalna: krótka, bez straty czasu"
        }
        let delay = r.trafficTimeS - r.baseTimeS
        s += r.hasTraffic ? (delay >= 60 ? " · korki +\(delay / 60) min" : " · bez korków") : " · brak danych o korkach"
        if let f = r.fare {
            s += "\nCena szacunkowa · taryfa \(f.tariffCity)" + (f.outKm > 0.05 ? "/\(f.tariffOut)" : "")
            if f.discountPct > 0 { s += " · rabat \(f.discountPct)%" }
            s += " — dotknij, aby zobaczyć wyliczenie"
        }
        return s
    }

    private var navSummary: some View {
        let st = m.navState
        let eta = Date().addingTimeInterval(st?.remainingS ?? 0)
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 3) {
                Text(st.map { "\(Speech.minutes(Int($0.remainingS))) · \(Speech.short($0.remainingM))" } ?? "Szukam pozycji…")
                    .font(.system(size: 22, weight: .bold)).foregroundColor(.white)
                Text(m.sectionText ?? ("Przyjazd \(f.string(from: eta))" + ((st?.offRoute ?? false) ? " · poza trasą" : "")))
                    .font(.system(size: 14)).foregroundColor(m.sectionText != nil ? Theme.accent : Theme.secondary)
            }
            Spacer()
            Button { m.stopNavigation() } label: { panelLabel("STOP", bg: Color(red: 0.85, green: 0.25, blue: 0.2)).frame(width: 110) }
        }
    }

    private func panelLabel(_ t: String, bg: Color, fg: Color = .white) -> some View {
        Text(t).font(.system(size: 16, weight: .bold)).foregroundColor(fg)
            .frame(maxWidth: .infinity).frame(height: 50)
            .background(RoundedRectangle(cornerRadius: 12).fill(bg))
    }

    private func voteBar(_ r: Report) -> some View {
        HStack {
            Text("\(r.label) — nadal jest?").foregroundColor(.white).font(.system(size: 16, weight: .semibold))
            Spacer()
            Button("Tak") { m.vote(true) }.font(.system(size: 16, weight: .bold)).padding(.horizontal, 10)
            Button("Nie") { m.vote(false) }.font(.system(size: 16, weight: .bold)).padding(.horizontal, 10)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(Theme.panel))
    }

    private var listeningOverlay: some View {
        VStack(spacing: 16) {
            Image(systemName: "mic.fill").font(.system(size: 44)).foregroundColor(Theme.accent)
            Text("Słucham… Powiedz adres, np. „Gdańska 12”").foregroundColor(.white).multilineTextAlignment(.center)
            Text(m.voiceText).font(.system(size: 22, weight: .semibold)).foregroundColor(.white).multilineTextAlignment(.center)
            Button("Gotowe") { m.stopVoiceInput() }.font(.headline)
        }
        .padding(28)
        .background(RoundedRectangle(cornerRadius: 22).fill(Theme.panel))
        .padding(30)
    }

    private var setupPrompt: some View {
        VStack(spacing: 14) {
            Text("Połącz z serwerem TaxiNav").font(.title3.bold()).foregroundColor(.white)
            Text("Na komputerze z serwerem uruchom qr.bat i zeskanuj kod aparatem iPhone'a albo w Ustawieniach.")
                .foregroundColor(Theme.secondary).multilineTextAlignment(.center)
            Button { showSettings = true } label: { panelLabel("Ustawienia", bg: Theme.accent, fg: .black) }
        }
        .padding(24)
        .background(RoundedRectangle(cornerRadius: 20).fill(Theme.panel))
        .padding(24)
    }
}
