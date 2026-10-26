# Lockscreen Video Cover (Lockscreen Canvas)

### 60FPS Hardware Canvas Engine, BitChord Now Playing Card, Synced Lyrics & Lossless Quality Tracking for ColorOS 16 / 17.

[![Tested on ColorOS 17](https://img.shields.io/badge/ColorOS-17_%2F_Android_17-00BD13?style=for-the-badge&logo=oppo&logoColor=white&labelColor=0d1117)](https://www.oppo.com)
[![Device: Oppo Find X9](https://img.shields.io/badge/Device-Oppo_Find_X9-E60012?style=for-the-badge&logo=oppo&logoColor=white&labelColor=0d1117)](https://www.oppo.com)
[![OpenGL ES 2.0](https://img.shields.io/badge/OpenGL_ES-2.0_Hardware_Canvas-5586A4?style=for-the-badge&logo=opengl&logoColor=white&labelColor=0d1117)](#features)
[![License: MIT](https://img.shields.io/badge/License-MIT-a855f7?style=for-the-badge&labelColor=0d1117)](../LICENSE)

---

## Overview

**Lockscreen Video Cover** transforms the Oppo / OnePlus lockscreen into a dynamic, aesthetic media canvas. It listens to music playback across **BitChord**, **Spotify**, and **Apple Music**, fetching high-definition vertical video canvases and rendering them smoothly at 60 FPS using hardware OpenGL ES.

When a track lacks a video canvas, it automatically frames high-resolution album artwork with ColorOS 17 ambient blurred backdrop lighting.

---

## Features

### 1. BitChord Now Playing Card & Synced Lyrics
- **Apple Music / BitChord Aesthetics**: Positioned cleanly directly above the native lockscreen media player pill.
- **Microtypography**: Apple SF Pro Display typography (Bold, Medium, Regular) loaded natively.
- **Real-Time Synced Lyrics**: Integrated with the open **LRCLIB** synced lyrics engine (`lrclib.net`), matching BitChord's backend. Tracks current playback millisecond timestamp and highlights active lines with trailing `›` chevrons.
- **Dynamic Scrub Bar**: Rounded progress bar indicating elapsed and remaining time (`1:03` / `-2:06`) with 0.4x larger typography.
- **Background Song-Shift Screen Wake**: Automatically wakes the display when a song changes while phone is in off state.

### 2. Live Lossless FLAC Stream Detection
- Tracks real-time audio playback configurations via `AudioManager.getActivePlaybackConfigurations()` and reactive callbacks.
- Detects when BitChord upgrades its stream from initial Opus buffer to CD-quality FLAC (44.1 kHz Red Book) or Hi-Res Lossless (96 kHz / 24-bit PCM), dynamically updating overlay metrics without stopping canvas playback.
- Fixed clean light typography without drop shadows or distracting color jitter.

### 3. Top Wallpaper Gradient Reveal
- Hardware fragment shader feathering (`topAlpha = smoothstep(0.77, 0.47, normCoord.y)`).
- Fades the top 30% of canvas video, revealing the stock lockscreen wallpaper curves cleanly behind the clock and widgets.
- User-selectable in app settings: **Top Wallpaper Reveal** vs **Full Screen Canvas**.

### 4. ColorOS 17 Ambient Artwork Framing
- 1400×1400 dynamic artwork fetching from music CDNs.
- Aspect-ratio preserved geometric framing with dual-gradient top and bottom ambient blurred extensions.

---

## Building from Source

### Prerequisites
- Android SDK (API 34+ / Build-Tools 35.0.0+)
- Java 11 JDK
- Python 3 (for APK dex packaging)

### Build Command
```powershell
cd lockscreen-canvas
.\build.ps1
```
The compiled, aligned, and signed APK will be output to `lockscreen-canvas/dist/LockVideoCover.apk`.

### Installation
```powershell
adb install -r -d dist/LockVideoCover.apk
```

---

## License
MIT License. See [LICENSE](../LICENSE) for details.
