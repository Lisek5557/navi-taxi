import AVFoundation
import AudioToolbox
import CoreLocation
import Speech
import UIKit

/// Polski lektor (AVSpeechSynthesizer) — przycisza muzykę na czas komunikatu.
final class Voice: NSObject, AVSpeechSynthesizerDelegate {
    private let synth = AVSpeechSynthesizer()
    private let voice = AVSpeechSynthesisVoice(language: "pl-PL")
    var muted = Prefs.muted

    override init() {
        super.init()
        synth.delegate = self
    }

    func say(_ text: String) {
        guard !muted, !text.isEmpty else { return }
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .voicePrompt, options: [.duckOthers, .interruptSpokenAudioAndMixWithOthers])
        try? session.setActive(true)
        let u = AVSpeechUtterance(string: text)
        u.voice = voice
        u.rate = AVSpeechUtteranceDefaultSpeechRate * 1.05
        synth.speak(u)
    }

    func stop() { synth.stopSpeaking(at: .immediate) }

    func beep(long: Bool = false) {
        guard !muted else { return }
        AudioServicesPlaySystemSound(long ? 1005 : 1057)
    }

    func speechSynthesizer(_ s: AVSpeechSynthesizer, didFinish u: AVSpeechUtterance) {
        if !s.isSpeaking {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }
}

/// GPS: dokładny, także w tle (wymaga UIBackgroundModes = location).
final class LocationService: NSObject, CLLocationManagerDelegate {
    private let lm = CLLocationManager()
    var onLocation: ((CLLocation) -> Void)?
    var onAuthChange: ((CLAuthorizationStatus) -> Void)?
    private(set) var last: CLLocation?

    override init() {
        super.init()
        lm.delegate = self
        lm.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        lm.distanceFilter = kCLDistanceFilterNone
        lm.activityType = .automotiveNavigation
        lm.pausesLocationUpdatesAutomatically = false
    }

    func start() {
        switch lm.authorizationStatus {
        case .notDetermined: lm.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse: lm.startUpdatingLocation(); lm.startUpdatingHeading()
        default: break
        }
    }

    /// Nawigacja w tle (wygaszony ekran, inna aplikacja na wierzchu).
    func setBackground(_ on: Bool) {
        lm.allowsBackgroundLocationUpdates = on
        lm.showsBackgroundLocationIndicator = on
    }

    var authorized: Bool { [.authorizedAlways, .authorizedWhenInUse].contains(lm.authorizationStatus) }

    func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
        onAuthChange?(m.authorizationStatus)
        if authorized { m.startUpdatingLocation() }
    }

    func locationManager(_ m: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let l = locations.last, l.horizontalAccuracy >= 0 else { return }
        last = l
        onLocation?(l)
    }

    func locationManager(_ m: CLLocationManager, didFailWithError error: Error) {}
}

extension CLLocation {
    var latLon: LatLon { LatLon(lat: coordinate.latitude, lon: coordinate.longitude) }
    var kmh: Double { speed >= 0 ? speed * 3.6 : 0 }
    /// Kurs GPS tylko w ruchu (na postoju jest losowy).
    var courseOrNil: Double? { (course >= 0 && speed > 2) ? course : nil }
}

/// Głosowe wpisywanie celu (rozpoznawanie mowy po polsku).
final class SpeechInput {
    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "pl-PL"))
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var silenceTimer: Timer?
    private var best = ""
    private var finished = false
    var onPartial: ((String) -> Void)?
    var onDone: ((String?) -> Void)?

    static func requestPermissions(_ done: @escaping (Bool) -> Void) {
        SFSpeechRecognizer.requestAuthorization { st in
            guard st == .authorized else { DispatchQueue.main.async { done(false) }; return }
            AVAudioSession.sharedInstance().requestRecordPermission { ok in DispatchQueue.main.async { done(ok) } }
        }
    }

    func start() throws {
        guard let recognizer = recognizer, recognizer.isAvailable else { throw ApiError(message: "Rozpoznawanie mowy jest niedostępne") }
        finished = false
        best = ""
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .measurement, options: .duckOthers)
        try session.setActive(true, options: .notifyOthersOnDeactivation)
        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        request = req
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buf, _ in self?.request?.append(buf) }
        engine.prepare()
        try engine.start()
        task = recognizer.recognitionTask(with: req) { [weak self] result, error in
            DispatchQueue.main.async {
                guard let self = self else { return }
                if let r = result {
                    self.best = r.bestTranscription.formattedString
                    self.onPartial?(self.best)
                    self.armSilence(1.6)
                    if r.isFinal { self.finish() }
                } else if error != nil {
                    self.finish()
                }
            }
        }
        armSilence(6)   // nic nie powiedziano
    }

    private func armSilence(_ s: TimeInterval) {
        silenceTimer?.invalidate()
        silenceTimer = Timer.scheduledTimer(withTimeInterval: s, repeats: false) { [weak self] _ in self?.finish() }
    }

    func finish() {
        guard !finished else { return }
        finished = true
        silenceTimer?.invalidate()
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        task?.cancel()
        task = nil; request = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        let text = best.trimmingCharacters(in: .whitespaces)
        onDone?(text.isEmpty ? nil : text)
    }
}
