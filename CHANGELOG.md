# Changelog

## 3.0.0
A "ready for everyday use" release: fewer rough edges, faster listings and a more complete
file manager. Still no Internet permission, no telemetry and no animations.

### New
- **SD card / USB** volumes on the Home screen (each with its own trash bin).
- **Settings** screen: theme, default view, hidden files, trash retention, clear thumbnail cache,
  local error log and About.
- **Global search** from the Home screen and a **Large files** shortcut (> 100 MB, biggest first).
- **Progress and cancel** for copy, move, ZIP, extract and delete.
- Natural sort (`photo2` < `photo10`, accent/case-insensitive) and a reverse-order switch.
- The list **remembers its scroll position** when you go back to a folder.
- Folder item counts, a clipboard strip (*Paste here* / *Cancel*), tap a ZIP to extract it and tap an
  APK to install it.
- Android media index is refreshed after changes (Gallery on Android 8–10).

### Improved
- Listing a folder makes one system call per file instead of three; searching makes one per entry
  instead of two; paths and file types are computed once.
- Dialogs and typed text survive screen rotation; the keyboard opens automatically; renaming selects
  the name without the extension.
- Works on Android 10 (legacy storage flag) and handles "don't ask again" for the old permission.
- Copy keeps the modification date and says why it failed (no space / no permission).
- Thumbnails are released when the system is low on memory; the "Loading…" notice only appears when a
  folder really takes a moment, and taps are ignored while a stale list is still on screen.
- Clear message for Android's protected `Android/data` and `Android/obb` folders.

## 2.0.0
- Built-in audio/video player, search by type/date/size, range selection.

## 1.x
- Local trash bin, favorites, image viewer, ZIP, thumbnails, grid view, hidden files and the first
  beta releases.
