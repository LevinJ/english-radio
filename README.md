# English Radio

A tiny, self-healing Android radio app for listening to English live radio — built for
the use case of pressing *play* while lying in bed, and never touching the phone again.

![minSdk](https://img.shields.io/badge/minSdk-26-green)
![targetSdk](https://img.shields.io/badge/targetSdk-35-blue)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7f52ff)
![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4)

## Stations

| Station | Style | Bitrate |
|---|---|---|
| BBC World Service | International news, clear UK English (HLS + mirrors) | 128 k |
| NPR | US news & culture, American English (AAC/MP3) | 128 k |
| BBC Radio 4 | British spoken word: drama, comedy, news (HLS) | 128 k |
| LBC London | UK news talk, London English | 48 k |
| Fox News Radio | 24/7 US news | 64 k |
| ABC Radio National | Ideas & documentaries, calm Australian English (HLS) | 64 k |
| CGTN Radio | English news from Beijing (HLS) | 96 k |

All stream URLs were verified reachable from a mainland-China network at release time;
unreachable mirrors are kept as automatic fallbacks.

## Why it never needs babysitting

- **Background playback** — `MediaSessionService` + `mediaPlayback` foreground service:
  keeps playing through screen lock, notification controls included.
- **Network-drop recovery** — playback errors trigger prepared+retries with exponential
  backoff (1 s → 30 s); a `ConnectivityManager` callback reconnects the instant
  connectivity returns. No user action, ever.
- **Dead-URL rotation & healing** — each station keeps several verified URLs and the last
  known-good one; when they all fail, the app queries the open
  [Radio Browser](https://www.radio-browser.info/) directory for replacements, probes
  them for liveness **and freshness** (rejecting 200-OK-but-silent zombie feeds) and
  adopts a working URL.
- **Wi-Fi ⇄ mobile-data failover** — a stall watchdog (no audio progress for 12 s, twice
  in a minute) pins the app's sockets to cellular automatically; tapping the
  *Mobile data* chip returns to Wi-Fi (auto failover can be disabled for the month).
- **Sleep timer** — off by default; 30 / 60 / 120 min with a gentle 60-second volume
  fade before pausing. The last station and play state survive app restarts.

## Build

Requirements: JDK 17, Android SDK (platform 35, build-tools 35). No Android Studio needed.

```bash
# point at your SDK, e.g.
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> The first build downloads ~2–3 GB of Gradle/AGP/dependency caches into
> `~/.gradle`. To keep them elsewhere: `export GRADLE_USER_HOME=/some/big/disk/gradle-home`.

## Project layout

```
app/src/main/java/com/levin/englishradio/
  Stations.kt        curated station list (URLs + fallbacks + healing queries)
  RadioService.kt    Media3 service: playback, retries, watchdog, failover, sleep timer
  StreamHealer.kt    Radio Browser directory client + URL liveness/freshness probes
  MainActivity.kt    Jetpack Compose UI + ViewModel (MediaController, custom session commands)
```

## Notes

- **MIUI/Xiaomi devices**: enable *Autostart* for the app and lock it in the recent-apps
  screen, or the system may kill background playback (the app shows a one-time hint).
- Stream URLs rot over time; the directory healing covers that, but if a station stays
  silent for days, refreshing `Stations.kt` is a two-line change.
- Debug builds only — no release signing configured.

## License

MIT — see [LICENSE](LICENSE).
