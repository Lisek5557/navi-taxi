import MapLibre
import UIKit

/// Mapa MapLibre: styl z serwera (dzień/noc), trasa z korkami, ikony, kamera nawigacji.
final class MapController: NSObject, MLNMapViewDelegate {
    let mapView: MLNMapView
    private(set) var style: MLNStyle?
    private var loadedURL: String?
    var onStyleLoaded: (() -> Void)?
    var onUserGesture: (() -> Void)?
    var onLongPress: ((LatLon) -> Void)?

    static let reportIcons: [String: (String, UIColor)] = [
        "police": ("🚓", UIColor(rgb: 0x2F7DE1)),
        "accident": ("💥", UIColor(rgb: 0xE5482F)),
        "hazard": ("⚠️", UIColor(rgb: 0xF5A623)),
        "closed": ("⛔", UIColor(rgb: 0x8E1B1B)),
    ]
    static let trafficColors: [(Int, UIColor)] = [
        (-1, UIColor(rgb: 0x3478F6)), (0, UIColor(rgb: 0x2E9E5B)), (1, UIColor(rgb: 0xF5A623)),
        (2, UIColor(rgb: 0xE5482F)), (3, UIColor(rgb: 0x8E1B1B)),
    ]

    override init() {
        mapView = MLNMapView(frame: CGRect(x: 0, y: 0, width: 400, height: 800))
        super.init()
        mapView.delegate = self
        mapView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        mapView.showsUserLocation = true
        mapView.setCenter(CLLocationCoordinate2D(latitude: 53.1235, longitude: 18.0084), zoomLevel: 12, animated: false)
        let lp = UILongPressGestureRecognizer(target: self, action: #selector(longPress(_:)))
        mapView.addGestureRecognizer(lp)
    }

    @objc private func longPress(_ g: UILongPressGestureRecognizer) {
        guard g.state == .began else { return }
        let c = mapView.convert(g.location(in: mapView), toCoordinateFrom: mapView)
        onLongPress?(LatLon(lat: c.latitude, lon: c.longitude))
    }

    func setStyle(_ url: String) {
        guard url != loadedURL, let u = URL(string: url) else { return }
        loadedURL = url
        style = nil
        mapView.styleURL = u
    }

    // MARK: - Delegat

    func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
        self.style = style
        setupLayers(style)
        onStyleLoaded?()
    }

    func mapView(_ mapView: MLNMapView, regionWillChangeWith reason: MLNCameraChangeReason, animated: Bool) {
        let gestures: MLNCameraChangeReason = [.gesturePan, .gesturePinch, .gestureRotate, .gestureZoomIn, .gestureZoomOut, .gestureOneFingerZoom, .gestureTilt]
        if !reason.intersection(gestures).isEmpty { onUserGesture?() }
    }

    // MARK: - Warstwy

    private func source(_ id: String) -> MLNShapeSource? { style?.source(withIdentifier: id) as? MLNShapeSource }

