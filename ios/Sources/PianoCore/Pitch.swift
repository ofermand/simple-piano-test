import Foundation

public struct PitchResult: Sendable {
    public let hz: Double
    public let confidence: Double
    public let rms: Double
    public let attack: Bool
    public init(hz: Double, confidence: Double, rms: Double, attack: Bool) {
        self.hz = hz; self.confidence = confidence; self.rms = rms; self.attack = attack
    }
}

/// YIN with overlapping windows. This estimates pitch, not instrument identity.
public struct PitchTracker {
    private var previousHz = -1.0, energy = 0.001, attackAt = -1.0
    private var stableCount = 0
    public init() {}
    public mutating func analyze(_ samples: [Float], sampleRate: Double, time: Double) -> PitchResult {
        guard samples.count >= 1024, sampleRate > 0 else {
            return PitchResult(hz: -1, confidence: 0, rms: 0, attack: false)
        }
        let mean = samples.reduce(0.0) { $0 + Double($1) } / Double(samples.count)
        let x = samples.map { Double($0) - mean }
        let rms = sqrt(x.reduce(0) { $0 + $1 * $1 } / Double(x.count))
        let attack = rms > 0.004 && rms > energy * 1.35 && time - attackAt > 0.12
        if attack { attackAt = time }
        energy = energy * 0.85 + rms * 0.15
        guard rms >= 0.0025 else {
            stableCount = 0; previousHz = -1
            return PitchResult(hz: -1, confidence: 0, rms: rms, attack: false)
        }
        let maxTau = min(Int(sampleRate / 110), x.count / 2 - 2)
        let minTau = max(2, Int(sampleRate / 1000))
        guard maxTau > minTau else { return PitchResult(hz: -1, confidence: 0, rms: rms, attack: false) }
        var normalized = [Double](repeating: 1, count: maxTau + 2)
        var sum = 0.0
        for tau in 1...maxTau+1 {
            var difference = 0.0
            for j in 0..<x.count/2 { let d = x[j] - x[j+tau]; difference += d*d }
            sum += difference
            normalized[tau] = sum < 1e-10 ? 1 : difference * Double(tau) / sum
        }
        var chosen: Int?
        var tau = minTau
        while tau <= maxTau {
            if normalized[tau] < 0.26 {
                while tau < maxTau && normalized[tau+1] < normalized[tau] { tau += 1 }
                chosen = tau; break
            }
            tau += 1
        }
        if chosen == nil { chosen = (minTau...maxTau).min { normalized[$0] < normalized[$1] } }
        guard let c = chosen, normalized[c] < 0.40 else {
            previousHz = -1; stableCount = 0
            return PitchResult(hz: -1, confidence: 0, rms: rms, attack: false)
        }
        let denominator = 2 * (2 * normalized[c] - normalized[c-1] - normalized[c+1])
        let shift = abs(denominator) > 1e-10 ? max(-0.5, min(0.5, (normalized[c+1]-normalized[c-1])/denominator)) : 0
        let hz = sampleRate / (Double(c) + shift)
        if previousHz > 0 && abs(12 * log2(hz / previousHz)) < 0.65 { stableCount += 1 } else { stableCount = 1 }
        previousHz = hz
        return PitchResult(hz: stableCount >= 2 ? hz : -1, confidence: 1-normalized[c], rms: rms,
                           attack: attack || time - attackAt < 0.22)
    }
}

public struct NarrationGate {
    private var pitch = -1.0, stableAt = -1.0, attackAt = -1.0
    public init() {}
    public mutating func reset() { self = NarrationGate() }
    public mutating func accepts(_ result: PitchResult, time: Double, echoReady: Bool) -> Bool {
        guard echoReady, result.hz > 0, result.confidence >= 0.90 else { reset(); return false }
        let midi = Music.midi(result.hz)
        if pitch < 0 || abs(midi - pitch) > 0.25 { stableAt = time; attackAt = -1 }
        pitch = midi
        if result.attack { attackAt = time }
        return attackAt >= 0 && time - attackAt <= 0.4 && time - stableAt >= 0.14
    }
}
