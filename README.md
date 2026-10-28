# GPS Map Pro

A fast, offline-friendly GPS dashboard for Android. GPS Map Pro keeps the clean three-state dashboard of the original GPS Launcher and adds a live OpenStreetMap, track recording, search, themes, and a few carefully chosen extras.

**made by nika**

## Features

- Original dashboard layout: three draggable states, mini bar, NO GPS flash, compass, accuracy and satellite info.
- Smooth spring-based drag and snap animations.
- Live OpenStreetMap with automatic tile caching and an offline-only mode.
- North-up and heading-up camera modes while following your position.
- Themes: **Original**, **Night**, and **Light** (saved on the device).
- Auto-collapse toggle as the first setting in the dashboard settings popup.
- Track recording to GPX, track list, playback, GPX sharing and deletion.
- Place search via Nominatim, long-press pins with reverse-geocoded addresses.
- Extra stats where space allows: altitude, heading, fix age, trip distance, average and maximum speed, coordinates.
- "made by nika" credit in Settings.

## Install

Download `GPS-Map-Pro-2.0.apk` from the latest GitHub release and open it on an Android 8.0+ phone. Allow installation from the browser or file manager when prompted, then grant location permission on first launch.

The release APK is signed with a stable key, so future versions can be installed over it.

## Build

Requirements: JDK 17 and Android SDK 35.

```bash
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Release builds use the local signing configuration in `keystore.properties` (not committed). See `keystore.properties.example` for the expected keys.

```bash
./gradlew :app:assembleRelease
```

## Tech

Kotlin, Android Views, osmdroid, Material Components, AndroidX, Kotlin coroutines, DynamicAnimation.

## Notes

- Map data (c) OpenStreetMap contributors, tiles by OpenStreetMap and CARTO.
- Location data stays on the device. Place search sends only the typed query to Nominatim.
- GPX files are stored in the app's internal storage and can be shared with the system share sheet.

---

made by nika
