# Simple Piano for iPhone

Native SwiftUI/AVFoundation port of the runnable **Little Star** Android game,
for iOS 16 or later. This directory is a self-contained Xcode application and
portable Swift package. It needs no third-party Swift dependencies or web service.

## Included game

- Hebrew welcome, two-note staff lessons, four-note microphone practice,
  42-note/48-beat Little Star song, results, repeat and incremental unlocks.
- Five levels: C/G, then A, F, E and D. Unlocked levels and voice preference
  persist on the phone. The player may revisit unlocked levels.
- Treble staff, moving score and stationary playhead, white/black reference
  keys, sharp names, pitch display and input meter. Tapping the reference
  keyboard does not fake microphone success.
- 72/96 BPM, a four-beat audible count-in, pause/resume, restart, forgiving or
  strict timing, octave tolerance, per-note scoring and accompaniment volume.
- Locally synthesized pads, bass, percussion and unowned melody notes. The
  accompaniment never synthesizes the child's owned melody part.
- On-device YIN pitch detection (about 110–1000 Hz), with stable-window/onset
  tracking. All 12 chromatic pitch names are represented, including sharps.
- Hebrew speech through Apple's installed speech voice. If unavailable, the
  UI continues with text and reports the missing Hebrew voice. No generated
  Android MP3 assets were present to transfer.
- AVAudioEngine voice processing for echo cancellation where available.
  During narration, high confidence, a recent onset and 140 ms stability are
  required before a correct note can interrupt the prompt. Without voice
  processing, microphone credit waits until narration and a 350 ms tail end.
- Microphone permission handling, recovery instructions, and explicit shutdown
  on backgrounding, incoming calls and disconnected audio routes. Resume through
  the welcome screen; the app does not restart recording automatically.
- Original app icon, microphone purpose string and privacy manifest. Audio
  stays in memory; there are no recording files, uploads, accounts or analytics.

The optional Android Golden content pack was **not** supplied in the checkout.
This app ships Little Star only; it does not contain or claim to play Golden.
There is no file picker, purchased music or remote download in the child flow.

## Open and run on a Mac

1. Use Xcode 16 or newer. Open `SimplePiano.xcodeproj` (already generated).
2. Select target **SimplePiano → Signing & Capabilities**, choose your Apple
   development Team, and change `com.simplepianotest.ios` to a bundle identifier
   you control. No team or credentials are committed to this repository.
3. Connect an iPhone running iOS 16+, enable Developer Mode if prompted, select
   it as the destination, and run. Approve microphone permission on the phone.
4. If needed, install a Hebrew system speech voice in iOS Settings. Without one,
   lessons still show their instructions on screen.

A free personal Apple ID can be used for restricted local development signing.
TestFlight/App Store distribution requires Apple Developer Program membership,
an App Store Connect app record, signing, and Apple's processing/review.

```sh
# Portable game/DSP tests (also works on Linux with Swift 5.9+).
swift test --package-path .

# On macOS, from this directory: build without signing for Simulator.
xcodebuild -project SimplePiano.xcodeproj -scheme SimplePiano \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build/simulator CODE_SIGNING_ALLOWED=NO build

# On macOS: compile the device target without producing an installable IPA.
xcodebuild -project SimplePiano.xcodeproj -scheme SimplePiano \
  -configuration Release -destination 'generic/platform=iOS' \
  -derivedDataPath build/device CODE_SIGNING_ALLOWED=NO build
```

For a signed distribution build, select a real device/generic iOS destination,
choose **Product → Archive**, then **Distribute App → App Store Connect**. Do not
rename a simulator app or source ZIP to `.ipa`: neither installs on an iPhone.
The repository workflow `.github/workflows/ios.yml` runs tests and unsigned
simulator/device builds on a Mac after the code is pushed; the first unsigned macOS build passed on 2026-11-17: [Actions run #1](https://github.com/ofermand/simple-piano-test/actions/runs/44161516993), code commit `0fa5190f6afad7e25ed0b0f3874546ccd9e5eb71`.

## Validation and limits

Validated here on Linux using Swift 6.0.3: **seven XCTest cases pass**, exercising
all five levels, scoring a complete song at both tempos, timing/duplicates,
chromatic naming/staff positions, synthetic piano-frequency recognition,
silence, narration confirmation and generated backing samples. All application
Swift files also pass syntax parsing. The Xcode project, scheme, plist, resource
references and icon are checked structurally.

**Validated on GitHub's macOS runner:** portable Swift tests, the Debug iPhone
Simulator build, and the unsigned Release build for physical iPhone all passed
in [run #1](https://github.com/ofermand/simple-piano-test/actions/runs/44161516993),
code commit `0fa5190f6afad7e25ed0b0f3874546ccd9e5eb71`. The run produced a simulator
app ZIP and test log. No Apple signing credentials were required or supplied.

**Not yet validated:** simulator launch, signing, installation, real microphone
accuracy, playback synchronization and AEC on a physical iPhone. The local host
has no Apple SDK/Xcode; SDK compilation was performed by GitHub's macOS runner.
Compilation and core tests do not establish successful acoustic separation. Voice
processing can affect piano timbre; onset/confidence checks are heuristics and
cannot guarantee speech/instrument separation. Simulator tests cannot establish
speaker/microphone performance.

Before calling this ready for children, on a real iPhone verify:

1. Remain silent through every prompt: no lesson/practice progress is awarded.
2. Play the requested note during narration: it interrupts only after a stable
   match; wrong notes and ordinary speech do not advance the exercise.
3. Repeat with low/high speaker volume, quiet piano notes and a reflective room.
4. Test all five levels at both tempos; confirm count-in, pause, restart, missed
   notes, results, and reopening the app restores unlocked levels.
5. Deny microphone permission, reopen Settings, take a call, background the app
   and disconnect an audio route. Confirm recovery and no background capture.

## Files

- `Sources/PianoCore`: portable score/progression, pitch tracking and backing.
- `App`: SwiftUI screens, session lifecycle, AVAudioEngine capture/playback and TTS.
- `Tests/PianoCoreTests`: seven executable regression tests.
- `generate_project.py`: deterministic, standard-library-only Xcode project
  generator. Run after adding/removing Swift files; it preserves no local Xcode
  project edits, so keep personal signing changes local or pass them to xcodebuild.

Android sources and previous APKs are independent of this iOS target.
