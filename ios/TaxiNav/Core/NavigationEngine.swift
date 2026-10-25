import Foundation

struct NavState {
    var distanceFromRouteM: Double
    var offRoute: Bool
    var arrived: Bool
    var nextManeuver: Maneuver?
    var distanceToNextM: Double
    var remainingM: Double
    var remainingS: Double
    var snapped: LatLon
    var traveledIndex: Int
    var speedLimit: Int?
    var lanes: LaneInfo?
    var distanceToLanesM: Double
    var alongM: Double
}

/// Silnik nawigacji zakręt po zakręcie (czysty Swift — taki sam jak w Androidzie).
/// Valhalla: manewr k opisuje akcję w punkcie `begin` i jazdę do początku manewru k+1.
final class NavigationEngine {
    let route: Route
    private let speak: (String) -> Void
    private let pts: [LatLon]
    private let cum: [Double]
    private let total: Double
    private let manStart: [Double]
    private var lastSeg = 0
    private var offCount = 0
    private var saidFar = Set<Int>()
    private var saidNear = Set<Int>()
    private var saidArrived = false
    private var startPending: Bool

    init(route: Route, announceStart: Bool = true, speak: @escaping (String) -> Void) {
        self.route = route
        self.speak = speak
        self.startPending = announceStart
        pts = route.points
        cum = Geo.cumulative(route.points)
        total = max(1.0, cum.last ?? 0)
        let c = cum
        let n = route.points.count
        manStart = route.maneuvers.map { c.isEmpty ? 0 : c[min(max(0, $0.begin), n - 1)] }
    }

    func update(_ pos: LatLon, speedMps: Double = 0, accuracyM: Double = 10) -> NavState {
        let last = max(0, pts.count - 2)
        var best = nearest(pos, max(0, lastSeg - 5), min(last, lastSeg + 80))
        if best.dist > 60 {
            let g = nearest(pos, 0, last)
            if g.dist < best.dist { best = g }
        }
        lastSeg = best.seg
        let along = alongOf(best)

        let threshold = max(40.0, accuracyM * 1.5)
        offCount = best.dist > threshold ? offCount + 1 : 0
        let offRoute = offCount >= 3

        var cur = 0
        for k in manStart.indices where manStart[k] <= along + 5 { cur = k }
        let nextIdx = cur + 1
        let next: Maneuver? = route.maneuvers.indices.contains(nextIdx) ? route.maneuvers[nextIdx] : nil
        let distToNext = next != nil ? max(0, manStart[nextIdx] - along) : 0
        let remaining = max(0, total - along)
        let remainingS = Double(route.trafficTimeS) * remaining / total
        let arrived = remaining < 30 || (next == nil && remaining < 60)

        if startPending {
            startPending = false
            if let f = route.maneuvers.first { speak(f.verbalPre) }
        }
        if arrived {
            if !saidArrived {
                saidArrived = true
                speak(route.maneuvers.last?.verbalPre ?? "Dotarłeś do celu.")
            }
        } else if let next = next, !offRoute {
            let farDist: Double = speedMps > 22 ? 1500 : (speedMps > 14 ? 800 : 400)
            let nearDist = max(60.0, speedMps * 7)
            let isDestination = (4...6).contains(next.type) && next.stop == nil
            if distToNext <= nearDist && !saidNear.contains(nextIdx) {
                saidNear.insert(nextIdx); saidFar.insert(nextIdx)
                if !isDestination { speak(next.verbalPre) }
            } else if distToNext <= farDist && distToNext > nearDist + 100 && !saidFar.contains(nextIdx) {
                saidFar.insert(nextIdx)
                speak("\(Speech.inDistance(distToNext)), \(Speech.lowerFirst(next.verbalAlert ?? next.verbalPre))")
            }
        }

        let limit = route.limits.first { best.seg >= $0.from && best.seg <= $0.to }?.limit
        let lane = route.lanes.first { alongAt($0.index) >= along - 5 }
        let laneDist = lane.map { alongAt($0.index) - along } ?? 0
        let laneShown = (lane != nil && laneDist <= 350) ? lane : nil

        let snapped: LatLon
        if pts.count >= 2 {
            let a = pts[best.seg], b = pts[best.seg + 1]
            snapped = LatLon(lat: a.lat + best.t * (b.lat - a.lat), lon: a.lon + best.t * (b.lon - a.lon))
        } else {
            snapped = pts.first ?? pos
        }
        return NavState(distanceFromRouteM: best.dist, offRoute: offRoute, arrived: arrived, nextManeuver: next,
                        distanceToNextM: distToNext, remainingM: remaining, remainingS: remainingS, snapped: snapped,
                        traveledIndex: best.seg, speedLimit: limit, lanes: laneShown, distanceToLanesM: laneDist, alongM: along)
    }

    /// Punkt na trasie w odległości `along` m od początku.
    func pointAt(_ along: Double) -> LatLon {
        guard pts.count >= 2 else { return pts.first ?? LatLon(lat: 0, lon: 0) }
        let a = min(max(0, along), total)
        var i = 0
        while i < cum.count - 2 && cum[i + 1] < a { i += 1 }
        let seg = cum[i + 1] - cum[i]
        let t = seg <= 0 ? 0 : (a - cum[i]) / seg
        return LatLon(lat: pts[i].lat + t * (pts[i + 1].lat - pts[i].lat), lon: pts[i].lon + t * (pts[i + 1].lon - pts[i].lon))
    }

    func alongAt(_ index: Int) -> Double { cum.isEmpty ? 0 : cum[min(max(0, index), cum.count - 1)] }

    func remainingPoints(from index: Int) -> [LatLon] {
        guard !pts.isEmpty else { return [] }
        return Array(pts[min(max(0, index), pts.count - 1)...])
    }

