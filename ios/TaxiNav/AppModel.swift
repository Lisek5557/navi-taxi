import CoreLocation
import SwiftUI
import UIKit

/// Cały stan aplikacji i logika nawigacji (odpowiednik MainActivity z Androida).
@MainActor
final class AppModel: ObservableObject {
    static let fallbackStyle = "https://tiles.openfreemap.org/styles/liberty"
    static let reportTypes: [(String, String)] = [("police", "🚓  Kontrola"), ("accident", "💥  Wypadek"), ("hazard", "⚠️  Utrudnienie"), ("closed", "⛔  Zamknięta ulica")]
    static let limitValues = [10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 140]

    let map = MapController()
    let voice = Voice()
    let location = LocationService()

    // Wyszukiwanie
    @Published var searchText = ""
    @Published var results: [Place] = []
    @Published var resultsAreRecent = false
    @Published var busy = false
    @Published var favorites: [Favorite] = Prefs.favorites

    // Trasa
    @Published var resp: RouteResponse?
    @Published var mode = Prefs.routeMode
    @Published var destPlace: Place?
    @Published var stops: [Place] = []
    @Published var addingStop = false
    @Published var countdown: Int?

    // Nawigacja
    @Published var navigating = false
    @Published var navState: NavState?
    @Published var following = true
    @Published var overview = false
    @Published var sectionText: String?

    // Prędkość
    @Published var speedKmh: Int?
    @Published var speedLimit: Int?
    @Published var overSpeed = false

    // Inne
    @Published var toast: String?
    @Published var voteReport: Report?
    @Published var needAccount = false
    @Published var muted = Prefs.muted
    @Published var trafficOn = Prefs.trafficLayer
    @Published var theme = Prefs.mapTheme
    @Published var listening = false
    @Published var voiceText = ""
    @Published var configured = !Prefs.server.isEmpty

    private(set) var lastLocation: CLLocation?
    private var engine: NavigationEngine?
    private var recentPts: [LatLon] = []
    private var reports: [Report] = []
    private var alertedReports = Set<Int>()
    private var votedReports = Set<Int>()
    private var alertedCameras = Set<String>()
    private var nearCameras: [Camera] = []
    private var lastCamerasFetch: LatLon?
    private var freeLimit: Int?
    private var lastLimitPoll = Date.distantPast
    private var overSince: Date?
    private var lastOverWarn = Date.distantPast
    private var rerouting = false
    private var lastRerouteAt = Date.distantPast
    private var suggestTask: Task<Void, Never>?
    private var countdownTask: Task<Void, Never>?
    private var refreshTask: Task<Void, Never>?
    private var overviewTask: Task<Void, Never>?
    private var pendingPlace: Place?
    private var pendingAutoStart = false
    private var camZoom = 16.0
    private var camBearing = -1.0
    private var lastThemeCheck = Date.distantPast
    private var samples: [JSON] = []
    private var lastSampleAt = Date.distantPast
    private var lastFlushAt = Date()
    private var section: SpeedSection?
    private var sectionStartAlong = 0.0
    private var sectionStartTime = Date()
    private var sectionWarned = false
    private var speech: SpeechInput?
    // Historia
    private var tripStart: Date?
    private var tripFrom: Place?
    private var tripTo: Place?
    private var tripStops: [Place] = []
    private var tripKm = 0.0
    private var tripFare: Double?
    private var lastTripLoc: CLLocation?

    /// Wysokość dolnego panelu i banera (ustawiane przez widok) — do kamery nawigacji.
    var bottomInset: CGFloat = 200
    var topInset: CGFloat = 120

    init() {
        voice.muted = muted
        map.onStyleLoaded = { [weak self] in self?.styleLoaded() }
        map.onUserGesture = { [weak self] in
            guard let self = self, self.navigating else { return }
            self.following = false
            self.overview = false
        }
        map.onLongPress = { [weak self] p in
            self?.pick(Place(label: "Punkt na mapie", detail: String(format: "%.5f, %.5f", p.lat, p.lon), lat: p.lat, lon: p.lon))
        }
        location.onLocation = { [weak self] l in Task { @MainActor in self?.onLocation(l) } }
        map.setStyle(desiredStyleURL())
        location.start()
        startReportsLoop()
    }

    // MARK: - Styl mapy

    var currentTheme: String {
        if theme == "day" || theme == "night" { return theme }
        let h = Calendar.current.component(.hour, from: Date())
        return (h >= 19 || h < 7) ? "night" : "day"
    }

