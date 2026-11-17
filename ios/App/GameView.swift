import SwiftUI
import UIKit

private let ink = Color(red: 0.07, green: 0.15, blue: 0.25)
private let mint = Color(red: 0.05, green: 0.62, blue: 0.49)
private let palette: [Color] = [.pink, .pink, .orange, .orange, .yellow, .green, .green, .teal, .teal, .blue, .blue, .purple]
private func noteColor(_ midi: Int) -> Color { palette[(midi % 12 + 12) % 12] }

struct GameView: View {
    @ObservedObject var game: GameModel
    private let ticker = Timer.publish(every: 1.0/30, on: .main, in: .common).autoconnect()
    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(spacing: 12) {
                    header
                    HStack(alignment: .top, spacing: 16) {
                        VStack(spacing: 12) { mainContent }
                            .frame(maxWidth: .infinity)
                        if geometry.size.width > 760 { monitor.frame(width: 175) }
                    }
                    if geometry.size.width <= 760 { monitor }
                    if let error = game.error {
                        VStack {
                            Text(error).foregroundStyle(.red)
                            if game.permissionDenied {
                                Link("פתיחת הגדרות המיקרופון", destination: URL(string: UIApplication.openSettingsURLString)!)
                            }
                        }.padding().background(.white, in: RoundedRectangle(cornerRadius: 16))
                    }
                }.padding(16).frame(maxWidth: 1200).frame(maxWidth: .infinity)
            }.background(Color(red: 0.93, green: 0.97, blue: 0.98))
        }
        .foregroundStyle(ink)
        .font(.system(.body, design: .rounded))
        .buttonStyle(.borderedProminent).tint(mint)
        .onReceive(ticker) { _ in game.tick() }
    }
    private var header: some View {
        HStack {
            VStack(alignment: .leading) {
                Text("סימפל פיאנו").font(.title2.bold())
                Text("כוכב קטן · שלב \(game.level + 1) מתוך 5").font(.subheadline)
            }
            Spacer()
            Button {
                game.voiceEnabled.toggle()
            } label: {
                Label(game.voiceEnabled ? "קול פועל" : "ללא קול", systemImage: game.voiceEnabled ? "speaker.wave.2.fill" : "speaker.slash.fill")
            }.accessibilityLabel("הפעלה או השתקה של הקריינות")
            if game.stage != .welcome {
                Button("בית") { game.suspend() }.tint(.gray)
            }
        }
    }
    @ViewBuilder private var mainContent: some View {
        switch game.stage {
        case .welcome:
            VStack(spacing: 16) {
                Image(systemName: "pianokeys").font(.system(size: 60)).foregroundStyle(mint)
                Text("הפסנתר שלכם. הלהקה שלנו.").font(.largeTitle.bold())
                Text("נכיר תווים, נתרגל ארבעה צלילים, ואז ננגן יחד את כוכב קטן.")
                Text("הניחו את האייפון ליד הפסנתר. התווים הצבעוניים הם התווים שלכם.")
                HStack {
                    Text("שלב לימוד")
                    Picker("שלב לימוד", selection: Binding(get: { game.level }, set: { game.selectLevel($0) })) {
                        ForEach(0...game.unlocked, id: \.self) { Text("\($0 + 1)").tag($0) }
                    }.pickerStyle(.segmented)
                }
                HStack {
                    Text("מהירות")
                    Picker("מהירות", selection: $game.bpm) {
                        Text("72 BPM").tag(72.0); Text("96 BPM").tag(96.0)
                    }.pickerStyle(.segmented)
                }
                Toggle("תזמון סלחני וקבלת אוקטבה נוספת", isOn: $game.flexible)
                Button("התחלה") { game.startLessons() }.disabled(game.preparing)
                if game.preparing { ProgressView("מפעילים מיקרופון…") }
                Text("אין חשבון, אין העלאת אודיו ואין שמירת הקלטות. נדרשת הרשאת מיקרופון.")
                    .font(.footnote).foregroundStyle(.secondary)
            }.card()
        case .lesson:
            if let target = game.target {
                VStack(spacing: 12) {
                    Text("מכירים את \(Music.name(target))").font(.title.bold())
                    Text(game.message).font(.headline)
                    StaffView(notes: [Note(target, 0, 1)], marks: [game.lessonLearned ? .hit : .waiting], beat: nil)
                        .frame(height: 145)
                    PianoKeyboard(target: target, success: game.lessonLearned).frame(height: 95)
                    Button(game.lessonLearned ? "המשך" : "נגנו את התו בפסנתר") { game.nextLesson() }
                        .disabled(!game.lessonLearned)
                }.card()
            }
        case .practice:
            VStack(spacing: 12) {
                Text("תרגול קצר").font(.title.bold())
                Text(game.message).font(.headline)
                HStack {
                    ForEach(Array(game.pattern.enumerated()), id: \.offset) { index, pitch in
                        Text(Music.name(pitch)).font(.title3.bold()).frame(maxWidth: .infinity).padding(10)
                            .background(index < game.practiceStep ? mint : noteColor(pitch).opacity(0.2), in: RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(index == game.practiceStep ? mint : .clear, lineWidth: 3))
                    }
                }.environment(\.layoutDirection, .leftToRight)
                StaffView(notes: [Note(game.target ?? game.pattern.last!, 0, 1)], marks: [.waiting], beat: nil).frame(height: 140)
                PianoKeyboard(target: game.target, success: false).frame(height: 95)
                if game.practiceStep == game.pattern.count {
                    Button("מנגנים את השיר") { game.startSong() }.disabled(game.preparing)
                }
                if game.preparing { ProgressView("מכינים את הלהקה…") }
            }.card()
        case .song:
            VStack(spacing: 12) {
                HStack {
                    Text(game.beat < 0 ? "ספירה: \(max(1, Int(ceil(-game.beat))))" : "מנגנים עם הלהקה").font(.title2.bold())
                    Spacer()
                    Text("✓ \(game.score.hits) / \(Music.required(game.level))").font(.headline)
                }
                Text(game.message)
                StaffView(notes: Music.melody, marks: game.score.marks, beat: game.beat).frame(height: 190)
                ProgressView(value: min(1, max(0, game.beat / 48))).tint(mint)
                HStack {
                    Button(game.playing ? "השהיה" : "המשך") { game.togglePlayback() }
                    Button("התחלה מחדש") { game.startSong() }.disabled(game.preparing)
                    Button("חזרה לתרגול") { game.startPractice() }.disabled(game.preparing)
                }
                if game.preparing { ProgressView("מכינים ליווי…") }
            }.card()
        case .results:
            VStack(spacing: 18) {
                Text("ניגנתם שיר שלם!").font(.largeTitle.bold())
                Text("\(game.accuracy)%").font(.system(size: 52, weight: .bold)).foregroundStyle(mint)
                Text("\(game.score.hits) תווים נכונים · \(game.score.misses) תווים שהוחמצו")
                HStack {
                    Button("שוב עם הלהקה") { game.startSong() }.disabled(game.preparing)
                    Button("תרגול נוסף") { game.startPractice() }
                    if game.level < 4 { Button("מגלים תו חדש") { game.unlockNext() } }
                }
                if game.level == 4 { Text("למדתם את כל תווי השיר! ⭐️") }
                if game.preparing { ProgressView("מכינים ליווי…") }
            }.card()
        }
    }
    private var monitor: some View {
        VStack(spacing: 10) {
            Text("🎙 נשמע עכשיו").font(.headline)
            Text(game.heard).font(.largeTitle.bold()).accessibilityLabel("תו שזוהה: \(game.heard)")
            ProgressView(value: game.rms).tint(mint).accessibilityLabel("עוצמת קלט המיקרופון")
            if game.microphoneReady {
                Text(game.echoReady ? "עיבוד קול וביטול הד פעילים" : "ביטול הד אינו זמין: מחכים לסיום ההנחיה")
                    .font(.caption)
                if game.speaking { Text("הקריין מדבר…").font(.caption) }
            }
            HStack {
                Image(systemName: "music.note")
                Slider(value: $game.volume, in: 0...0.8).accessibilityLabel("עוצמת הליווי")
            }
            Text("הקלידים על המסך הם מדריך; נגנו בפסנתר האמיתי.").font(.caption).foregroundStyle(.secondary)
        }.card()
    }
}

