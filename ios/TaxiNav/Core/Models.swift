import Foundation

struct Maneuver {
    var type: Int
    var instruction: String
    var verbalAlert: String?
    var verbalPre: String
    var begin: Int
    var lengthM: Int
    var streets: [String]
    /// Numer przystanku pośredniego (nil = zwykły manewr lub cel końcowy).
    var stop: Int?
}

struct LimitRange { var from: Int; var to: Int; var limit: Int; var override: Bool }
struct Lane { var directions: [String]; var ok: Bool }
struct LaneInfo { var index: Int; var lanes: [Lane] }
struct TrafficRange { var from: Int; var to: Int; var level: Int }

struct Report: Identifiable {
    var id: Int; var type: String; var label: String
    var lat: Double; var lon: Double
    var up: Int; var down: Int; var ageMin: Int
}

struct Camera {
    var idx: Int; var type: String; var maxspeed: Int?
    var lat: Double = 0; var lon: Double = 0
}

struct SpeedSection { var fromIdx: Int; var toIdx: Int; var lengthM: Int; var maxspeed: Int?; var name: String? }

struct Fare {
    var total: Double, before: Double, startFee: Double
    var cityKm: Double, outKm: Double, cityCost: Double, outCost: Double
    var trafficMin: Int, trafficCost: Double
    var night: Bool, tariffCity: Int, tariffOut: Int
    var discountPct: Int, discountReason: String?
    var flatCity: String?, flatValue: Double?
    var districtA: String?, districtB: String?, tariffName: String

    static func zl(_ v: Double) -> String { String(format: "%.2f zł", v).replacingOccurrences(of: ".", with: ",") }
    static func zlShort(_ v: Double) -> String { "\(Int(v.rounded())) zł" }
    /// Kwota do pokazania: ryczałt, jeśli jest, inaczej taksometr.
    var shown: Double { flatValue ?? total }
}

final class Route {
    let points: [LatLon]
    let maneuvers: [Maneuver]
    let distanceM: Int
    let baseTimeS: Int
    let trafficTimeS: Int
    let hasTraffic: Bool
    let traffic: [TrafficRange]
    let limits: [LimitRange]
    let lanes: [LaneInfo]
    let stops: Int
    let fare: Fare?
    let cameras: [Camera]
    let sections: [SpeedSection]

    init(points: [LatLon], maneuvers: [Maneuver], distanceM: Int, baseTimeS: Int, trafficTimeS: Int,
         hasTraffic: Bool = false, traffic: [TrafficRange] = [], limits: [LimitRange] = [], lanes: [LaneInfo] = [],
         stops: Int = 0, fare: Fare? = nil, cameras: [Camera] = [], sections: [SpeedSection] = []) {
        self.points = points; self.maneuvers = maneuvers; self.distanceM = distanceM
        self.baseTimeS = baseTimeS; self.trafficTimeS = trafficTimeS; self.hasTraffic = hasTraffic
        self.traffic = traffic; self.limits = limits; self.lanes = lanes; self.stops = stops
        self.fare = fare; self.cameras = cameras; self.sections = sections
    }
}

enum RouteModes {
    static let all = ["optimal", "fastest", "shortest"]
    static func label(_ m: String) -> String {
        switch m { case "fastest": return "Najszybsza"; case "shortest": return "Najkrótsza"; default: return "Optymalna" }
    }
}

struct RouteResponse {
    var routes: [Route]
    /// Indeksy w `routes` dla trybów: fastest, shortest, optimal.
    var pick: [String: Int]
    var mode: String = "optimal"

    func routeFor(_ m: String) -> Route {
        let i = pick[m] ?? 0
        return routes.indices.contains(i) ? routes[i] : routes[0]
    }
    var chosen: Route { routeFor(mode) }
    var alternatives: [Route] { let c = chosen; return routes.filter { $0 !== c } }
}

struct Place: Codable, Equatable, Identifiable {
    var label: String
    var detail: String
    var lat: Double
    var lon: Double
    var id: String { "\(label)|\(detail)|\(lat),\(lon)" }
    var pos: LatLon { LatLon(lat: lat, lon: lon) }
}

struct Favorite: Codable, Equatable, Identifiable {
    var name: String
    var label: String
    var lat: Double
    var lon: Double
    var id: String { name }
}

enum Lanes {
    static func arrow(_ dir: String) -> String {
        switch dir {
        case "left": return "←"; case "slight left": return "↖"; case "sharp left": return "↙"
        case "right": return "→"; case "slight right": return "↗"; case "sharp right": return "↘"
        case "uturn": return "↶"; case "merge": return "⇡"
        default: return "↑"
        }
    }
    static func label(_ l: Lane) -> String { (l.directions.isEmpty ? ["straight"] : l.directions).map(arrow).joined() }
}

enum Maneuvers {
    static func arrow(_ type: Int) -> String {
        switch type {
        case 4, 5, 6: return "🏁"
        case 9, 20, 23: return "↗"
        case 10, 18: return "→"
        case 11: return "↘"
        case 12, 13: return "↶"
        case 14: return "↙"
        case 15, 19: return "←"
        case 16, 21, 24: return "↖"
        case 26, 27: return "⟳"
        default: return "↑"
        }
    }
}