    private func desiredStyleURL() -> String {
        let s = Prefs.server
        return s.isEmpty ? AppModel.fallbackStyle : "\(s)/style/\(currentTheme).json"
    }

    func cycleTheme() {
        theme = theme == "auto" ? "day" : (theme == "day" ? "night" : "auto")
        Prefs.mapTheme = theme
        map.setStyle(desiredStyleURL())
        show(theme == "auto" ? "Mapa: automatycznie dzień/noc" : (theme == "day" ? "Mapa: dzień" : "Mapa: noc"))
    }

    func toggleTraffic() {
        trafficOn.toggle()
        Prefs.trafficLayer = trafficOn
        map.setTrafficLayer(server: Prefs.server, key: Prefs.apiKey, on: trafficOn)
    }

    func toggleMute() {
        muted.toggle()
        Prefs.muted = muted
        voice.muted = muted
        if muted { voice.stop() }
    }

    private func styleLoaded() {
        map.setTrafficLayer(server: Prefs.server, key: Prefs.apiKey, on: trafficOn)
        map.drawRoute(resp, navigating: navigating)
        map.drawStops(stops, destination: destPlace)
        map.drawReports(reports)
        map.drawCameras(route: resp?.chosen, near: nearCameras)
        Task {
            if !Prefs.server.isEmpty, let d = try? await Api().discountZones() { map.drawDiscountZones(d) }
        }
    }

    /// Po zmianie ustawień (adres serwera, klucz).
    func settingsChanged() {
        configured = !Prefs.server.isEmpty
        map.setStyle(desiredStyleURL())
        map.setTrafficLayer(server: Prefs.server, key: Prefs.apiKey, on: trafficOn)
        Task { await refreshReports() }
    }

    // MARK: - Konfiguracja z kodu QR (taxinav://setup?s=...&k=...)

    @discardableResult
    func applySetup(_ raw: String) -> Bool {
        guard let c = URLComponents(string: raw.trimmingCharacters(in: .whitespaces)), c.scheme == "taxinav", c.host == "setup" else { return false }
        let s = c.queryItems?.first { $0.name == "s" }?.value?.trimmingCharacters(in: .whitespaces) ?? ""
        let k = c.queryItems?.first { $0.name == "k" }?.value?.trimmingCharacters(in: .whitespaces) ?? ""
        guard s.hasPrefix("http"), !k.isEmpty else { return false }
        Prefs.server = s
        Prefs.apiKey = k
        settingsChanged()
        show("Zapisano ustawienia z kodu QR")
        return true
    }

    // MARK: - Lokalizacja

    private func onLocation(_ loc: CLLocation) {
        let first = lastLocation == nil
        lastLocation = loc
        let pos = loc.latLon
        if first {
            if !navigating { map.center(pos, zoom: 14) }
            if let p = pendingPlace { pendingPlace = nil; pick(p) }
        }
        if Date().timeIntervalSince(lastThemeCheck) > 60 {
            lastThemeCheck = Date()
            map.setStyle(desiredStyleURL())
        }
        collectSpeedSample(loc)
        recentPts.append(pos)
        if recentPts.count > 8 { recentPts.removeFirst(recentPts.count - 8) }

        if navigating, let e = engine {
            if loc.horizontalAccuracy < 30 {
                if let l = lastTripLoc { tripKm += loc.distance(from: l) / 1000 }
                lastTripLoc = loc
            }
            let st = e.update(pos, speedMps: max(0, loc.speed), accuracyM: loc.horizontalAccuracy)
            navState = st
            updateNavCamera(loc, st, e)
            checkReportsOnRoute(e, st)
            checkCamerasOnRoute(e, st, loc)
            updateSpeed(loc, limit: st.speedLimit)
            if st.arrived { stopNavigation(arrived: true); return }
            if st.offRoute && !rerouting && Date().timeIntervalSince(lastRerouteAt) > 8 { reroute(silent: false) }
        } else {
            pollFreeLimit(loc)
            checkReportsAhead(loc)
            checkCamerasAhead(loc)
            updateSpeed(loc, limit: freeLimit)
        }
    }