private extension View {
    func card() -> some View { padding(16).background(.white, in: RoundedRectangle(cornerRadius: 22)) }
}

struct StaffView: View {
    let notes: [Note]
    let marks: [Mark]
    let beat: Double?
    var body: some View {
        Canvas { context, size in
            let gap: CGFloat = 19, bottom = size.height * 0.69
            for line in 0..<5 {
                var path = Path(); let y = bottom - CGFloat(line) * gap
                path.move(to: CGPoint(x: 12, y: y)); path.addLine(to: CGPoint(x: size.width - 12, y: y))
                context.stroke(path, with: .color(.gray.opacity(0.6)), lineWidth: 1)
            }
            context.draw(Text("𝄞").font(.system(size: 75)).foregroundColor(ink), at: CGPoint(x: 32, y: bottom - 36))
            let playhead = max(90, size.width * 0.26)
            for (i, n) in notes.enumerated() {
                let x = beat.map { playhead + CGFloat(n.start - $0) * 68 } ?? size.width * 0.48
                guard x >= 65, x < size.width + 20 else { continue }
                let y = bottom + gap - CGFloat(Music.staffStep(n.midi)) * gap / 2
                let mark = marks.indices.contains(i) ? marks[i] : .waiting
                let color: Color = mark == .hit ? mint : mark == .missed ? .red : mark == .supplied ? .gray.opacity(0.45) : noteColor(n.midi)
                if Music.staffStep(n.midi) <= 0 {
                    var ledger = Path(); ledger.move(to: CGPoint(x: x-17, y: bottom+gap)); ledger.addLine(to: CGPoint(x: x+17, y: bottom+gap))
                    context.stroke(ledger, with: .color(.gray), lineWidth: 2)
                }
                context.fill(Path(ellipseIn: CGRect(x: x-11, y: y-7, width: 22, height: 14)), with: .color(color))
                var stem = Path(); stem.move(to: CGPoint(x: x+9, y: y)); stem.addLine(to: CGPoint(x: x+9, y: y-35))
                context.stroke(stem, with: .color(color), lineWidth: 2)
                if Music.sharp(n.midi) { context.draw(Text("♯").foregroundColor(color), at: CGPoint(x: x-23, y: y)) }
                context.draw(Text(Music.name(n.midi)).font(.caption.bold()).foregroundColor(color), at: CGPoint(x: x, y: size.height-10))
            }
            if beat != nil {
                var line = Path(); line.move(to: CGPoint(x: playhead, y: 5)); line.addLine(to: CGPoint(x: playhead, y: size.height-25))
                context.stroke(line, with: .color(mint), lineWidth: 3)
            }
        }.environment(\.layoutDirection, .leftToRight)
        .accessibilityLabel(beat == nil ? "תו על החמשה: \(notes.first.map { Music.name($0.midi) } ?? "")" : "חמשה עם תווים נעים לעבר קו הנגינה")
    }
}