// MARK: - Parsowanie JSON (luźne, jak optX w Androidzie)

typealias JSON = [String: Any]

extension Dictionary where Key == String, Value == Any {
    func int(_ k: String, _ d: Int = 0) -> Int {
        if let v = self[k] as? Int { return v }
        if let v = self[k] as? Double { return Int(v) }
        if let v = self[k] as? NSNumber { return v.intValue }
        return d
    }
    func intOrNil(_ k: String) -> Int? {
        if self[k] == nil || self[k] is NSNull { return nil }
        if let v = self[k] as? NSNumber { return v.intValue }
        return nil
    }
    func double(_ k: String, _ d: Double = 0) -> Double {
        if let v = self[k] as? NSNumber { return v.doubleValue }
        return d
    }
    func doubleOrNil(_ k: String) -> Double? { (self[k] as? NSNumber)?.doubleValue }
    func bool(_ k: String) -> Bool { (self[k] as? Bool) ?? ((self[k] as? NSNumber)?.boolValue ?? false) }
    func str(_ k: String, _ d: String = "") -> String { (self[k] as? String) ?? d }
    func strOrNil(_ k: String) -> String? {
        guard let s = self[k] as? String, !s.isEmpty, s != "null" else { return nil }
        return s
    }
    func arr(_ k: String) -> [JSON] { (self[k] as? [Any])?.compactMap { $0 as? JSON } ?? [] }
    func obj(_ k: String) -> JSON? { self[k] as? JSON }
}

enum RouteParser {
    static func parseResponse(_ j: JSON) -> RouteResponse? {
        let arr = j.arr("routes")
        if !arr.isEmpty, let p = j.obj("pick") {
            let routes = arr.map(parseRoute)
            var pick: [String: Int] = [:]
            for m in RouteModes.all { pick[m] = min(max(0, p.int(m)), routes.count - 1) }
            return RouteResponse(routes: routes, pick: pick)
        }
        guard let c = j.obj("chosen") else { return nil }
        let routes = [parseRoute(c)] + j.arr("alternatives").map(parseRoute)
        return RouteResponse(routes: routes, pick: ["optimal": 0, "fastest": 0, "shortest": 0])
    }

    static func parseRoute(_ o: JSON) -> Route {
        let mans = o.arr("maneuvers").map { m in
            Maneuver(type: m.int("type"), instruction: m.str("instruction"), verbalAlert: m.strOrNil("verbalAlert"),
                     verbalPre: m.strOrNil("verbalPre") ?? m.str("instruction"), begin: m.int("begin"),
                     lengthM: m.int("lengthM"), streets: (m["streets"] as? [String]) ?? [], stop: m.intOrNil("stop"))
        }
        let fare: Fare? = o.obj("fare").map { f in
            let flat = f.obj("flat")
            return Fare(total: f.double("total"), before: f.double("before"), startFee: f.double("startFee"),
                        cityKm: f.double("cityKm"), outKm: f.double("outKm"), cityCost: f.double("cityCost"), outCost: f.double("outCost"),
                        trafficMin: f.int("trafficMin"), trafficCost: f.double("trafficCost"),
                        night: f.bool("night"), tariffCity: f.int("tariffCity", 1), tariffOut: f.int("tariffOut", 3),
                        discountPct: f.int("discountPct"), discountReason: f.strOrNil("discountReason"),
                        flatCity: flat?.strOrNil("city"), flatValue: flat?.doubleOrNil("value"),
                        districtA: f.strOrNil("districtA"), districtB: f.strOrNil("districtB"), tariffName: f.str("tariffName"))
        }
        return Route(
            points: Polyline.decode(o.str("shape")),
            maneuvers: mans,
            distanceM: o.int("distance"),
            baseTimeS: o.int("baseTime"),
            trafficTimeS: o.int("trafficTime", o.int("baseTime")),
            hasTraffic: o.bool("hasTraffic"),
            traffic: o.arr("traffic").map { TrafficRange(from: $0.int("from"), to: $0.int("to"), level: $0.int("level")) },
            limits: o.arr("limits").map { LimitRange(from: $0.int("from"), to: $0.int("to"), limit: $0.int("limit"), override: $0.bool("override")) },
            lanes: o.arr("lanes").map { l in
                LaneInfo(index: l.int("idx"), lanes: l.arr("lanes").map { Lane(directions: ($0["d"] as? [String]) ?? [], ok: $0.bool("ok")) })
            },
            stops: o.int("stops"),
            fare: fare,
            cameras: o.arr("cameras").map { Camera(idx: $0.int("idx"), type: $0.str("type", "fixed"), maxspeed: $0.intOrNil("maxspeed")) },
            sections: o.arr("sections").map {
                SpeedSection(fromIdx: $0.int("fromIdx"), toIdx: $0.int("toIdx"), lengthM: $0.int("lengthM"),
                             maxspeed: $0.intOrNil("maxspeed"), name: $0.strOrNil("name"))
            }
        )
    }
}
