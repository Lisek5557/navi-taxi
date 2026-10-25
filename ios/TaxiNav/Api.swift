import Foundation

struct ApiError: LocalizedError {
    let message: String
    var code: Int = 0
    var errorDescription: String? { message }
}

enum Prefs {
    private static let d = UserDefaults.standard

    static var server: String {
        get { (d.string(forKey: "server") ?? "").trimmingCharacters(in: CharacterSet(charactersIn: "/ ")) }
        set { d.set(newValue.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/")), forKey: "server") }
    }
    static var apiKey: String {
        get { d.string(forKey: "key") ?? "" }
        set { d.set(newValue.trimmingCharacters(in: .whitespaces), forKey: "key") }
    }
    static var routeMode: String {
        get { d.string(forKey: "routeMode") ?? "optimal" }
        set { d.set(newValue, forKey: "routeMode") }
    }
    /// auto / day / night
    static var mapTheme: String {
        get { d.string(forKey: "mapTheme") ?? "auto" }
        set { d.set(newValue, forKey: "mapTheme") }
    }
    static var trafficLayer: Bool {
        get { d.object(forKey: "trafficLayer") as? Bool ?? true }
        set { d.set(newValue, forKey: "trafficLayer") }
    }
    static var muted: Bool {
        get { d.bool(forKey: "muted") }
        set { d.set(newValue, forKey: "muted") }
    }

    // Konto kierowcy
    static var userToken: String { get { d.string(forKey: "userToken") ?? "" } set { d.set(newValue, forKey: "userToken") } }
    static var userName: String { get { d.string(forKey: "userName") ?? "" } set { d.set(newValue, forKey: "userName") } }
    static var userRole: String { get { d.string(forKey: "userRole") ?? "" } set { d.set(newValue, forKey: "userRole") } }

    /// Anonimowy identyfikator telefonu (do próbek prędkości).
    static var deviceId: String {
        if let v = d.string(forKey: "devId"), !v.isEmpty { return v }
        let id = String(UUID().uuidString.lowercased().replacingOccurrences(of: "-", with: "").prefix(12))
        d.set(id, forKey: "devId")
        return id
    }

    private static func load<T: Decodable>(_ key: String, _ def: T) -> T {
        guard let data = d.data(forKey: key), let v = try? JSONDecoder().decode(T.self, from: data) else { return def }
        return v
    }
    private static func store<T: Encodable>(_ key: String, _ v: T) {
        if let data = try? JSONEncoder().encode(v) { d.set(data, forKey: key) }
    }

    static var recent: [Place] { load("recent", [Place]()) }
    static func addRecent(_ p: Place) {
        let list = [p] + recent.filter { $0.label != p.label || $0.detail != p.detail }
        store("recent", Array(list.prefix(10)))
    }

    static var favorites: [Favorite] {
        get { load("favorites", [Favorite]()) }
        set { store("favorites", newValue) }
    }

    static var trips: [Trip] {
        get { load("trips", [Trip]()) }
        set { store("trips", Array(newValue.prefix(200))) }
    }
}

/// Przejechana trasa: skąd, przez co, dokąd, kiedy, ile km, szacowana cena.
struct Trip: Codable, Identifiable {
    var from: Place
    var to: Place
    var stops: [Place]
    var startedAt: Date
    var endedAt: Date
    var km: Double
    var fare: Double?
    var arrived: Bool
    var id: Date { startedAt }
    var minutes: Int { Int(endedAt.timeIntervalSince(startedAt) / 60) }
}

final class Api {
    let server: String
    let key: String
    let userToken: String

    init(server: String = Prefs.server, key: String = Prefs.apiKey, userToken: String = Prefs.userToken) {
        self.server = server; self.key = key; self.userToken = userToken
    }

    private static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 25
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    private func request(_ method: String, _ path: String, _ body: Any? = nil) async throws -> Any {
        if server.isEmpty { throw ApiError(message: "Ustaw adres serwera w Ustawieniach") }
        guard let url = URL(string: server + path) else { throw ApiError(message: "Zły adres serwera: \(server)") }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue(key, forHTTPHeaderField: "X-Api-Key")
        if !userToken.isEmpty { req.setValue(userToken, forHTTPHeaderField: "X-User-Token") }
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body = body {
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }
        let result: (Data, URLResponse)
        do {
            result = try await Api.session.data(for: req)
        } catch {
            throw ApiError(message: "Brak połączenia z serwerem: \(error.localizedDescription)")
        }
        let (data, resp) = result
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let json = try? JSONSerialization.jsonObject(with: data)
        if !(200...299).contains(code) {
            let msg = (json as? JSON)?.strOrNil("error")
            throw ApiError(message: msg ?? (code == 401 ? "Zły klucz API (Ustawienia)" : "Błąd serwera (\(code))"), code: code)
        }
        guard let j = json else { throw ApiError(message: "Zła odpowiedź serwera") }
        return j
    }

    private func obj(_ method: String, _ path: String, _ body: Any? = nil) async throws -> JSON {
        guard let j = try await request(method, path, body) as? JSON else { throw ApiError(message: "Zła odpowiedź serwera") }
        return j
    }

    private static func q(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? s
    }

    func health() async throws -> JSON { try await obj("GET", "/health") }

    func route(from: LatLon, to: LatLon, heading: Double?, via: [LatLon]) async throws -> RouteResponse {
        var body: JSON = ["from": ["lat": from.lat, "lon": from.lon], "to": ["lat": to.lat, "lon": to.lon]]
        if let h = heading { body["heading"] = h }
        if !via.isEmpty { body["via"] = via.map { ["lat": $0.lat, "lon": $0.lon] } }
        let j = try await obj("POST", "/route", body)
        guard let r = RouteParser.parseResponse(j), !r.routes.isEmpty else { throw ApiError(message: "Nie znaleziono trasy") }
        return r
    }

    func geocode(_ text: String, near: LatLon?, fast: Bool = false) async throws -> [Place] {
        var path = "/geocode?q=" + Api.q(text) + (fast ? "&fast=1" : "")
        if let n = near { path += "&lat=\(n.lat)&lon=\(n.lon)" }
        return try await obj("GET", path).arr("results").map {
            Place(label: $0.str("label"), detail: $0.str("detail"), lat: $0.double("lat"), lon: $0.double("lon"))
        }
    }

    func register(name: String) async throws -> JSON { try await obj("POST", "/register", ["name": name]) }
    func me() async throws -> JSON { try await obj("GET", "/me").obj("user") ?? [:] }

    func reports(near: LatLon?) async throws -> [Report] {
        let path = "/reports" + (near.map { "?lat=\($0.lat)&lon=\($0.lon)" } ?? "")
        return try await obj("GET", path).arr("reports").map {
            Report(id: $0.int("id"), type: $0.str("type"), label: $0.str("label"), lat: $0.double("lat"), lon: $0.double("lon"),
                   up: $0.int("up"), down: $0.int("down"), ageMin: $0.int("ageMin"))
        }
    }
    func addReport(type: String, pos: LatLon, heading: Double?) async throws {
        var b: JSON = ["type": type, "lat": pos.lat, "lon": pos.lon]
        if let h = heading { b["heading"] = h }
        _ = try await request("POST", "/reports", b)
    }
    func voteReport(id: Int, stillThere: Bool) async throws {
        _ = try await request("POST", "/reports/\(id)/vote", ["stillThere": stillThere])
    }

    func reportLimit(pos: LatLon, heading: Double?, value: Int) async throws -> String {
        var b: JSON = ["lat": pos.lat, "lon": pos.lon, "value": value, "note": ""]
        if let h = heading { b["heading"] = h }
        return try await obj("POST", "/limits/report", b).str("status")
    }
    func limitAt(_ points: [LatLon]) async throws -> Int? {
        try await obj("POST", "/limit/at", ["points": points.map { [$0.lat, $0.lon] }]).intOrNil("limit")
    }

    func camerasNear(_ p: LatLon) async throws -> [Camera] {
        try await obj("GET", "/cameras?lat=\(p.lat)&lon=\(p.lon)&r=30").arr("cameras").map {
            Camera(idx: -1, type: $0.str("type", "fixed"), maxspeed: $0.intOrNil("maxspeed"), lat: $0.double("lat"), lon: $0.double("lon"))
        }
    }

    func reverse(_ p: LatLon) async throws -> String? {
        let j = try await obj("GET", "/reverse?lat=\(p.lat)&lon=\(p.lon)")
        return j.obj("address")?.strOrNil("label") ?? j.strOrNil("city")
    }

    func discountZones() async throws -> Data {
        let j = try await request("GET", "/fare/zones")
        return try JSONSerialization.data(withJSONObject: j)
    }

    func sendSpeed(samples: [JSON]) async throws {
        _ = try await request("POST", "/speed", ["dev": Prefs.deviceId, "samples": samples])
    }
}
