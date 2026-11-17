import XCTest
@testable import PianoCore

final class PianoCoreTests: XCTestCase {
    func testSongAndEveryProgressionLevel() {
        XCTAssertEqual(Music.melody.count, 42)
        XCTAssertEqual(Music.melody.last!.start + Music.melody.last!.length, 48)
        XCTAssertEqual(Music.owned(0), [60,67])
        XCTAssertEqual(Music.owned(4), [60,67,69,65,64,62])
        for level in 0..<5 {
            let score = SongScore(level: level)
            XCTAssertEqual(score.marks.filter { $0 == .waiting }.count, Music.required(level))
            XCTAssertEqual(Music.practice(level).count, 4)
            XCTAssertTrue(Music.practice(level).allSatisfy { Music.owned(level).contains($0) })
        }
    }
    func testChromaticPianoNamesAndStaff() {
        for midi in 21...108 {
            XCTAssertEqual(Music.name(midi), Music.names[midi % 12])
            if Music.sharp(midi) {
                XCTAssertTrue(Music.name(midi).contains("♯"))
                XCTAssertEqual(Music.staffStep(midi), Music.staffStep(midi - 1))
                XCTAssertFalse(Music.same(Double(midi), midi - 1))
                XCTAssertFalse(Music.same(Double(midi), midi + 1))
            }
        }
        XCTAssertEqual(Music.staffStep(60), 0)
        XCTAssertEqual(Music.staffStep(64), 2)
        XCTAssertEqual(Music.staffStep(72), 7)
    }
    func testCompleteSongScoringAtBothTempos() {
        for bpm in [72.0, 96.0] {
            for level in 0..<5 {
                var score = SongScore(level: level)
                for note in Music.melody where Music.owned(level).contains(note.midi) {
                    XCTAssertTrue(score.receive(midi: Double(note.midi), beat: note.start + 0.12 * bpm / 60,
                                                bpm: bpm, time: note.start * 60 / bpm + 1, attack: true, flexible: true))
                }
                score.expire(beat: 60, flexible: true)
                XCTAssertEqual(score.hits, Music.required(level))
                XCTAssertEqual(score.misses, 0)
            }
        }
    }
    func testTimingAndDuplicateAttacks() {
        var score = SongScore(level: 0)
        XCTAssertFalse(score.receive(midi: 61, beat: 0.2, bpm: 72, time: 1, attack: true, flexible: true))
        XCTAssertFalse(score.receive(midi: 60, beat: -2, bpm: 72, time: 1, attack: true, flexible: true))
        XCTAssertTrue(score.receive(midi: 72, beat: 0.2, bpm: 72, time: 1, attack: true, flexible: true))
        XCTAssertFalse(score.receive(midi: 60, beat: 1, bpm: 72, time: 1.1, attack: false, flexible: true))
        score.expire(beat: 60, flexible: true)
        XCTAssertEqual(score.misses, Music.required(0) - 1)
    }
    func testYINAllTwelvePitchClassesAndSilence() {
        let rate = 16000.0
        for midi in 60...71 {
            let samples = (0..<2048).map { i -> Float in
                let t = Double(i) / rate, f = Music.frequency(midi)
                return Float(0.25 * sin(2 * .pi * f * t) + 0.05 * sin(4 * .pi * f * t))
            }
            var tracker = PitchTracker()
            _ = tracker.analyze(samples, sampleRate: rate, time: 0)
            let result = tracker.analyze(samples, sampleRate: rate, time: 0.032)
            XCTAssertGreaterThan(result.confidence, 0.9)
            XCTAssertGreaterThan(result.hz, 0)
            XCTAssertEqual(Music.midi(result.hz), Double(midi), accuracy: 0.1)
        }
        var tracker = PitchTracker()
        XCTAssertLessThan(tracker.analyze([Float](repeating: 0, count: 2048), sampleRate: rate, time: 1).hz, 0)
    }
    func testNarrationGateRequiresAECOnsetAndStability() {
        var gate = NarrationGate()
        let onset = PitchResult(hz: 261.63, confidence: 0.99, rms: 0.1, attack: true)
        let sustain = PitchResult(hz: 261.63, confidence: 0.99, rms: 0.08, attack: false)
        for i in 0..<10 { XCTAssertFalse(gate.accepts(onset, time: Double(i) / 20, echoReady: false)) }
        XCTAssertFalse(gate.accepts(onset, time: 1, echoReady: true))
        XCTAssertFalse(gate.accepts(sustain, time: 1.05, echoReady: true))
        XCTAssertTrue(gate.accepts(sustain, time: 1.16, echoReady: true))
        gate.reset()
        XCTAssertFalse(gate.accepts(sustain, time: 2, echoReady: true))
        XCTAssertFalse(gate.accepts(sustain, time: 2.2, echoReady: true))
        let speech = PitchResult(hz: 261.63, confidence: 0.7, rms: 0.1, attack: true)
        XCTAssertFalse(gate.accepts(speech, time: 3, echoReady: true))
    }
    func testBackingIsFiniteAndHasFourBeatCountIn() {
        for bpm in [72.0, 96.0] {
            let samples = BackingSynth.render(bpm: bpm, level: 4)
            XCTAssertEqual(samples.count, Int(52 * 60 / bpm * BackingSynth.sampleRate) + 4096)
            XCTAssertTrue(samples.allSatisfy { $0.isFinite && abs($0) <= 1 })
            XCTAssertGreaterThan(samples.prefix(2000).map { abs($0) }.max()!, 0.01)
        }
    }
}