struct PianoKeyboard: View {
    let target: Int?
    let success: Bool
    private let whites = [60,62,64,65,67,69,71,72]
    var body: some View {
        GeometryReader { geometry in
            let width = geometry.size.width / 8
            ZStack(alignment: .topLeading) {
                HStack(spacing: 1) {
                    ForEach(whites, id: \.self) { pitch in
                        VStack {
                            Spacer()
                            Text(Music.name(pitch)).font(.caption.bold()).padding(.bottom, 6)
                        }.frame(maxWidth: .infinity).frame(height: geometry.size.height)
                            .background(target == pitch ? (success ? mint : noteColor(pitch)) : Color.white)
                            .overlay(RoundedRectangle(cornerRadius: 4).stroke(.gray))
                    }
                }
                ForEach([61,63,66,68,70], id: \.self) { pitch in
                    let index = whites.firstIndex(of: pitch-1) ?? 0
                    RoundedRectangle(cornerRadius: 4)
                        .fill(target == pitch ? noteColor(pitch) : ink)
                        .frame(width: width * 0.58, height: geometry.size.height * 0.6)
                        .overlay(alignment: .bottom) {
                            Text(Music.name(pitch)).font(.system(size: 10)).foregroundStyle(.white).padding(.bottom, 4)
                        }
                        .offset(x: CGFloat(index + 1) * width - width * 0.29)
                }
            }
        }.environment(\.layoutDirection, .leftToRight)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(target.map { "מצאו בפסנתר את \(Music.name($0))" } ?? "מקלדת פסנתר")
    }
}
