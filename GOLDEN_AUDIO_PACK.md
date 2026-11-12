# Golden test content pack

The child-facing app never asks for music files.

For a **Golden** build, add these two files to `app/src/main/assets/`:

- `golden_melody.mid` — a melody-only MIDI sequence, with note timing aligned to the instrumental recording. If multiple tracks are present, name the melody track **Rumi**, **Vocal Melody**, or **Main Melody**.
- `golden_instrumental.mp3` — the matching full instrumental recording, starting at the same timestamp as the MIDI.

Optional, for natural-sounding guide melody in place of the basic synthesizer:

- `golden_guide_level_1.wav` — audio only for melody notes the app plays in level 1 (all other note positions silent)
- `golden_guide_level_2.wav` etc. — one pre-rendered guide for each unlocked level

The Android launcher checks for the first two files. When bundled, it automatically opens the Golden learning sequence. When absent, it launches the previously validated Little Star test so that it never mislabels demo music as Golden.

## Child flow

Song → Learn two notes on staff + mini keyboard → Four-note microphone practice → Full song → Results → Unlock one new pitch → Repeat whole song.

The instrumental **must not include a prominent guide melody** that plays the child's assigned notes: the microphone cannot reliably distinguish a phone's speaker from a piano playing that same note.

Audio and MIDI are never downloaded by the installed app. Any source-provider purchase or account action happens before creating the build. The app requires no child account, file-picker, settings page, or network permission.