    /// Rzut punktu na trasę: (dystans wzdłuż trasy, odległość od trasy).
    func project(_ p: LatLon) -> (along: Double, dist: Double) {
        let h = nearest(p, 0, max(0, pts.count - 2))
        if pts.count < 2 { return (0, h.dist) }
        return (alongOf(h), h.dist)
    }

    private struct Hit { var seg: Int; var dist: Double; var t: Double }

    private func alongOf(_ h: Hit) -> Double {
        guard pts.count >= 2 else { return 0 }
        return cum[h.seg] + h.t * (cum[h.seg + 1] - cum[h.seg])
    }

    private func nearest(_ p: LatLon, _ from: Int, _ to: Int) -> Hit {
        if pts.count < 2 { return Hit(seg: 0, dist: pts.isEmpty ? 0 : Geo.distance(p, pts[0]), t: 0) }
        var best = Hit(seg: from, dist: .greatestFiniteMagnitude, t: 0)
        if from > to { return Hit(seg: to, dist: Geo.projectOnSegment(p, pts[to], pts[to + 1]).dist, t: 0) }
        for i in from...to {
            let r = Geo.projectOnSegment(p, pts[i], pts[i + 1])
            if r.dist < best.dist { best = Hit(seg: i, dist: r.dist, t: r.t) }
        }
        return best
    }
}

enum Speech {
    /// "Za 300 metrów", "Za 1 kilometr", "Za półtora kilometra", "Za 2 kilometry", "Za 5 kilometrów".
    static func inDistance(_ m: Double) -> String {
        if m < 1000 {
            let step = m > 300 ? 100 : 50
            let r = max(step, Int((m / Double(step)).rounded()) * step)
            return r >= 1000 ? "Za 1 kilometr" : "Za \(r) metrów"
        }
        let halfKm = (m / 500).rounded() / 2.0
        if halfKm == 1.5 { return "Za półtora kilometra" }
        if halfKm.truncatingRemainder(dividingBy: 1) != 0 {
            return "Za \(String(halfKm).replacingOccurrences(of: ".", with: ",")) kilometra"
        }
        let n = Int(halfKm)
        let word: String
        if n == 1 { word = "kilometr" }
        else if (2...4).contains(n % 10) && !(12...14).contains(n % 100) { word = "kilometry" }
        else { word = "kilometrów" }
        return "Za \(n) \(word)"
    }

    static func lowerFirst(_ s: String) -> String {
        guard let f = s.first else { return s }
        return f.lowercased() + s.dropFirst()
    }

    /// Na ekran: "350 m", "1,2 km".
    static func short(_ m: Double) -> String {
        if m < 1000 { return "\(Int((m / 10).rounded()) * 10) m" }
        if m < 10000 { return String(format: "%.1f km", m / 1000).replacingOccurrences(of: ".", with: ",") }
        return "\(Int((m / 1000).rounded())) km"
    }

    static func minutes(_ s: Int) -> String {
        let m = (s + 30) / 60
        return m < 60 ? "\(m) min" : "\(m / 60) h \(m % 60) min"
    }
}

/// Parametry kamery nawigacji: zoom wg prędkości i bliskości manewru, kierunek wg trasy.
enum NavCamera {
    static func zoomFor(speedKmh: Double, distToManeuverM: Double) -> Double {
        let v = min(max(0, speedKmh), 140)
        let stops: [(Double, Double)] = [(0, 17.5), (20, 17.2), (50, 16.4), (90, 15.6), (130, 14.9), (140, 14.8)]
        var z = stops.last!.1
        for k in 0..<(stops.count - 1) {
            let (v0, z0) = stops[k], (v1, z1) = stops[k + 1]
            if v <= v1 { z = z0 + (z1 - z0) * (v - v0) / (v1 - v0); break }
        }
        let near: Double
        if distToManeuverM < 60 { near = 0.8 }
        else if distToManeuverM < 200 { near = 0.8 * (200 - distToManeuverM) / 140 }
        else { near = 0 }
        return min(18.0, z + near)
    }

    static func lookAheadM(_ speedKmh: Double) -> Double { min(140, max(25, 25 + speedKmh * 0.9)) }

    static func smoothAngle(_ from: Double, _ to: Double, _ k: Double) -> Double {
        let d = ((to - from + 540).truncatingRemainder(dividingBy: 360)) - 180
        return (from + d * k + 360).truncatingRemainder(dividingBy: 360)
    }

    /// Wysokość kamery MapLibre dla danego zoomu (MLNMapCamera używa wysokości, nie zoomu).
    static func altitude(zoom: Double, pitch: Double, latitude: Double, viewHeight: Double) -> Double {
        let metersPerPixel = cos(Geo.rad(latitude)) * 2 * .pi * 6378137.0 / (512.0 * pow(2.0, zoom))
        let metersTall = metersPerPixel * viewHeight
        let alt = metersTall / 2 / tan(Geo.rad(30) / 2)
        return alt * sin(.pi / 2 - Geo.rad(pitch))
    }
}

/// Zamiana „jedź do Gdańskiej 12” na zapytanie do wyszukiwarki.
enum VoiceQuery {
    static func clean(_ raw: String) -> String {
        var q = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let verbs = ["jedź", "jedz", "jedziemy", "nawiguj", "prowadź", "prowadz", "zawieź mnie", "zawieź", "kurs"]
        for v in verbs where q.lowercased().hasPrefix(v + " ") { q = String(q.dropFirst(v.count + 1)); break }
        for p in ["do", "na", "pod"] where q.lowercased().hasPrefix(p + " ") { q = String(q.dropFirst(p.count + 1)); break }
        return q.trimmingCharacters(in: .whitespaces)
    }
}
