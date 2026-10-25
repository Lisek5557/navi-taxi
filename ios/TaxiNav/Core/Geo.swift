import Foundation

struct LatLon: Equatable, Codable {
    var lat: Double
    var lon: Double
}

enum Geo {
    static let R = 6371000.0

    static func rad(_ d: Double) -> Double { d * .pi / 180 }
    static func deg(_ r: Double) -> Double { r * 180 / .pi }

    static func distance(_ a: LatLon, _ b: LatLon) -> Double {
        let dLat = rad(b.lat - a.lat)
        let dLon = rad(b.lon - a.lon)
        let s = pow(sin(dLat / 2), 2) + cos(rad(a.lat)) * cos(rad(b.lat)) * pow(sin(dLon / 2), 2)
        return 2 * R * asin(min(1.0, sqrt(s)))
    }

    /// Rzut punktu p na odcinek a-b: (odległość od odcinka w m, t w [0,1]).
    static func projectOnSegment(_ p: LatLon, _ a: LatLon, _ b: LatLon) -> (dist: Double, t: Double) {
        let kx = cos(rad(p.lat)) * 111320.0
        let ky = 110540.0
        let ax = (a.lon - p.lon) * kx, ay = (a.lat - p.lat) * ky
        let bx = (b.lon - p.lon) * kx, by = (b.lat - p.lat) * ky
        let dx = bx - ax, dy = by - ay
        let len2 = dx * dx + dy * dy
        let t = len2 == 0 ? 0.0 : min(1.0, max(0.0, (-ax * dx - ay * dy) / len2))
        let cx = ax + t * dx, cy = ay + t * dy
        return (sqrt(cx * cx + cy * cy), t)
    }

    static func cumulative(_ points: [LatLon]) -> [Double] {
        var c = [Double](repeating: 0, count: points.count)
        if points.count > 1 {
            for i in 1..<points.count { c[i] = c[i - 1] + distance(points[i - 1], points[i]) }
        }
        return c
    }

    static func bearing(_ a: LatLon, _ b: LatLon) -> Double {
        let la1 = rad(a.lat), la2 = rad(b.lat), dl = rad(b.lon - a.lon)
        let y = sin(dl) * cos(la2)
        let x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dl)
        return (deg(atan2(y, x)) + 360).truncatingRemainder(dividingBy: 360)
    }

    /// Różnica kątów w stopniach (0...180).
    static func angleDiff(_ a: Double, _ b: Double) -> Double {
        abs((((a - b) + 540).truncatingRemainder(dividingBy: 360)) - 180)
    }
}

enum Polyline {
    /// Dekoduje polyline z precyzją 6 (format Valhalli).
    static func decode(_ str: String, precision: Int = 6) -> [LatLon] {
        let factor = pow(10.0, Double(precision))
        let bytes = Array(str.utf8)
        var out: [LatLon] = []
        var index = 0
        var lat = 0, lon = 0
        func next() -> Int? {
            var result = 0, shift = 0
            while index < bytes.count {
                let b = Int(bytes[index]) - 63
                index += 1
                result |= (b & 0x1f) << shift
                shift += 5
                if b < 0x20 {
                    return (result & 1) != 0 ? ~(result >> 1) : (result >> 1)
                }
            }
            return nil
        }
        while index < bytes.count {
            guard let dLat = next(), let dLon = next() else { break }
            lat += dLat
            lon += dLon
            out.append(LatLon(lat: Double(lat) / factor, lon: Double(lon) / factor))
        }
        return out
    }
}
