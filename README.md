# Simple Piano TEST · Native Android (v0.1)

A runnable native Android **test project**, based on Simple Piano's landscape gameplay UI.

## What is implemented

- Native Android app written in Java (no web view, no React, no Kotlin dependency).
- Landscape gameplay for the public-domain melody **Twinkle Twinkle Little Star / כוכב קטן**.
- A 4-beat count-in, colored notes on a five-line staff, continuous movement across a stationary playhead, and a rhythmic accompaniment (pads, bass, simple percussion).
- Microphone permission and capture (`AudioRecord`) with a built-in YIN monophonic pitch estimator.
- Detected pitch on screen, a microphone input meter, green played notes, red missed notes, and completion statistics.
- 72/96 BPM test modes, pause/resume and restart.
- No account, no network permissions, no telemetry, no audio uploads, and no recorded audio files.

## Important test limitations

This is a **prototype, not production-grade pitch recognition**. Acoustic piano timbre, room noise and speaker leakage can cause false detections or misses. The native audio pipeline has not been tested on the user's physical Android device. The accompaniment is synthesized locally, not a mastered commercial backing track. Tap the phone's microphone permission prompt to enable detection. Put the phone close enough to hear the piano; start with the accompaniment volume low or with headphones to reduce crosstalk.

Pitch detection is intended for individual melody notes in the **C4–C5** range. It does not recognize complex chords or polyphonic performances.

## Build APK in GitHub Actions (without Android Studio)

1. Create a new GitHub repository, e.g. `simple-piano-test`.
2. Upload **all the files in this project**, keeping the `.github/workflows/android-apk.yml` path.
3. Push to branch `main` or open **Actions > Build installable Android APK > Run workflow**.
4. Open the successful workflow run; under **Artifacts**, download `Simple-Piano-TEST-Android-APK`.
5. Unzip the artifact and copy `app-debug.apk` to your Android phone. Open it and allow installation from the file manager/browser when prompted.
6. Launch **Simple Piano TEST**, approve Microphone, tap **התחלה**, listen to four count-in beats, and play C4, C4, G4, G4, A4, A4, G4… on a physical piano.

The APK is a debug build suitable for private testing, not an app-store release.

## Local build (optional)

Requirements: Android SDK Platform 35, Build Tools 35, JDK 17, Gradle 8.7. Run:

```shell
gradle :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Sound/architecture

- `MainActivity.java`: game state, microphone permission, timing, UI.
- `PitchDetector.java`: streaming PCM capture, normalized YIN difference function and pitch confidence.
- `BackingPlayer.java`: synthesizes a dedicated 16-beat backing arrangement and four-beat count-in using `AudioTrack`.
- `.github/workflows/android-apk.yml`: performs cloud build and publishes the debug APK as a GitHub Actions artifact.

Known audio tradeoff: the phone speakers may be captured by the microphone. For the v0.1 test, reduce accompaniment volume to isolate your piano, and assess recognition accuracy before adding more instruments to the backing band.