    private func setupLayers(_ s: MLNStyle) {
        for id in ["alt-src", "route-src", "stops-src", "reports-src", "discount-src", "cameras-src"] {
            s.addSource(MLNShapeSource(identifier: id, shape: nil, options: nil))
        }
        let below = s.layers.first { $0 is MLNSymbolStyleLayer }
        func add(_ l: MLNStyleLayer) { if let b = below { s.insertLayer(l, below: b) } else { s.addLayer(l) } }
        func c(_ v: Any) -> NSExpression { NSExpression(forConstantValue: v) }

        let disc = source("discount-src")!
        let fill = MLNFillStyleLayer(identifier: "discount-fill", source: disc)
        fill.fillColor = c(UIColor(rgb: 0x2E9E5B)); fill.fillOpacity = c(0.10)
        add(fill)
        let dl = MLNLineStyleLayer(identifier: "discount-line", source: disc)
        dl.lineColor = c(UIColor(rgb: 0x2E9E5B)); dl.lineWidth = c(2); dl.lineOpacity = c(0.6); dl.lineDashPattern = c([2, 2])
        add(dl)

        let alt = MLNLineStyleLayer(identifier: "alt-line", source: source("alt-src")!)
        alt.lineColor = c(UIColor(rgb: 0x8A93A3)); alt.lineWidth = c(6); alt.lineOpacity = c(0.7)
        alt.lineCap = c("round"); alt.lineJoin = c("round")
        add(alt)
        let route = source("route-src")!
        let casing = MLNLineStyleLayer(identifier: "route-casing", source: route)
        casing.lineColor = c(UIColor.white); casing.lineWidth = c(11); casing.lineCap = c("round"); casing.lineJoin = c("round")
        add(casing)
        for (lvl, col) in MapController.trafficColors {
            let l = MLNLineStyleLayer(identifier: "route-line-\(lvl)", source: route)
            l.predicate = NSPredicate(format: "level == %d", lvl)
            l.lineColor = c(col); l.lineWidth = c(7); l.lineCap = c("round"); l.lineJoin = c("round")
            add(l)
        }

        for (type, v) in MapController.reportIcons { s.setImage(MapController.icon(v.0, v.1), forName: "rep-\(type)") }
        for i in 1...9 { s.setImage(MapController.icon("\(i)", UIColor(rgb: 0x1B6B3A)), forName: "stop-\(i)") }
        s.setImage(MapController.icon("🏁", UIColor(rgb: 0x1B6B3A)), forName: "stop-dest")
        s.setImage(MapController.icon("📷", UIColor(rgb: 0x212121)), forName: "cam-fixed")
        s.setImage(MapController.icon("🚦", UIColor(rgb: 0x212121)), forName: "cam-red_light")
        s.setImage(MapController.icon("⏱", UIColor(rgb: 0x6A1B9A)), forName: "cam-section")

        for (id, src, size) in [("cameras-layer", "cameras-src", 0.75), ("stops-layer", "stops-src", 1.0), ("reports-layer", "reports-src", 0.9)] {
            let l = MLNSymbolStyleLayer(identifier: id, source: source(src)!)
            l.iconImageName = NSExpression(forKeyPath: "icon")
            l.iconAllowsOverlap = c(true)
            l.iconIgnoresPlacement = c(true)
            l.iconScale = c(size)
            s.addLayer(l)
        }
    }

    /// Kafle korków (TomTom przez nasz serwer) pod trasą.
    func setTrafficLayer(server: String, key: String, on: Bool) {
        guard let s = style else { return }
        if let l = s.layer(withIdentifier: "traffic-layer") { s.removeLayer(l) }
        if let src = s.source(withIdentifier: "traffic-src") { s.removeSource(src) }
        guard on, !server.isEmpty else { return }
        let k = key.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? key
        let src = MLNRasterTileSource(identifier: "traffic-src", tileURLTemplates: ["\(server)/traffic/{z}/{x}/{y}.png?k=\(k)"],
                                      options: [.minimumZoomLevel: 6, .maximumZoomLevel: 18, .tileSize: 256])
        s.addSource(src)
        let l = MLNRasterStyleLayer(identifier: "traffic-layer", source: src)
        l.rasterOpacity = NSExpression(forConstantValue: 0.75)
        if let alt = s.layer(withIdentifier: "alt-line") { s.insertLayer(l, below: alt) } else { s.addLayer(l) }
    }

    // MARK: - Rysowanie

    private static func line(_ pts: ArraySlice<LatLon>) -> MLNPolylineFeature {
        var coords = pts.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
        return MLNPolylineFeature(coordinates: &coords, count: UInt(coords.count))
    }

