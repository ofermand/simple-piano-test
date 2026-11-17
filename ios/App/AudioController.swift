import AVFoundation
import Foundation

/// Mutable DSP state is confined to this queue, outside the audio render callback.
private final class CapturePipeline {
    private let queue = DispatchQueue(label: "piano.pitch", qos: .userInitiated)
    private var samples: [Float] = []
    private var tracker = PitchTracker()
    let receive: (PitchResult, Double) -> Void
    init(receive: @escaping (PitchResult, Double) -> Void) { self.receive = receive }
    func append(_ input: [Float], rate: Double, at: Double) {
        queue.async { [self] in
            // Downsample 48/44.1 kHz to ~16/22 kHz before YIN.
            let stride = max(1, Int(rate / 16000))
            samples.append(contentsOf: Swift.stride(from: 0, to: input.count, by: stride).map { input[$0] })
            let reducedRate = rate / Double(stride)
            while samples.count >= 2048 {
                let result = tracker.analyze(Array(samples.prefix(2048)), sampleRate: reducedRate, time: at)
                receive(result, at)
                samples.removeFirst(512)
            }
        }
    }
}

@MainActor
final class AudioController: NSObject, AVSpeechSynthesizerDelegate {
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let speech = AVSpeechSynthesizer()
    private var currentUtterance: AVSpeechUtterance?
    private var pipeline: CapturePipeline?
    private var hasTap = false
    private var prepared: AVAudioPCMBuffer?
    private(set) var echoReady = false
    private(set) var running = false
    var onPitch: ((PitchResult, Double) -> Void)?
    var onSpeechFinished: (() -> Void)?
    var volume: Float = 0.5 { didSet { player.volume = volume } }
    var elapsed: Double {
        guard let time = player.lastRenderTime, let played = player.playerTime(forNodeTime: time) else { return 0 }
        return max(0, Double(played.sampleTime) / played.sampleRate)
    }
    override init() {
        super.init()
        speech.delegate = self
        speech.usesApplicationAudioSession = true
        engine.attach(player)
    }
    func requestPermission() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) }
        }
    }
    func start() throws {
        guard !running else { return }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .videoChat, options: [.defaultToSpeaker, .allowBluetooth])
        try session.setPreferredSampleRate(48000)
        try session.setActive(true)
        let input = engine.inputNode
        do {
            try input.setVoiceProcessingEnabled(true)
            echoReady = input.isVoiceProcessingEnabled
        } catch {
            // Explicit safe fallback: the game will wait for narration to finish.
            try input.setVoiceProcessingEnabled(false)
            try session.setMode(.default)
            echoReady = false
        }
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { throw AudioFailure.noInput }
        let capture = CapturePipeline { [weak self] result, at in
            Task { @MainActor in self?.onPitch?(result, at) }
        }
        pipeline = capture
        if hasTap { input.removeTap(onBus: 0) }
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
            guard let channel = buffer.floatChannelData?[0] else { return }
            let copy = Array(UnsafeBufferPointer(start: channel, count: Int(buffer.frameLength)))
            capture.append(copy, rate: buffer.format.sampleRate, at: ProcessInfo.processInfo.systemUptime)
        }
        hasTap = true
        let playbackFormat = AVAudioFormat(standardFormatWithSampleRate: BackingSynth.sampleRate, channels: 1)!
        engine.connect(player, to: engine.mainMixerNode, format: playbackFormat)
        engine.prepare()
        do { try engine.start(); running = true }
        catch {
            input.removeTap(onBus: 0); hasTap = false; pipeline = nil
            throw error
        }
    }
    func prepare(bpm: Double, level: Int) async throws {
        let samples = await Task.detached(priority: .userInitiated) { BackingSynth.render(bpm: bpm, level: level) }.value
        try Task.checkCancellation()
        let format = AVAudioFormat(standardFormatWithSampleRate: BackingSynth.sampleRate, channels: 1)!
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)),
              let data = buffer.floatChannelData?[0] else { throw AudioFailure.buffer }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        samples.withUnsafeBufferPointer { source in data.update(from: source.baseAddress!, count: samples.count) }
        prepared = buffer
        rewind()
    }
    func rewind() {
        player.stop()
        if let prepared { player.scheduleBuffer(prepared) }
        player.volume = volume
    }
    func play() throws {
        guard running, engine.isRunning, prepared != nil else { throw AudioFailure.noPlayback }
        player.play()
    }
    func pause() { player.pause() }
    func stopSpeech() {
        currentUtterance = nil
        speech.stopSpeaking(at: .immediate)
    }
    @discardableResult
    func speak(_ message: String) -> Bool {
        stopSpeech()
        guard let voice = AVSpeechSynthesisVoice(language: "he-IL") else { return false }
        let utterance = AVSpeechUtterance(string: message)
        utterance.voice = voice; utterance.rate = 0.43; utterance.volume = 0.8
        currentUtterance = utterance
        speech.speak(utterance)
        return true
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in
            guard self.currentUtterance === utterance else { return }
            self.currentUtterance = nil; self.onSpeechFinished?()
        }
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor in
            guard self.currentUtterance === utterance else { return }
            self.currentUtterance = nil; self.onSpeechFinished?()
        }
    }
    func shutdown() {
        stopSpeech(); player.stop(); engine.stop()
        if hasTap { engine.inputNode.removeTap(onBus: 0); hasTap = false }
        pipeline = nil; running = false; prepared = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

enum AudioFailure: LocalizedError {
    case noInput, buffer, noPlayback
    var errorDescription: String? {
        switch self {
        case .noInput: return "לא נמצא מיקרופון זמין."
        case .buffer: return "לא ניתן להכין את הליווי."
        case .noPlayback: return "האודיו אינו פעיל. חזרו למסך הפתיחה ונסו שוב."
        }
    }
}
