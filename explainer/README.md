# 🎧 Explainers — AI audio explainers for Android

Type any topic → **Grok (xAI) writes a short, entertaining script** → your phone
narrates it in a voice you choose → saved as a real **.mp3** in `Music/Explainers`.

A native Android app (API 29+ / Android 10+), built without Gradle — plain Java,
zero AndroidX dependencies, one bundled pure-Java library.

## Features

- **Any topic, in English** — script written by the xAI API (default model `grok-3-mini`,
  changeable in settings), tuned for spoken audio: hook opening, fun facts, clear takeaway
- **Three lengths**: ≈1, ≈2 or ≈4 minutes
- **Voice picker** with instant preview — lists every offline English voice installed on the phone
- **Talking speed** slider (0.5×–2.0×), applied to the narration
- **Real MP3 export** (128 kbps) via [jump3r](https://github.com/Sciss/jump3r), the pure-Java
  LAME encoder (LGPL) — files land in `Music/Explainers`, visible to every music app,
  shareable straight from the library (long-press → Share)
- **Background playback**: a foreground media service with notification controls keeps
  the audio playing when you close the app or switch the screen off
- **Library** with duration/date, re-play, share, read-the-script, delete
- Your API key is stored only on the device and only sent to `api.x.ai`

## Setup (one time)

1. Install `dist/explainers.apk` (sideload; debug-signed).
2. Get an xAI API key at **console.x.ai**.
3. In the app, tap **⚙ API key**, paste it, save. Done.

## How it works

```
topic ──► xAI chat completions (script, plain spoken prose)
      ──► Android TextToSpeech.synthesizeToFile (chosen voice + speed) ──► WAV
      ──► jump3r LAME port ──► MP3 (128 kbps)
      ──► MediaStore insert ──► Music/Explainers/<topic>.mp3
      ──► foreground PlayerService (MediaPlayer + wake lock + notification)
```

## Building

```sh
ANDROID_SDK=/path/to/sdk ./build-apk.sh        # needs build-tools 35 + platform android-35 + JDK 11+
```

No Gradle, no Android Studio: `aapt2 → javac → d8 → zipalign → apksigner`.
`libs/jump3r-1.0.5.jar` is bundled (from Maven Central); its `javax.sound`-dependent
classes are stripped at build time since Android lacks that API.

## Notes

- Narration quality = your phone's TTS voices. Install more via
  *Settings → System → Languages → Text-to-speech* (Google Speech Services offers many).
- The xAI API is paid per token; a 2-minute script is a fraction of a cent on `grok-3-mini`.
- Tests: `Mp3Encoder` (WAV parse, mono/stereo encode, concat) and the xAI client
  (mock-server request/response/error) are exercised on a desktop JVM — see repo history.