    private static func point(_ p: LatLon, icon: String) -> MLNPointFeature {
        let f = MLNPointFeature()
        f.coordinate = CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon)
        f.attributes = ["icon": icon]
        return f
    }

    func drawRoute(_ resp: RouteResponse?, navigating: Bool, fromIndex: Int = 0) {
        guard let resp = resp else {
            source("route-src")?.shape = nil
            source("alt-src")?.shape = nil
            return
        }
        let r = resp.chosen
        var features: [MLNShape & MLNFeature] = []
        let ranges = r.traffic.isEmpty ? [TrafficRange(from: 0, to: r.points.count - 1, level: -1)] : r.traffic
        for tr in ranges {
            let from = max(tr.from, fromIndex)
            let to = min(tr.to, r.points.count - 1)
            if to - from < 1 { continue }
            let f = MapController.line(r.points[from...to])
            f.attributes = ["level": NSNumber(value: tr.level)]
            features.append(f)
        }
        source("route-src")?.shape = MLNShapeCollectionFeature(shapes: features)
        let alts: [MLNShape & MLNFeature] = navigating ? [] : resp.alternatives.filter { $0.points.count > 1 }.map { MapController.line($0.points[...]) }
        source("alt-src")?.shape = MLNShapeCollectionFeature(shapes: alts)
    }

    func drawStops(_ stops: [Place], destination: Place?) {
        var f: [MLNShape & MLNFeature] = []
        for (i, p) in stops.enumerated() { f.append(MapController.point(p.pos, icon: "stop-\(min(i + 1, 9))")) }
        if let d = destination { f.append(MapController.point(d.pos, icon: "stop-dest")) }
        source("stops-src")?.shape = MLNShapeCollectionFeature(shapes: f)
    }

    func drawReports(_ reports: [Report]) {
        let f: [MLNShape & MLNFeature] = reports.map { MapController.point(LatLon(lat: $0.lat, lon: $0.lon), icon: "rep-\($0.type)") }
        source("reports-src")?.shape = MLNShapeCollectionFeature(shapes: f)
    }

    func drawCameras(route: Route?, near: [Camera]) {
        var f: [MLNShape & MLNFeature] = []
        func icon(_ c: Camera) -> String { c.type == "red_light" ? "cam-red_light" : "cam-fixed" }
        if let r = route {
            for c in r.cameras where r.points.indices.contains(c.idx) { f.append(MapController.point(r.points[c.idx], icon: icon(c))) }
            for s in r.sections {
                for i in [s.fromIdx, s.toIdx] where r.points.indices.contains(i) { f.append(MapController.point(r.points[i], icon: "cam-section")) }
            }
        } else {
            for c in near { f.append(MapController.point(LatLon(lat: c.lat, lon: c.lon), icon: icon(c))) }
        }
        source("cameras-src")?.shape = MLNShapeCollectionFeature(shapes: f)
    }

    func drawDiscountZones(_ data: Data) {
        guard let shape = try? MLNShape(data: data, encoding: String.Encoding.utf8.rawValue) else { return }
        source("discount-src")?.shape = shape
    }

    // MARK: - Kamera

    func fit(_ pts: [LatLon], insets: UIEdgeInsets, animated: Bool = true) {
        guard pts.count > 1 else { return }
        var minLat = 90.0, maxLat = -90.0, minLon = 180.0, maxLon = -180.0
        for p in pts { minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat); minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon) }
        flatten()
        let b = MLNCoordinateBounds(sw: CLLocationCoordinate2D(latitude: minLat, longitude: minLon),
                                    ne: CLLocationCoordinate2D(latitude: maxLat, longitude: maxLon))
        mapView.setVisibleCoordinateBounds(b, edgePadding: insets, animated: animated, completionHandler: nil)
    }

    func flatten() {
        let cam = MLNMapCamera(lookingAtCenter: mapView.centerCoordinate, altitude: mapView.camera.altitude, pitch: 0, heading: 0)
        mapView.setCamera(cam, animated: false)
    }

    func center(_ p: LatLon, zoom: Double) {
        mapView.setCenter(CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon), zoomLevel: zoom, direction: 0, animated: true)
    }

    /// Kamera nawigacji: auto w dolnej części ekranu, kąt, kierunek jazdy.
    func follow(_ p: LatLon, zoom: Double, bearing: Double, pitch: Double, topPad: CGFloat, bottomPad: CGFloat, duration: TimeInterval) {
        let alt = MLNAltitudeForZoomLevel(zoom, CGFloat(pitch), p.lat, mapView.bounds.size)
        let cam = MLNMapCamera(lookingAtCenter: CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon), altitude: alt, pitch: CGFloat(pitch), heading: bearing)
        mapView.setCamera(cam, withDuration: duration, animationTimingFunction: CAMediaTimingFunction(name: .linear),
                          edgePadding: UIEdgeInsets(top: topPad, left: 0, bottom: bottomPad, right: 0), completionHandler: nil)
    }

    // MARK: - Ikony z emoji

    static func icon(_ text: String, _ bg: UIColor) -> UIImage {
        let size = CGSize(width: 44, height: 44)
        return UIGraphicsImageRenderer(size: size).image { _ in
            let r = CGRect(origin: .zero, size: size).insetBy(dx: 2, dy: 2)
            let path = UIBezierPath(ovalIn: r)
            bg.setFill(); path.fill()
            UIColor.white.setStroke(); path.lineWidth = 3; path.stroke()
            let attrs: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 21), .foregroundColor: UIColor.white]
            let s = NSAttributedString(string: text, attributes: attrs)
            let ts = s.size()
            s.draw(at: CGPoint(x: (size.width - ts.width) / 2, y: (size.height - ts.height) / 2))
        }
    }
}

extension UIColor {
    convenience init(rgb: UInt32, alpha: CGFloat = 1) {
        self.init(red: CGFloat((rgb >> 16) & 0xff) / 255, green: CGFloat((rgb >> 8) & 0xff) / 255, blue: CGFloat(rgb & 0xff) / 255, alpha: alpha)
    }
}
