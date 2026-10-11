<div align="center">

<img src="docs/icon.svg" alt="FossFiles icon" width="128" />

# FossFiles

**A native Android file manager with no telemetry, no Google services and no bloat.**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-3DDC84.svg?logo=android&logoColor=white)](#)
[![Language: Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF.svg?logo=kotlin&logoColor=white)](#)
[![UI: Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4.svg?logo=jetpackcompose&logoColor=white)](#)
[![minSdk 26](https://img.shields.io/badge/minSdk-26-brightgreen.svg)](#)
[![No INTERNET permission](https://img.shields.io/badge/INTERNET-not%20requested-brightgreen.svg)](#)

</div>

FossFiles is a native Android file manager written in **a single language: Kotlin** (logic + UI with
Jetpack Compose). The goal: something like Google Files — complete, but **without telemetry, without
Google services (GMS), light and fast**.

## Project policies

### Zero Internet, zero telemetry (a verifiable guarantee, not a promise)
- `AndroidManifest.xml` **does not declare `android.permission.INTERNET`**. Without that permission,
  Android blocks any network connection at the OS level, so the app could not send data to any server
  even if the code tried to.
- Zero third-party SDKs: no Firebase, Crashlytics, Analytics, Ads or Google Play Services.
  Only AndroidX, Compose and kotlinx-coroutines. (JUnit is test-only and never ships in the APK.)
- Verify it on any built APK: `aapt2 dump permissions app-release.apk` must not list `INTERNET`.
- All the code lives in this repo; you can read the whole thing in one sitting.

### Zero animations (so it feels — and is — fast)
- The app animates nothing on its own: no screen transitions, no touch ripple, no sliding sheets or
  menus, no indeterminate progress bars (which animate forever and drain battery).
- Dialogs are just another layer of the screen (not system windows), so they appear instantly.
- Notices and errors show up in a fixed strip at the bottom (tap to dismiss), not in a Toast.
- The overscroll "stretch" effect at the end of a list and the theme's window animations are disabled.
- Limitations that belong to the system and cannot be removed from the app: the Android 12+ splash
  screen and Android's own keyboard/gesture animations.

## Features
- Browse folders, go up a level, search by name (up to 6 levels deep; the previous search is cancelled as you keep typing)
- Sort by name, date, size or type (folders always first)
- **Multi-selection** (long-press): copy, cut, rename (one at a time), delete and select all
- Create a folder, paste into the current folder
- Copy without overwriting: if the name exists, it creates `name (1)`, `name (2)`…
- Move = instant rename on the same volume (copy + delete only when crossing volumes)
- Confirmation before deleting
- Categories: Photos, Videos, Audio, Downloads and Documents (via MediaStore, no disk scan)
- Per-type icons and human-readable sizes (KB/MB/GB)
- Back button: closes the selection, goes up a folder, or returns home

## Requirements
- JDK 17 or newer (the project targets Java 17 and builds fine on JDK 21)
- Android SDK with `platforms;android-34` and `build-tools;34.0.0`
- A device or emulator running Android 8.0 (API 26) or newer

## Build from the command line
```bash
# Debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug

# Release APK (signed with the debug key, see notes below)
./gradlew assembleRelease
```

Install on a physical device (enable USB debugging first):
```bash
adb devices
./gradlew installDebug
# or, with an APK already built:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> The release build is signed with the **debug key** so you can install and test the optimized build
> without creating a keystore. Replace `signingConfigs.getByName("debug")` in `app/build.gradle.kts`
> with your real signing config before publishing anywhere.

## Build with Android Studio
1. Open the `fossfiles/` folder in Android Studio (Hedgehog or newer).
2. Let Gradle sync (it downloads Kotlin/Compose/AndroidX — these do need internet **once**, on your
   development machine, to build the app; the resulting APK does not).
3. Connect a device or use an emulator with Android 8.0 (API 26) or newer.
4. Run ▶.
5. The first time, the app asks for the "All files access" permission — it takes you straight to Settings to enable it.

## Build with Podman or Docker (no local toolchain)
Don't want to install a JDK, the Android SDK, Gradle or Kotlin? You only need **Podman** (or Docker):

```bash
./build-in-container.sh                    # release APKs (default)
./build-in-container.sh assembleDebug      # any Gradle task
./build-in-container.sh testDebugUnitTest  # run the JVM tests
ENGINE=docker ./build-in-container.sh      # use Docker instead of Podman
```

The first run builds a small builder image (`Containerfile`: JDK 17 + the Android SDK, with the
toolchain's telemetry off) and caches Gradle in `.container/gradle`, so later builds are fast. The
generated files belong to you, and your own `local.properties` is left untouched. This is the same
approach CI systems use, and it makes builds reproducible across machines.

## Testing
The file logic (`FileRepository`) does not depend on Android and has JVM tests:

```bash
./gradlew testDebugUnitTest
```

They cover: invalid names, copy-without-overwrite, moving to the same folder, a folder inside itself,
deleting without following symlinks, search depth and cancellation, and list ordering.

## Behavior notes
- Symlinks are never followed: copy/move skip them and delete removes only the link.
- Search shows at most 5,000 results and warns you when there are more.
- Cut and paste across volumes (e.g. to an SD card) copies and then deletes; if the original could not
  be deleted, the app tells you instead of failing silently.

## Possible next steps
- Image/video thumbnails (using the platform API, no third-party libraries)
- Shortcut bar to Downloads, DCIM, etc. and a Recents section
- Show/hide hidden files, grid view, ZIP compress/extract
- Share with other apps and file details/properties
- Sign the release APK with your own keystore for publishing (today it uses the debug key)

## License

FossFiles is free software under the **GNU General Public License v3.0** (or any later version).
You can use, study, modify and redistribute this program, as long as derivative works stay free under
the same license.

See the [LICENSE](LICENSE) file for the full text.

```
Copyright (C) 2026 Abemilek

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU General Public License for more details.
```