    private func updateSpeed(_ loc: CLLocation, limit: Int?) {
        let kmh = loc.speed >= 0 ? Int(loc.speed * 3.6) : nil
        speedKmh = kmh
        speedLimit = limit
        guard let limit = limit, let v = kmh else { overSpeed = false; return }
        if v > limit + 5 {
            if overSince == nil { overSince = Date() }
            if let s = overSince, Date().timeIntervalSince(s) > 3 {
                overSpeed = true
                if Date().timeIntervalSince(lastOverWarn) > 60 {
                    lastOverWarn = Date()
                    voice.beep(long: true)
                    voice.say("Ograniczenie do \(limit).")
                }
            }
        } else {
            overSince = nil
            overSpeed = false
        }
    }

    private func pollFreeLimit(_ loc: CLLocation) {
        guard loc.speed > 3, recentPts.count >= 3, Date().timeIntervalSince(lastLimitPoll) > 15, !Prefs.server.isEmpty else { return }
        lastLimitPoll = Date()
        let pts = recentPts
        Task { freeLimit = try? await Api().limitAt(pts) }
    }

    private func collectSpeedSample(_ loc: CLLocation) {
        let now = Date()
        if loc.speed >= 0, loc.course >= 0, loc.horizontalAccuracy < 30, now.timeIntervalSince(lastSampleAt) >= 5 {
            lastSampleAt = now
            samples.append(["lat": loc.coordinate.latitude, "lon": loc.coordinate.longitude, "speed": loc.speed,
                            "heading": loc.course, "t": Int(now.timeIntervalSince1970 * 1000)])
        }
        if !samples.isEmpty, now.timeIntervalSince(lastFlushAt) > 60, !Prefs.server.isEmpty {
            lastFlushAt = now
            let batch = samples
            samples = []
            Task { try? await Api().sendSpeed(samples: batch) }
        }
    }

    // MARK: - Wyszukiwanie

