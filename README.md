<div align="center">

<img src="docs/icon.svg" alt="FossFiles icon" width="128" />

# FossFiles

**A native Android file manager with no telemetry, no Google services and no bloat.**

[![Build](https://github.com/Abemilek/fossfiles/actions/workflows/build.yml/badge.svg)](https://github.com/Abemilek/fossfiles/actions/workflows/build.yml)
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

### 1. Zero Internet, zero telemetry (a verifiable guarantee, not a promise)
- `AndroidManifest.xml` **does not declare `android.permission.INTERNET`**. Without that permission,
  Android blocks any network connection at the OS level, so the app could not send data to any server
  even if the code tried to.
- Zero third-party SDKs: no Firebase, Crashlytics, Analytics, Ads or Google Play Services.
  Only AndroidX, Compose and kotlinx-coroutines. Thumbnails, the image viewer, the media player, ZIP,
  the trash bin and sharing use only system APIs (`ThumbnailUtils`, `BitmapFactory`, `MediaPlayer`,
  `java.util.zip`, `FileProvider`). JUnit is test-only and never ships in the APK.
- Your settings, favorites and trash bin are stored on the device only. If the app ever crashes, the
  last error is saved **locally** (Settings → Error log) so you can copy it into a bug report if you
  want; nothing is ever sent anywhere.
- Verify it on any built APK: `aapt2 dump permissions app-release.apk` must not list `INTERNET`.

### 2. Zero animations (so it feels — and is — fast)
- The app animates nothing on its own: no screen transitions, no touch ripple, no sliding sheets or
  menus, no indeterminate progress bars (which animate forever and drain battery).
- Dialogs are just another layer of the screen (not system windows), so they appear instantly.
- The image viewer and the media player animate nothing: they switch photo/track instantly, zoom is
  direct and the player controls are fixed (they never fade in or out).
- Notices and errors show up in a fixed strip at the bottom (tap to dismiss), not in a Toast.
- Limitations that belong to the system and cannot be removed from the app: the Android 12+ splash
  screen, Android's own keyboard/gesture animations and the "Share" chooser.

### 3. Light APK
- A single universal APK (no per-CPU split: the only native piece is a small AndroidX library,
  `libandroidx.graphics.path.so`, so one APK for every architecture is simpler and barely bigger),
  R8 with minification and resource shrinking, only the `es`/`en` strings from the libraries, no
  leftover metadata and no unused dependencies. Thumbnails and the viewer pull in no image library —
  they use the system one, with a bounded in-memory cache (1/16 of the heap).

## Features

**Home**
- A card per storage volume (internal storage and, if present, an **SD card / USB drive**) with the
  space used; tap one to open it.
- A **search box** that looks for names across the whole internal storage.
- **Quick access** (Downloads, DCIM, Pictures, Documents, Music, Movies — only the ones that exist),
  categories (Photos, Videos, Audio, Downloads, Documents, **Favorites**), **Large files** (everything
  over 100 MB, biggest first, to free up space), **Trash** and a **Recents** list.
- **Settings**: theme (system / light / dark), default view, hidden files, trash retention
  (7–90 days), clear the thumbnail cache, last-error log and an About section.

**Browse**
- Go up a level (**and it remembers where you were in the list** when you come back), search by name
  (6 levels, cancellable), **natural sort** (`photo2` before `photo10`, accent- and case-insensitive,
  folders first) by name/date/size/type with a **reverse order** switch, **list or grid with
  thumbnails** (photos, videos and **album art** for audio), folder item counts and show/hide hidden
  files. Sort, view and hidden-file choice are remembered.
- **Search by type, date and size:** the options bar has a *Filter* row (type: images, videos, audio,
  documents, folders · date: 24 h, 7 days, 30 days, 1 year · size: > 1 MB, > 10 MB, > 100 MB, > 1 GB).
  They combine with each other and with the text; with filters active it also searches subfolders (up
  to 6 levels). Each tap moves to the next option. Changing folder resets them.

**Selection and operations**
- **Multi-selection** (long-press): copy, cut, delete and, in "⋮": select all, invert selection,
  favorites, rename, **share**, **compress to ZIP**, **extract ZIP** and **details**. **Range
  selection:** with a selection active, long-press another item and everything from the last one you
  selected up to it gets marked (in list or grid).
- A **clipboard strip** shows what you copied/cut with *Paste here* / *Cancel*. Copy never overwrites
  (`name (1)`) and keeps the modification date; move is an instant rename on the same volume.
- **Progress and cancel** for long operations (copy, move, ZIP, extract, delete): a fixed strip with
  the percentage and a *Cancel* button; a cancelled copy or ZIP leaves nothing half-written. Short
  operations never show it.
- Tap a **ZIP** to extract it (or open it with another app); tap an **APK** to install it (Android asks
  you to allow FossFiles as an install source).
- The Android media index is notified after changes, so the Gallery sees what you copy, move or delete
  also on Android 8–10.

**Built-in viewers**
- **Image viewer:** zoom (pinch or double tap), previous/next, "open with another app", honors the EXIF
  orientation of camera photos.
- **Audio/video player:** previous/next, pause, a seek bar and automatic advance to the next track. It
  requests audio focus (it pauses if another app takes it) and pauses when you leave the app: there is
  no background service, so it does not drain battery. If the system cannot decode a format (e.g. some
  MKV/AVI) there is an "open with another app" button. Videos keep the screen on.

**Favorites and trash**
- **Favorites:** mark them from "⋮" (they show a star and appear in the Favorites category; favorite
  folders open straight in the browser).
- **Local trash bin** (one per storage volume, so sending to trash is always an instant rename):
  deleting asks whether to move to trash or delete permanently. Items are kept for the number of days
  you choose (default 30), can be **restored** to the original folder (recreating it if needed) and the
  trash can be emptied.

**Robustness**
- Works on Android 8 to 14+: runtime permission on 8–10 (with a shortcut to the app's settings if you
  ticked "don't ask again"), "All files access" on 11+ (with fallbacks for phones that lack that screen).
- Dialogs and what you typed survive screen rotation; the keyboard opens by itself and, when renaming a
  file, the name without its extension is pre-selected.
- Memory: thumbnails use a bounded cache that is released when the system is low on memory.

## Requirements
- JDK 17 or newer (the project targets Java 17 and builds fine on JDK 21)
- Android SDK with `platforms;android-34` and `build-tools;34.0.0`
- A device or emulator running Android 8.0 (API 26) or newer

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
./build-in-container.sh                    # release APK (default)
./build-in-container.sh assembleDebug      # any Gradle task
./build-in-container.sh testDebugUnitTest  # run the JVM tests
ENGINE=docker ./build-in-container.sh      # use Docker instead of Podman
```

The first run builds a small builder image (`Containerfile`: JDK 17 + the Android SDK, with the
toolchain's telemetry off) and caches Gradle in `.container/gradle`, so later builds are fast. The
generated files belong to you, and your own `local.properties` is left untouched. This is the same
approach CI systems use, and it makes builds reproducible across machines.

## Build from the command line
```bash
# Debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug

# Release APK (signed with the debug key unless you set up a keystore, see below)
./gradlew assembleRelease
```

Install on a physical device (enable USB debugging first):
```bash
adb devices
./gradlew installDebug
# or, with an APK already built:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Signing the APK with your own key
With no configuration the release build is signed with the debug key (fine for testing). To sign it
with your own key (needed to publish and to have updates install on top):

```bash
keytool -genkeypair -v -keystore fossfiles-release.jks -alias fossfiles -keyalg RSA -keysize 2048 -validity 10000
cp keystore.properties.example keystore.properties     # then edit it with your passwords
./gradlew assembleRelease
```

Put the `.jks` in the project root. `keystore.properties` and the `.jks` files are already in
`.gitignore`: **keep a copy of your keystore** — if you lose it you won't be able to update the app
signed with it.

> There is a **single universal APK**: the only native library is a small AndroidX support file
> (`libandroidx.graphics.path.so`), so one APK that covers every architecture is simpler than
> per-CPU builds. Only the strings for Spanish + English are packaged, which keeps the APK small.

## Testing
The file logic (`FileRepository`), the trash bin (`TrashBin`), the search filters (`SearchFilter`),
progress/cancellation (`OpProgress`) and the volume helpers (`VolumePaths`) do not depend on Android and
have JVM tests:

```bash
./gradlew testDebugUnitTest
```

They cover: natural sort and accent/case-insensitive keys, filtering by type/date/size, invalid names,
copy-without-overwrite (keeping dates, byte counting and cancelling without leftovers), moving to the
same folder, a folder inside itself, deleting without following symlinks, search (depth, cancellation,
cycles), hidden files, measuring folder contents, ZIP (round-trip, duplicate names, cancelling,
rejecting paths that try to escape), volume detection, the clear message for Android's protected
folders, and the trash bin (restore to origin, recreating the folder, name conflicts, purging by age,
symlinks, several volumes).

## Behavior notes
- Symlinks are never followed: copy/move/ZIP skip them; deleting removes only the link (they don't go
  through the trash bin).
- The trash bin lives in `.fossfiles-trash/` at the root of each volume (hidden and with `.nomedia`, so
  it doesn't show up in the gallery) and takes up space until it is emptied or the retention time
  passes. Items whose original folder no longer exists are restored by recreating it; if that fails,
  to the root of their volume.
- Since Android 11 the system itself blocks `Android/data` and `Android/obb` for every file manager
  (even with "All files access"); FossFiles says so instead of showing an empty folder.
- Long operations run while the app is open. There is no background service (that would need a
  notification and permissions), so for very large copies keep the app on screen.
- Search shows at most 5,000 results and warns you when there are more.
- Cut and paste across volumes (e.g. to an SD card) copies and then deletes; if the original could not
  be deleted, the app tells you.
- Folders cannot be shared directly: compress them to a ZIP first.
- ZIP extraction creates a new folder next to the archive, rejects unsafe paths and stops if there is
  no free space.
- The viewer does not open SVG (open those with another app); the player uses the system codecs.
- The interface is currently in Spanish.

## Possible next steps
- English translation of the interface (string resources) and other languages
- Subtitles, playback speed and background playback (which would need a service with a notification)
- Search inside file contents (text documents)
- Browsing inside a ZIP without extracting it

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
