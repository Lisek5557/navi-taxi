// Testy rdzenia (bez symulatora): swiftc TaxiNav/Core/*.swift CoreTests/main.swift -o coretests && ./coretests
import Foundation

var failures = 0
func check(_ cond: Bool, _ msg: String, line: Int = #line) {
    if !cond { failures += 1; print("::error::CoreTests linia \(line): \(msg)") }
}
func near(_ a: Double, _ b: Double, _ eps: Double, _ msg: String, line: Int = #line) {
    check(abs(a - b) <= eps, "\(msg): \(a) != \(b) ±\(eps)", line: line)
}
func eq<T: Equatable>(_ a: T, _ b: T, _ msg: String = "", line: Int = #line) {
    check(a == b, "\(msg) '\(a)' != '\(b)'", line: line)
}

let lat0 = 52.2, lon0 = 21.0
let dLat1km = 1000.0 / 111195.0
let dLon500m = 500.0 / (111195.0 * cos(Geo.rad(lat0 + dLat1km)))
var points: [LatLon] = []
for i in 0...10 { points.append(LatLon(lat: lat0 + dLat1km * Double(i) / 10, lon: lon0)) }
for i in 1...5 { points.append(LatLon(lat: lat0 + dLat1km, lon: lon0 + dLon500m * Double(i) / 5)) }

let m0 = Maneuver(type: 1, instruction: "Jedź na północ ulicą Marszałkowską.", verbalAlert: nil, verbalPre: "Jedź na północ ulicą Marszałkowską.", begin: 0, lengthM: 1000, streets: ["Marszałkowska"], stop: nil)
let m1 = Maneuver(type: 10, instruction: "Skręć w prawo w Aleje Jerozolimskie.", verbalAlert: "Skręć w prawo w Aleje Jerozolimskie.", verbalPre: "Skręć w prawo w Aleje Jerozolimskie.", begin: 10, lengthM: 500, streets: ["Aleje Jerozolimskie"], stop: nil)
let m2 = Maneuver(type: 4, instruction: "Dotarłeś do celu.", verbalAlert: nil, verbalPre: "Dotarłeś do celu.", begin: 15, lengthM: 0, streets: [], stop: nil)
func makeRoute(maneuvers: [Maneuver] = [m0, m1, m2], limits: [LimitRange] = [], lanes: [LaneInfo] = []) -> Route {
    Route(points: points, maneuvers: maneuvers, distanceM: 1500, baseTimeS: 180, trafficTimeS: 240, hasTraffic: true, limits: limits, lanes: lanes)
}
func at(_ n: Double, _ e: Double = 0) -> LatLon {
    LatLon(lat: lat0 + n / 111195.0, lon: lon0 + e / (111195.0 * cos(Geo.rad(lat0))))
}

// Polyline
let p = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision: 5)
eq(p.count, 3, "polyline count")
near(p[0].lat, 38.5, 1e-6, "lat0"); near(p[0].lon, -120.2, 1e-6, "lon0")
near(p[2].lat, 43.252, 1e-6, "lat2"); near(p[2].lon, -126.453, 1e-6, "lon2")

// Postęp i komunikaty
do {
    var said: [String] = []
    let e = NavigationEngine(route: makeRoute()) { said.append($0) }
    let s1 = e.update(at(100), speedMps: 10)
    check(!s1.offRoute, "offRoute s1")
    eq(s1.nextManeuver?.type ?? -1, 10, "next type")
    near(s1.distanceToNextM, 900, 15, "distToNext")
    near(s1.remainingM, 1400, 20, "remaining")
    near(s1.remainingS, 240.0 * 1400 / 1500, 5, "remainingS")
    eq(said, ["Jedź na północ ulicą Marszałkowską."], "start")
    _ = e.update(at(630), speedMps: 10)
    eq(said.last ?? "", "Za 400 metrów, skręć w prawo w Aleje Jerozolimskie.", "far")
    let c = said.count
    _ = e.update(at(700), speedMps: 10)
    eq(said.count, c, "no repeat")
    _ = e.update(at(950), speedMps: 10)
    eq(said.last ?? "", "Skręć w prawo w Aleje Jerozolimskie.", "near")
    let s4 = e.update(at(1000, 200), speedMps: 10)
    eq(s4.nextManeuver?.type ?? -1, 4, "after turn")
    near(s4.distanceToNextM, 300, 20, "dist after turn")
    let s5 = e.update(at(1000, 490), speedMps: 5)
    check(s5.arrived, "arrived")
    eq(said.last ?? "", "Dotarłeś do celu.", "arrived speech")
}

// Zjechanie z trasy
do {
    let e = NavigationEngine(route: makeRoute(), announceStart: false) { _ in }
    check(!e.update(at(300, 150)).offRoute, "off1")
    check(!e.update(at(310, 150)).offRoute, "off2")
    check(e.update(at(320, 150)).offRoute, "off3")
    check(!e.update(at(330, 5)).offRoute, "back")
}

// Polskie odległości
eq(Speech.inDistance(40), "Za 50 metrów")
eq(Speech.inDistance(290), "Za 300 metrów")
eq(Speech.inDistance(780), "Za 800 metrów")
eq(Speech.inDistance(980), "Za 1 kilometr")
eq(Speech.inDistance(1450), "Za półtora kilometra")
eq(Speech.inDistance(2100), "Za 2 kilometry")
eq(Speech.inDistance(2600), "Za 2,5 kilometra")
eq(Speech.inDistance(5000), "Za 5 kilometrów")
eq(Speech.inDistance(12000), "Za 12 kilometrów")
eq(Speech.inDistance(22000), "Za 22 kilometry")
eq(Speech.short(347), "350 m")
eq(Speech.short(1234), "1,2 km")
eq(Speech.minutes(3700), "1 h 2 min")