    func searchChanged(_ q: String) {
        suggestTask?.cancel()
        let t = q.trimmingCharacters(in: .whitespaces)
        if t.count < 2 { showRecent(); return }
        suggestTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            if Task.isCancelled { return }
            if let found = try? await Api().geocode(t, near: lastLocation?.latLon, fast: true), !Task.isCancelled,
               searchText.trimmingCharacters(in: .whitespaces) == t {
                results = found
                resultsAreRecent = false
            }
        }
    }

    func showRecent() {
        results = navigating ? [] : Prefs.recent
        resultsAreRecent = true
    }

    func search() {
        let q = searchText.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        suggestTask?.cancel()
        busy = true
        Task {
            defer { busy = false }
            do {
                let found = try await Api().geocode(q, near: lastLocation?.latLon)
                if found.isEmpty { show("Nie znaleziono: \(q)") }
                results = found
                resultsAreRecent = false
            } catch { show(error.localizedDescription) }
        }
    }

    func choose(_ p: Place) {
        Prefs.addRecent(p)
        results = []
        searchText = p.label
        hideKeyboard()
        pick(p)
    }

    /// Wybrane miejsce: cel albo (w trybie „+ Przystanek”) przystanek przed celem.
    func pick(_ p: Place) {
        guard lastLocation != nil else { pendingPlace = p; show("Czekam na GPS…"); return }
        if addingStop && destPlace != nil {
            addingStop = false
            if stops.count >= 8 { show("Maksymalnie 8 przystanków"); return }
            stops.append(p)
        } else {
            addingStop = false
            if resp == nil { stops = [] }
            destPlace = p
        }
        requestRoute(fit: true)
    }

    func requestRoute(fit: Bool) {
        guard let loc = lastLocation, let dest = destPlace else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                var r = try await Api().route(from: loc.latLon, to: dest.pos, heading: loc.courseOrNil, via: stops.map { $0.pos })
                r.mode = mode
                resp = r
                drawAll()
                if fit { fitRoute(r.chosen) }
                if pendingAutoStart { pendingAutoStart = false; startCountdown(r.chosen) }
            } catch { show(error.localizedDescription) }
        }
    }

    private func drawAll() {
        map.drawRoute(resp, navigating: navigating)
        map.drawStops(stops, destination: destPlace)
        map.drawCameras(route: resp?.chosen, near: nearCameras)
    }

    private func fitRoute(_ r: Route) {
        map.fit(r.points, insets: UIEdgeInsets(top: topInset + 40, left: 50, bottom: bottomInset + 40, right: 50))
    }

    func selectMode(_ m: String) {
        cancelAutoStart()
        mode = m
        Prefs.routeMode = m
        resp?.mode = m
        drawAll()
    }

    func clearRoute() {
        cancelAutoStart()
        resp = nil; destPlace = nil; engine = nil; stops = []; addingStop = false
        searchText = ""
        drawAll()
    }

    func moveStop(_ from: Int, by delta: Int) {
        var all = stops + (destPlace.map { [$0] } ?? [])
        let to = from + delta
        guard all.indices.contains(from), all.indices.contains(to) else { return }
        all.swapAt(from, to)
        destPlace = all.last
        stops = Array(all.dropLast())
        requestRoute(fit: false)
    }

    func removeStop(_ i: Int) {
        var all = stops + (destPlace.map { [$0] } ?? [])
        guard all.indices.contains(i), all.count > 1 else { return }
        all.remove(at: i)
        destPlace = all.last
        stops = Array(all.dropLast())
        requestRoute(fit: false)
    }

    func googleMapsURL() -> URL? {
        guard let d = destPlace else { return nil }
        var s = "https://www.google.com/maps/dir/?api=1&destination=\(d.lat),\(d.lon)&travelmode=driving"
        if !stops.isEmpty {
            let w = stops.map { "\($0.lat),\($0.lon)" }.joined(separator: "|")
            s += "&waypoints=" + (w.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? w)
        }
        return URL(string: s)
    }

    // MARK: - Ulubione

    func addFavorite(name: String) {
        guard let p = destPlace else { return }
        let n = name.trimmingCharacters(in: .whitespaces).isEmpty ? p.label : name.trimmingCharacters(in: .whitespaces)
        favorites = favorites.filter { $0.name != n } + [Favorite(name: n, label: p.detail.isEmpty ? p.label : "\(p.label), \(p.detail)", lat: p.lat, lon: p.lon)]
        Prefs.favorites = favorites
        show("Dodano „\(n)”")
    }

    func removeFavorite(_ f: Favorite) {
        favorites.removeAll { $0 == f }
        Prefs.favorites = favorites
    }

    // MARK: - Głos

    func startVoiceInput() {
        SpeechInput.requestPermissions { [weak self] ok in
            guard let self = self else { return }
            guard ok else { self.show("Zezwól na mikrofon i rozpoznawanie mowy w Ustawieniach iPhone'a"); return }
            self.voice.stop()
            let s = SpeechInput()
            s.onPartial = { [weak self] t in self?.voiceText = t }
            s.onDone = { [weak self] t in
                guard let self = self else { return }
                self.listening = false
                self.speech = nil
                if let t = t { self.onVoiceDestination(t) }
            }
            do {
                self.voiceText = ""
                try s.start()
                self.speech = s
                self.listening = true
            } catch { self.show(error.localizedDescription) }
        }
    }

    func stopVoiceInput() { speech?.finish() }

    /// „jedź do Gdańskiej 12” -> pierwszy wynik -> trasa -> automatyczny start za 5 s.
    private func onVoiceDestination(_ raw: String) {
        let q = VoiceQuery.clean(raw)
        searchText = q
        Task {
            do {
                let found = try await Api().geocode(q, near: lastLocation?.latLon)
                guard let top = found.first else {
                    voice.say("Nie znalazłem adresu \(q).")
                    show("Nie znaleziono: \(q)")
                    return
                }
                Prefs.addRecent(top)
                searchText = top.label
                results = []
                pendingAutoStart = true
                pick(top)
            } catch { show(error.localizedDescription) }
        }
    }

    private func startCountdown(_ r: Route) {
        voice.say("Trasa do \(destPlace?.label ?? "celu"), \(Speech.minutes(r.trafficTimeS)). Ruszam za 5 sekund.")
        countdownTask?.cancel()
        countdownTask = Task {
            for i in stride(from: 5, through: 1, by: -1) {
                countdown = i
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if Task.isCancelled { return }
            }
            countdown = nil
            if !navigating && resp != nil { startNavigation() }
        }
    }

    func cancelAutoStart() {
        countdownTask?.cancel()
        countdownTask = nil
        countdown = nil
        pendingAutoStart = false
    }

    // MARK: - Nawigacja

    func startNavigation() {
        guard let r = resp else { return }
        cancelAutoStart()
        navigating = true
        alertedReports = []
        alertedCameras = []
        section = nil
        sectionText = nil
        searchText = ""        // wpisany adres znika po starcie
        results = []
        hideKeyboard()
        beginTrip(r.chosen)
        engine = NavigationEngine(route: r.chosen) { [weak self] in self?.voice.say($0) }
        UIApplication.shared.isIdleTimerDisabled = true
        location.setBackground(true)
        camBearing = -1
        following = true
        overview = false
        drawAll()
        if let l = lastLocation { onLocation(l) }
        refreshTask?.cancel()
        refreshTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 180_000_000_000)
                if Task.isCancelled { return }
                if navigating && !rerouting { reroute(silent: true) }
            }
        }
    }

    func stopNavigation(arrived: Bool = false) {
        saveTrip(arrived: arrived)
        navigating = false
        navState = nil
        sectionText = nil
        refreshTask?.cancel()
        overviewTask?.cancel()
        overview = false
        UIApplication.shared.isIdleTimerDisabled = false
        location.setBackground(false)
        map.flatten()
        if arrived {
            show("Dotarłeś do celu")
            clearRoute()
        } else {
            voice.stop()
            drawAll()
        }
    }

    /// silent=false: zjechałeś z trasy. silent=true: odświeżenie korków (zmiana tylko gdy wyraźnie szybciej).
    private func reroute(silent: Bool) {
        guard let loc = lastLocation, let dest = destPlace else { return }
        rerouting = true
        lastRerouteAt = Date()
        if !silent { voice.say("Przeliczam trasę.") }
        if let st = navState, let e = engine {
            let passed = e.route.maneuvers.filter { $0.stop != nil && $0.begin <= st.traveledIndex }.count
            if passed > 0 { stops.removeFirst(min(passed, stops.count)) }
        }
        Task {
            defer { rerouting = false }
            do {
                var r = try await Api().route(from: loc.latLon, to: dest.pos, heading: loc.courseOrNil, via: stops.map { $0.pos })
                r.mode = mode
                guard navigating else { return }
                let saves = (navState?.remainingS ?? .greatestFiniteMagnitude) - Double(r.chosen.trafficTimeS)
                if silent && saves >= 120 { voice.say("Znalazłem szybszą trasę, oszczędzasz \(Int(saves / 60)) minut.") }
                resp = r
                engine = NavigationEngine(route: r.chosen, announceStart: !silent) { [weak self] in self?.voice.say($0) }
                navState = nil
                drawAll()
            } catch {
                if !silent { show(error.localizedDescription) }
            }
        }
    }

    private func updateNavCamera(_ loc: CLLocation, _ st: NavState, _ e: NavigationEngine, duration: TimeInterval = 0.95) {
        guard following, !overview else { return }
        let kmh = loc.kmh
        camZoom += (NavCamera.zoomFor(speedKmh: kmh, distToManeuverM: st.distanceToNextM) - camZoom) * 0.35
        let brg = (st.offRoute ? loc.courseOrNil : nil) ?? Geo.bearing(st.snapped, e.pointAt(st.alongM + NavCamera.lookAheadM(kmh)))
        if camBearing < 0 { camBearing = brg }
        else if kmh >= 4 { camBearing = NavCamera.smoothAngle(camBearing, brg, 0.55) }
        let pos = (!st.offRoute && st.distanceFromRouteM < 25) ? st.snapped : loc.latLon
        let h = map.mapView.bounds.height
        map.follow(pos, zoom: camZoom, bearing: camBearing, pitch: 45, topPad: h * 0.5, bottomPad: bottomInset + 16, duration: duration)
    }

    func recenter() {
        if navigating {
            following = true
            overview = false
            overviewTask?.cancel()
            if let l = lastLocation, let st = navState, let e = engine { updateNavCamera(l, st, e, duration: 0.6) }
        } else if let l = lastLocation {
            map.center(l.latLon, zoom: 15)
        }
    }

    /// Podgląd całej pozostałej trasy z góry; po 10 s powrót do jazdy.
    func toggleOverview() {
        if overview { recenter(); return }
        guard let e = engine else { return }
        var pts = e.remainingPoints(from: navState?.traveledIndex ?? 0)
        if let l = lastLocation { pts.append(l.latLon) }
        guard pts.count > 1 else { return }
        overview = true
        following = false
        map.fit(pts, insets: UIEdgeInsets(top: topInset + 40, left: 60, bottom: bottomInset + 40, right: 90))
        overviewTask?.cancel()
        overviewTask = Task {
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            if !Task.isCancelled && overview && navigating { recenter() }
        }
    }

    // MARK: - Zgłoszenia

    func requireAccount() -> Bool {
        if !Prefs.userToken.isEmpty { return true }
        needAccount = true
        return false
    }

    func addReport(_ type: String) {
        guard let l = lastLocation else { show("Czekam na GPS…"); return }
        Task {
            do {
                try await Api().addReport(type: type, pos: l.latLon, heading: l.courseOrNil)
                show("Dzięki! Zgłoszenie widzą inni kierowcy")
                await refreshReports()
            } catch { show(error.localizedDescription) }
        }
    }

    func reportLimit(_ v: Int) {
        guard let l = lastLocation else { show("Czekam na GPS…"); return }
        Task {
            do {
                let st = try await Api().reportLimit(pos: l.latLon, heading: l.courseOrNil, value: v)
                show(st == "approved" ? "Zapisano: \(v) km/h" : "Wysłano do zatwierdzenia przez admina: \(v) km/h")
            } catch { show(error.localizedDescription) }
        }
    }

    private func startReportsLoop() {
        Task {
            while true {
                await refreshReports()
                try? await Task.sleep(nanoseconds: 60_000_000_000)
            }
        }
    }

    private func refreshReports() async {
        guard !Prefs.server.isEmpty else { return }
        if let r = try? await Api().reports(near: lastLocation?.latLon) {
            reports = r
            map.drawReports(r)
        }
    }

    private func checkReportsOnRoute(_ e: NavigationEngine, _ st: NavState) {
        for r in reports {
            let pr = e.project(LatLon(lat: r.lat, lon: r.lon))
            if pr.dist > 40 { continue }
            let ahead = pr.along - st.alongM
            if (0...700).contains(ahead) && !alertedReports.contains(r.id) {
                alertedReports.insert(r.id)
                voice.say("Uwaga, \(r.label.lowercased()), \(Speech.lowerFirst(Speech.inDistance(ahead))).")
            }
            if (-40...25).contains(ahead) { askVote(r) }
        }
    }

    private func checkReportsAhead(_ loc: CLLocation) {
        guard let course = loc.courseOrNil, loc.speed > 4 else { return }
        let me = loc.latLon
        for r in reports {
            let p = LatLon(lat: r.lat, lon: r.lon)
            let d = Geo.distance(me, p)
            if d < 400 && Geo.angleDiff(Geo.bearing(me, p), course) < 30 && !alertedReports.contains(r.id) {
                alertedReports.insert(r.id)
                voice.say("Uwaga, \(r.label.lowercased()), \(Speech.lowerFirst(Speech.inDistance(d))).")
            }
            if d < 30 { askVote(r) }
        }
    }

    private func askVote(_ r: Report) {
        guard !votedReports.contains(r.id), voteReport == nil, !Prefs.userToken.isEmpty else { return }
        votedReports.insert(r.id)
        voteReport = r
        Task {
            try? await Task.sleep(nanoseconds: 15_000_000_000)
            if voteReport?.id == r.id { voteReport = nil }
        }
    }

    func vote(_ stillThere: Bool) {
        guard let r = voteReport else { return }
        voteReport = nil
        Task {
            try? await Api().voteReport(id: r.id, stillThere: stillThere)
            await refreshReports()
        }
    }

    // MARK: - Fotoradary i odcinkowy pomiar

    private func camWarn(_ kmh: Double) -> Double { kmh > 70 ? 900 : 500 }
    private func camLabel(_ c: Camera) -> String {
        (c.type == "red_light" ? "Fotoradar na światłach" : "Fotoradar") + (c.maxspeed.map { ", ograniczenie \($0)" } ?? "")
    }

    private func checkCamerasOnRoute(_ e: NavigationEngine, _ st: NavState, _ loc: CLLocation) {
        let r = e.route
        let kmh = loc.kmh
        for c in r.cameras {
            let ahead = e.alongAt(c.idx) - st.alongM
            let key = "r\(c.idx)-\(r.distanceM)"
            if (0...camWarn(kmh)).contains(ahead) && !alertedCameras.contains(key) {
                alertedCameras.insert(key)
                voice.beep()
                voice.say("\(camLabel(c)), \(Speech.lowerFirst(Speech.inDistance(ahead))).")
            }
        }
        if let s = section {
            let done = st.alongM - sectionStartAlong
            let secs = Date().timeIntervalSince(sectionStartTime)
            let avg = secs > 5 ? done / secs * 3.6 : 0
            let left = e.alongAt(s.toIdx) - st.alongM
            sectionText = "⏱ Odcinek: śr. \(Int(avg)) km/h" + (s.maxspeed.map { " / \($0)" } ?? "") + " · do końca \(Speech.short(max(0, left)))"
            if let lim = s.maxspeed, avg > Double(lim + 3), secs > 20, !sectionWarned {
                sectionWarned = true
                voice.beep(long: true)
                voice.say("Średnia prędkość na odcinku za wysoka, zwolnij.")
            }
            if left <= 0 || st.offRoute {
                voice.say("Koniec odcinkowego pomiaru" + ((s.maxspeed != nil && avg > 0) ? ", średnia \(Int(avg))." : "."))
                section = nil
                sectionText = nil
            }
        } else {
            for s in r.sections {
                let toStart = e.alongAt(s.fromIdx) - st.alongM
                let key = "s\(s.fromIdx)-\(r.distanceM)"
                if (0...camWarn(kmh)).contains(toStart) && !alertedCameras.contains(key) {
                    alertedCameras.insert(key)
                    voice.say("Odcinkowy pomiar prędkości, \(Speech.lowerFirst(Speech.inDistance(toStart)))" + (s.maxspeed.map { ", ograniczenie \($0)" } ?? "") + ".")
                }
                if st.alongM >= e.alongAt(s.fromIdx) && st.alongM < e.alongAt(s.toIdx) {
                    section = s; sectionStartAlong = st.alongM; sectionStartTime = Date(); sectionWarned = false
                    voice.say("Początek odcinkowego pomiaru.")
                    break
                }
            }
        }
    }

    private func checkCamerasAhead(_ loc: CLLocation) {
        let me = loc.latLon
        if (lastCamerasFetch.map { Geo.distance($0, me) > 10_000 } ?? true) && !Prefs.server.isEmpty {
            lastCamerasFetch = me
            Task {
                if let c = try? await Api().camerasNear(me) {
                    nearCameras = c
                    if resp == nil { map.drawCameras(route: nil, near: c) }
                }
            }
        }
        guard let course = loc.courseOrNil, loc.speed > 5 else { return }
        for c in nearCameras {
            let p = LatLon(lat: c.lat, lon: c.lon)
            let d = Geo.distance(me, p)
            let key = "n\(c.lat),\(c.lon)"
            if d < camWarn(loc.kmh) && Geo.angleDiff(Geo.bearing(me, p), course) < 25 && !alertedCameras.contains(key) {
                alertedCameras.insert(key)
                voice.beep()
                voice.say("\(camLabel(c)), \(Speech.lowerFirst(Speech.inDistance(d))).")
            }
            if d > 2000 { alertedCameras.remove(key) }
        }
    }

    // MARK: - Historia tras

    private func beginTrip(_ r: Route) {
        tripStart = Date()
        tripKm = 0
        lastTripLoc = lastLocation
        tripTo = destPlace
        tripStops = stops
        tripFare = r.fare?.shown
        guard let l = lastLocation else { tripFrom = nil; return }
        let from = Place(label: "Start", detail: "", lat: l.coordinate.latitude, lon: l.coordinate.longitude)
        tripFrom = from
        Task {
            if let n = try? await Api().reverse(from.pos) {
                var f = from; f.label = n
                tripFrom = f
            }
        }
    }

    private func saveTrip(arrived: Bool) {
        if let s = tripStart, let from = tripFrom, let to = tripTo, tripKm > 0.2 {
            Prefs.trips = [Trip(from: from, to: to, stops: tripStops, startedAt: s, endedAt: Date(), km: tripKm, fare: tripFare, arrived: arrived)] + Prefs.trips
        }
        tripStart = nil
    }

    /// Z historii: „Jedź ponownie” (ten sam cel) albo „Trasa powrotna” (do miejsca startu).
    func routeFromHistory(_ t: Trip, back: Bool) {
        if navigating { stopNavigation() }
        resp = nil
        stops = back ? Array(t.stops.reversed()) : t.stops
        destPlace = nil
        pick(back ? t.from : t.to)
    }

    // MARK: - Pomocnicze

    func show(_ msg: String) {
        toast = msg
        Task {
            try? await Task.sleep(nanoseconds: 3_500_000_000)
            if toast == msg { toast = nil }
        }
    }

    func hideKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
}
