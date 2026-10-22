# OpenPlay

An ad-free, open-source Android video player built on Jetpack Media3 (ExoPlayer) and Jetpack Compose.

## Features (v0.4)
- Redesigned home: header, tab pills, thumbnails, sorting (name/date/size/duration), list or grid view
- New player UI: modern controls, collapsible options panel, playlist, audio/subtitle menus, orientation switch + rotation lock, landscape playback
- Videos and Folders tabs (MediaStore, internal storage + SD card)
- Network streaming (HTTP progressive, HLS, DASH)
- Gestures: left side brightness, right side volume, horizontal swipe seek
- Speed 0.25x–2x, aspect modes (Fit / Fill / Zoom)
- External subtitles (SRT, VTT, ASS/SSA)
- Background playback with media notification
- Picture-in-Picture
- Private folder with PIN (files moved to app-private storage)
- Resume where you left off
- "Open with" from other apps

## Formats
Plays whatever Media3 and the device's codecs support: MP4, MKV, WebM, M4V, MOV, 3GP, TS, FLV; H.264, HEVC, VP9, AV1 (hardware dependent), 4K/HDR where the device allows.
AVI, WMV, ASF, RMVB, RM, DIVX, VOB and OGM are played by a libVLC fallback engine (LGPL-2.1+). Any file ExoPlayer fails on is automatically retried in libVLC. The VLC engine has no background-play service yet.
libVLC adds roughly 20–30 MB per ABI to the APK; per-ABI splits are enabled in app/build.gradle.kts.

## Roadmap
Chromecast, floating pop-up window, audio track picker, widgets, USB drives, encryption for the private folder, tests.

## Build
Open in Android Studio (Koala or newer), sync, run. This code has not been compiled by its author tool; expect to fix small issues on first build.

## License
MIT for OpenPlay's own code. libVLC is LGPL-2.1+; keep it dynamically linked and credit VideoLAN when you distribute.

## Build without Android Studio
Push this repo to GitHub. The "Build APK" workflow (.github/workflows/build.yml) builds it automatically.
Open the Actions tab, pick the latest run, and download the OpenPlay-debug-apk artifact.
