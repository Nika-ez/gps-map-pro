# GPS Map Pro

A fast, offline-friendly GPS dashboard for Android. GPS Map Pro keeps the clean dashboard of the original GPS Launcher and adds a live OpenStreetMap, track recording, route planning, search, themes, and a few carefully chosen extras.

**made by nika**

## Features

- Four-stage dashboard: hidden bar, mini, main and full, with smooth spring-based dragging.
- Auto-collapse toggle and a **Never hide dashboard** option; the layout stays put when disabled.
- Landscape mode turns the dashboard into a left rail; orientation can be **Auto**, **Portrait**, or **Landscape**.
- Fullscreen with a status bar that only appears when you swipe from the top edge.
- Live OpenStreetMap with automatic tile caching and an offline-only mode.
- Three-step follow button: location, location with heading rotation, off. **N up** snaps the map north without stopping tracking.
- Two-finger map rotation while exploring, and pinch zoom with the built-in controls.
- Route planning with start and destination set from your location, a map pin, the picker, or a typed address. Straight-line routes show distance; track routes show progress and ETA.
- Track recording to GPX with GPS filtering, smooth display lines, a hide/show pill, and automatic display after recording stops.
- Use any recorded track as a route, and tap a track name to rename it.
- Themes: **Original**, **Night**, and **Light** (saved on the device).
- Location marker styles: **Dot**, **Arrow**, **Car**, and **Pin**, selected in Settings.
- Place search via Nominatim, long-press pins with reverse-geocoded addresses.
- Extra stats where space allows: altitude, heading, GPS age, trip distance, average and maximum speed, coordinates.
- "made by nika" credit in Settings.

## Install

Download `GPS-Map-Pro-2.2.apk` from the latest GitHub release and open it on an Android 8.0+ phone. Allow installation from the browser or file manager when prompted, then grant location permission on first launch.

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