// Ograniczenia, pasy, rzut
do {
    let r = makeRoute(limits: [LimitRange(from: 0, to: 9, limit: 50, override: false), LimitRange(from: 10, to: 15, limit: 30, override: true)],
                      lanes: [LaneInfo(index: 10, lanes: [Lane(directions: ["left"], ok: false), Lane(directions: ["straight", "right"], ok: true)])])
    let e = NavigationEngine(route: r, announceStart: false) { _ in }
    let far = e.update(at(100))
    eq(far.speedLimit ?? -1, 50, "limit")
    check(far.lanes == nil, "lanes far")
    let nr = e.update(at(800))
    eq(nr.lanes?.lanes.count ?? 0, 2, "lanes near")
    near(nr.distanceToLanesM, 200, 15, "lane dist")
    eq(e.update(at(1000, 250)).speedLimit ?? -1, 30, "limit 2")
    let pr = e.project(at(500, 20))
    near(pr.along, 500, 15, "along"); near(pr.dist, 20, 3, "dist")
    eq(Lanes.label(Lane(directions: ["straight", "right"], ok: true)), "↑→")
}

// Przystanek pośredni
do {
    var said: [String] = []
    let stop = Maneuver(type: 4, instruction: "Przystanek 1", verbalAlert: "Przystanek 1.", verbalPre: "Dotarłeś do przystanku 1.", begin: 10, lengthM: 0, streets: [], stop: 1)
    let e = NavigationEngine(route: makeRoute(maneuvers: [m0, stop, m2]), announceStart: false) { said.append($0) }
    _ = e.update(at(960), speedMps: 8)
    eq(said.last ?? "", "Dotarłeś do przystanku 1.", "stop")
}

// Kamera
near(NavCamera.zoomFor(speedKmh: 0, distToManeuverM: 1000), 17.5, 0.01, "z0")
near(NavCamera.zoomFor(speedKmh: 50, distToManeuverM: 1000), 16.4, 0.01, "z50")
near(NavCamera.zoomFor(speedKmh: 130, distToManeuverM: 1000), 14.9, 0.01, "z130")
check(NavCamera.zoomFor(speedKmh: 50, distToManeuverM: 50) > NavCamera.zoomFor(speedKmh: 50, distToManeuverM: 500), "zoom near")
near(NavCamera.lookAheadM(0), 25, 0.01, "la0"); near(NavCamera.lookAheadM(200), 140, 0.01, "la200")
near(Geo.bearing(at(0), at(100)), 0, 1, "brg N"); near(Geo.bearing(at(0), at(0, 100)), 90, 1, "brg E")
near(NavCamera.smoothAngle(350, 20, 0.5), 5, 0.01, "smooth")
do {
    let e = NavigationEngine(route: makeRoute(), announceStart: false) { _ in }
    let q = e.pointAt(1200)
    near((q.lat - lat0) * 111195.0, 1000, 10, "pointAt lat")
    check(q.lon > lon0, "pointAt lon")
    eq(e.remainingPoints(from: 10).count, 6, "remaining pts")
    let alt = NavCamera.altitude(zoom: 16, pitch: 0, latitude: 52, viewHeight: 800)
    check(alt > 300 && alt < 3000, "altitude \(alt)")
}

// Głos i parsowanie
eq(VoiceQuery.clean("jedź do Gdańska 12"), "Gdańska 12")
eq(VoiceQuery.clean("Galeria Pomorska"), "Galeria Pomorska")
do {
    let json = """
    {"routes":[{"shape":"_p~iF~ps|U_ulLnnqC","distance":1200,"baseTime":100,"trafficTime":130,"hasTraffic":true,
      "maneuvers":[{"type":1,"instruction":"Jedź","begin":0,"streets":["A"]},{"type":4,"instruction":"Cel","begin":1,"stop":null}],
      "limits":[{"from":0,"to":1,"limit":50}],"cameras":[{"idx":1,"type":"red_light","maxspeed":null}],
      "fare":{"total":25.5,"before":25.5,"flat":null,"tariffCity":2,"districtA":null}},
     {"shape":"","distance":900,"baseTime":200,"maneuvers":[]}],
     "pick":{"fastest":0,"shortest":1,"optimal":0}}
    """
    let j = try! JSONSerialization.jsonObject(with: Data(json.utf8)) as! JSON
    let resp = RouteParser.parseResponse(j)!
    eq(resp.routes.count, 2, "routes")
    eq(resp.routeFor("shortest").distanceM, 900, "shortest")
    let r = resp.chosen
    eq(r.trafficTimeS, 130, "traffic time"); eq(r.points.count, 2, "points")
    check(r.maneuvers[1].stop == nil, "stop null")
    eq(r.cameras.first?.type ?? "", "red_light", "camera"); check(r.cameras.first?.maxspeed == nil, "maxspeed nil")
    eq(r.fare?.tariffCity ?? 0, 2, "tariff"); check(r.fare?.flatValue == nil, "flat nil")
    eq(Fare.zl(25.5), "25,50 zł", "zł")
    eq(resp.routeFor("shortest").trafficTimeS, 200, "trafficTime fallback")
}

if failures > 0 { print("BŁĘDY: \(failures)"); exit(1) }
print("Wszystkie testy rdzenia OK")
