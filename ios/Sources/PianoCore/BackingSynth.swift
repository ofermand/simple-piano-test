import Foundation

public enum BackingSynth {
    public static let sampleRate = 22050.0
    /// No owned melody note is synthesized: success must come from the instrument.
    public static func render(bpm: Double, level: Int) -> [Float] {
        let seconds = 60 / bpm
        var mix = [Float](repeating: 0, count: Int(52 * seconds * sampleRate) + 4096)
        func tone(start: Double, duration: Double, midi: Int, amplitude: Double, pad: Bool = false) {
            let first = Int(start * sampleRate), length = min(Int(duration * sampleRate), mix.count - first)
            guard first >= 0, length > 0 else { return }
            let f = Music.frequency(midi)
            for i in 0..<length {
                let t = Double(i) / sampleRate
                let envelope = min(1, t / (pad ? 0.18 : 0.008)) * min(1, (duration - t) / 0.08) * exp(-t * (pad ? 0.25 : 3))
                mix[first+i] += Float(amplitude * envelope * (sin(2 * .pi * f * t) + 0.15 * sin(4 * .pi * f * t)))
            }
        }
        for b in 0..<4 { tone(start: Double(b) * seconds, duration: 0.09, midi: b == 0 ? 84 : 79, amplitude: 0.18) }
        let chords = [[48,52,55],[41,45,48],[41,45,48],[48,52,55]]
        for bar in 0..<12 {
            for pitch in chords[bar % 4] {
                tone(start: Double(4 + bar * 4) * seconds, duration: 3.7 * seconds, midi: pitch, amplitude: 0.026, pad: true)
            }
        }
        for b in 0..<48 {
            let start = Double(4+b) * seconds
            if b % 2 == 0 { tone(start: start, duration: 0.5 * seconds, midi: chords[(b/4)%4][0] - 12, amplitude: 0.11) }
            // Quiet, deterministic percussive beat; no microphone recording is persisted.
            let first = Int(start * sampleRate)
            var seed = UInt32(b + 1)
            for i in 0..<Int(sampleRate * 0.08) {
                seed = 1664525 &* seed &+ 1013904223
                let t = Double(i) / sampleRate
                let noise = Double(seed & 65535) / 32768 - 1
                let kick = b % 4 == 0 ? 0.15 * sin(2 * .pi * 65 * t) * exp(-t*30) : 0
                mix[first+i] += Float(kick + noise * (b % 4 == 2 ? 0.035 : 0.012) * exp(-t*65))
            }
        }
        for n in Music.melody where !Music.owned(level).contains(n.midi) {
            tone(start: (4+n.start) * seconds, duration: n.length * seconds * 0.9, midi: n.midi, amplitude: 0.12)
        }
        return mix.map { Float(tanh(Double($0) * 1.2)) }
    }
}
