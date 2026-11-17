import SwiftUI
import AVFoundation

@main
@MainActor
struct SimplePianoApp: App {
    @StateObject private var game = GameModel()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            GameView(game: game)
                .environment(\.layoutDirection, .rightToLeft)
                .onChange(of: scenePhase) { value in
                    if value == .background { game.suspend() }
                }
                .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)) { notification in
                    if let type = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                       type == AVAudioSession.InterruptionType.began.rawValue { game.suspend() }
                }
                .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification)) { notification in
                    if let reason = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                       reason == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue { game.suspend() }
                }
        }
    }
}
