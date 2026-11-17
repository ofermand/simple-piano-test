import Foundation

public struct Note: Equatable, Sendable {
    public let midi: Int
    public let start: Double
    public let length: Double
    public init(_ midi: Int, _ start: Double, _ length: Double) {
        self.midi = midi; self.start = start; self.length = length
    }
}

public enum Music {
    public static let names = ["דו", "דו♯", "רה", "רה♯", "מי", "פה", "פה♯", "סול", "סול♯", "לה", "לה♯", "סי"]
    public static func name(_ midi: Int) -> String { names[(midi % 12 + 12) % 12] }
    public static func frequency(_ midi: Int) -> Double { 440 * pow(2, Double(midi - 69) / 12) }
    public static func midi(_ hz: Double) -> Double { 69 + 12 * log2(hz / 440) }
    public static func sharp(_ midi: Int) -> Bool { [1, 3, 6, 8, 10].contains((midi % 12 + 12) % 12) }
    public static func same(_ heard: Double, _ target: Int, flexible: Bool = true, tolerance: Double = 0.48) -> Bool {
        let d = abs(heard - Double(target))
        return (flexible ? min(d, abs(d - 12)) : d) <= tolerance
    }
    /// Diatonic steps above middle C; sharps share their natural's staff position.
    public static func staffStep(_ midi: Int) -> Int {
        let octave = Int(floor(Double(midi) / 12)) - 5
        return octave * 7 + [0,0,1,1,2,3,3,4,4,5,5,6][(midi % 12 + 12) % 12]
    }
    public static let melody: [Note] = {
        let pitches = [60,60,67,67,69,69,67,65,65,64,64,62,62,60,
                       67,67,65,65,64,64,62,67,67,65,65,64,64,62,
                       60,60,67,67,69,69,67,65,65,64,64,62,62,60]
        var beat = 0.0
        return pitches.enumerated().map { i, pitch in
            let length = i % 7 == 6 ? 2.0 : 1.0
            defer { beat += length }
            return Note(pitch, beat, length)
        }
    }()
    public static let order = [60, 67, 69, 65, 64, 62]
    public static let levels = 5
    public static func owned(_ level: Int) -> [Int] { Array(order.prefix(min(6, max(2, level + 2)))) }
    public static func practice(_ level: Int) -> [Int] {
        let p = owned(level)
        return level == 0 ? [60,67,60,67] : [p[p.count-1],p[p.count-2],p[p.count-1],p[p.count-3]]
    }
    public static func required(_ level: Int) -> Int { melody.filter { owned(level).contains($0.midi) }.count }
}

public enum Mark: Int, Sendable { case waiting, hit, missed, supplied }

public struct SongScore: Sendable {
    public private(set) var marks: [Mark]
    public let level: Int
    private var lastHitTime = -Double.infinity
    private var lastPitch = -1
    public init(level: Int) {
        self.level = level
        marks = Music.melody.map { Music.owned(level).contains($0.midi) ? .waiting : .supplied }
    }
    public var hits: Int { marks.filter { $0 == .hit }.count }
    public var misses: Int { marks.filter { $0 == .missed }.count }
    public mutating func expire(beat: Double, flexible: Bool) {
        for i in marks.indices where marks[i] == .waiting {
            let n = Music.melody[i]
            if beat > n.start + n.length + (flexible ? 0.60 : 0.16) { marks[i] = .missed }
        }
    }
    @discardableResult
    public mutating func receive(midi: Double, beat: Double, bpm: Double, time: Double,
                                 attack: Bool, flexible: Bool) -> Bool {
        guard time - lastHitTime >= 0.19 else { return false }
        let played = beat - 0.12 * bpm / 60
        let candidates = marks.indices.filter { i in
            let n = Music.melody[i]
            return marks[i] != .hit && marks[i] != .supplied &&
                Music.same(midi, n.midi, flexible: flexible) &&
                played >= n.start - (flexible ? 0.45 : 0.16) &&
                played <= n.start + n.length + (flexible ? 0.60 : 0.16)
        }
        guard let i = candidates.min(by: {
            abs(played - Music.melody[$0].start) + (marks[$0] == .missed ? 0.08 : 0) <
            abs(played - Music.melody[$1].start) + (marks[$1] == .missed ? 0.08 : 0)
        }) else { return false }
        let pitch = Music.melody[i].midi
        guard pitch != lastPitch || attack || time - lastHitTime >= 60 / bpm * 0.63 else { return false }
        marks[i] = .hit; lastPitch = pitch; lastHitTime = time
        return true
    }
}
