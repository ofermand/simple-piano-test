import SwiftUI
import AVFoundation

@MainActor
final class GameModel: ObservableObject {
    enum Stage: Equatable { case welcome, lesson(Int), practice, song, results }
    @Published var stage: Stage = .welcome
    @Published private(set) var level = 0
    @Published private(set) var unlocked = 0
    @Published private(set) var lessonLearned = false
    @Published private(set) var practiceStep = 0
    @Published private(set) var score = SongScore(level: 0)
    @Published private(set) var beat = -4.0
    @Published private(set) var playing = false
    @Published private(set) var preparing = false
    @Published private(set) var microphoneReady = false
    @Published private(set) var heard = "—"
    @Published private(set) var rms = 0.0
    @Published private(set) var speaking = false
    @Published private(set) var echoReady = false
    @Published var message = "לומדים תווים, מתרגלים ומנגנים שיר שלם"
    @Published var error: String?
    @Published var permissionDenied = false
    @Published var bpm = 72.0
    @Published var flexible = true
    @Published var voiceEnabled = true {
        didSet {
            UserDefaults.standard.set(voiceEnabled, forKey: "voiceEnabled")
            if !voiceEnabled { stopVoice() }
        }
    }
    @Published var volume = 0.5 { didSet { audio.volume = Float(volume) } }
    private let audio = AudioController()
    private var gate = NarrationGate()
    private var voiceChangedAt = -Double.infinity
    private var tailUntil = -Double.infinity
    private var lastPitchAt = 0.0
    private var lastPracticeAt = -Double.infinity
    private var preparation: Task<Void, Never>?
    private var setup: Task<Void, Never>?
    private var feedbackMilestone = 0
    private var now: Double { ProcessInfo.processInfo.systemUptime }
    var owned: [Int] { Music.owned(level) }
    var pattern: [Int] { Music.practice(level) }
    var target: Int? {
        switch stage {
        case .lesson(let i): return level == 0 ? [60,67][i] : owned.last
        case .practice: return practiceStep < pattern.count ? pattern[practiceStep] : nil
        default: return nil
        }
    }
    var accuracy: Int { Int((Double(score.hits) / Double(Music.required(level)) * 100).rounded()) }
    init() {
        unlocked = min(4, max(0, UserDefaults.standard.integer(forKey: "unlockedLevel")))
        level = unlocked
        score = SongScore(level: level)
        voiceEnabled = UserDefaults.standard.object(forKey: "voiceEnabled") as? Bool ?? true
        audio.onPitch = { [weak self] result, capturedAt in self?.receive(result, capturedAt: capturedAt) }
        audio.onSpeechFinished = { [weak self] in self?.voiceFinished() }
    }
    func selectLevel(_ value: Int) {
        guard stage == .welcome else { return }
        level = min(unlocked, max(0, value)); score = SongScore(level: level)
    }
    func startLessons() {
        guard !preparing else { return }
        error = nil; preparing = true
        setup = Task { [weak self] in
            guard let self else { return }
            let granted = await audio.requestPermission()
            guard !Task.isCancelled else { return }
            guard granted else {
                permissionDenied = true; preparing = false
                error = "כדי לזהות פסנתר, יש לאשר גישה למיקרופון בהגדרות האייפון."
                return
            }
            do {
                try audio.start()
                microphoneReady = true; echoReady = audio.echoReady; preparing = false
                lessonLearned = false; stage = .lesson(0)
                introduceNote()
            } catch { preparing = false; self.error = error.localizedDescription; audio.shutdown() }
        }
    }
    private func introduceNote() {
        guard let target else { return }
        message = "מצאו את \(Music.name(target)) בחמשה ובפסנתר, ונגנו אותו"
        speak("בואו נכיר את התו \(Music.name(target)). הסתכלו על החמשה ועל הקליד המואר, ועכשיו נגנו!")
    }
    func nextLesson() {
        guard lessonLearned, case .lesson(let i) = stage else { return }
        stopVoice(); lessonLearned = false
        if level == 0 && i == 0 { stage = .lesson(1); introduceNote() }
        else { startPractice() }
    }
    func startPractice() {
        preparation?.cancel(); preparing = false
        audio.pause(); playing = false; stopVoice()
        stage = .practice; practiceStep = 0; lastPracticeAt = -.infinity
        message = "נגנו ארבעה תווים לפי הסדר"
        speak("ועכשיו תרגול קצר. הסתכלו על התו ועל הקליד המואר, ונגנו \(Music.name(pattern[0])).")
    }
    func startSong() {
        guard !preparing else { return }
        stopVoice(); error = nil; preparing = true; playing = false
        preparation?.cancel()
        preparation = Task { [weak self] in
            guard let self else { return }
            do {
                try await audio.prepare(bpm: bpm, level: level)
                try Task.checkCancellation()
                score = SongScore(level: level); beat = -4; feedbackMilestone = 0
                stage = .song; preparing = false
                // Four audible count-in clicks; no speech can cover the first scored notes.
                try audio.play(); playing = true
                message = "הפסנתר שלכם מנגן את התווים הצבעוניים"
            } catch is CancellationError { }
            catch { preparing = false; self.error = error.localizedDescription }
        }
    }
    func togglePlayback() {
        guard stage == .song, !preparing else { return }
        if playing { audio.pause(); playing = false; stopVoice() }
        else {
            do { try audio.play(); playing = true }
            catch { self.error = error.localizedDescription }
        }
    }
    func tick() {
        if now - lastPitchAt > 0.6 { heard = "—" }
        guard stage == .song, playing else { return }
        beat = audio.elapsed * bpm / 60 - 4
        score.expire(beat: beat, flexible: flexible)
        if beat >= 48 {
            // Expire the final note too before presenting complete statistics.
            score.expire(beat: 50, flexible: flexible)
            audio.pause(); playing = false; stage = .results
            message = "ניגנתם שיר שלם!"
            speak(level < 4 ? "כל הכבוד! סיימתם את השיר. אפשר לנגן שוב, או ללמוד תו חדש!" : "כל הכבוד! למדתם את כל תווי השיר!")
        }
    }
    func unlockNext() {
        guard stage == .results, level < 4 else { return }
        preparation?.cancel(); preparing = false
        level += 1; unlocked = max(unlocked, level)
        UserDefaults.standard.set(unlocked, forKey: "unlockedLevel")
        lessonLearned = false; stage = .lesson(0); introduceNote()
    }
    private func receive(_ result: PitchResult, capturedAt: Double) {
        guard microphoneReady else { return }
        rms = min(1, result.rms * 15)
        guard capturedAt >= voiceChangedAt else { return } // discard queued callbacks from an older prompt
        if speaking || capturedAt < tailUntil {
            guard gate.accepts(result, time: capturedAt, echoReady: echoReady) else { return }
        } else { gate.reset() }
        guard result.hz > 0, result.confidence >= 0.52 else { return }
        let midi = Music.midi(result.hz)
        heard = Music.name(Int(midi.rounded())); lastPitchAt = now
        switch stage {
        case .lesson:
            guard !lessonLearned, let target, Music.same(midi, target, tolerance: 0.48) else { return }
            lessonLearned = true; stopVoice()
            message = "כל הכבוד! זיהיתי \(Music.name(target)) בפסנתר שלכם"
            speak("יופי! זה התו הנכון. לחצו על המשך.")
        case .practice:
            guard let target, Music.same(midi, target), now - lastPracticeAt >= 0.35 else { return }
            stopVoice(); practiceStep += 1; lastPracticeAt = now
            if practiceStep == pattern.count {
                message = "סיימתם את התרגול! מוכנים לשיר?"
                speak("כל הכבוד! סיימתם לתרגל. עכשיו מנגנים את כל השיר!")
            } else {
                message = "יופי! עכשיו \(Music.name(pattern[practiceStep]))"
                speak("מצוין! עכשיו נגנו \(Music.name(pattern[practiceStep])).")
            }
        case .song where playing:
            if score.receive(midi: midi, beat: beat, bpm: bpm, time: capturedAt, attack: result.attack, flexible: flexible) {
                if speaking { stopVoice() }
                message = "מעולה! ניגנתם \(heard)"
                let next = Music.melody.indices.first { score.marks[$0] == .waiting && Music.melody[$0].start >= beat }
                if score.hits / 4 > feedbackMilestone, let next, Music.melody[next].start - beat > 2.2 {
                    feedbackMilestone = score.hits / 4; speak("מעולה!")
                }
            }
        default: break
        }
    }
    private func speak(_ text: String) {
        stopVoice()
        guard voiceEnabled else { return }
        voiceChangedAt = now; speaking = true; gate.reset()
        if !audio.speak(text) {
            voiceFinished()
            message += " · קול עברי אינו מותקן במכשיר"
        }
    }
    private func voiceFinished() {
        speaking = false; tailUntil = now + 0.35; gate.reset()
    }
    func stopVoice() {
        audio.stopSpeech(); speaking = false; tailUntil = now + 0.35; gate.reset(); voiceChangedAt = now
    }
    /// Never resume recording/playback automatically after a call, route change or backgrounding.
    func suspend() {
        setup?.cancel(); preparation?.cancel(); stopVoice(); audio.shutdown()
        playing = false; preparing = false; microphoneReady = false
        stage = .welcome; message = "לחצו על התחלה כדי להפעיל שוב את המיקרופון"
    }
}
