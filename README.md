# Flashlight

An Android flashlight that works like the iOS one: a tall slider you drag to dim or brighten the light smoothly, plus a round on/off button.

- Uses Android 13+'s torch brightness API, so it can use every brightness step the phone's flash supports (not just a few presets).
- Keeps the light on when you leave the app, with a "Turn off" notification.
- Adds a **Flashlight+** Quick Settings tile: tap to toggle, long-press to open the app.

## Install

Download the latest APK on your phone:
https://github.com/ssymba0/flashlight/releases/latest/download/Flashlight.apk

Open it and allow your browser to install apps when Android asks.

## Build

GitHub Actions builds the APK on every push (`.github/workflows/build.yml`). Locally: `./gradlew assembleRelease`.
