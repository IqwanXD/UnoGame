# UnoGame

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Min SDK](https://img.shields.io/badge/SDK-21%2B-blue)
![Language](https://img.shields.io/badge/code-Java-orange?logo=openjdk&logoColor=white)

A classic UNO card game for Android with 3D rendering, online multiplayer via Firebase, and a customizable player avatar.

---

## Features

- **Classic UNO rules** — numbered cards (0–9), Skip, Reverse, Draw 2, Wild, Wild Draw 4
- **System Juara mode** — players who empty their hand earn a rank and leave the round; the game continues until one player remains
- **Online multiplayer** — real-time sync using Firebase Realtime Database
- **Bot AI** — single-player support with a priority-based card selection bot
- **Custom avatar** — pick and crop a face photo from your gallery
- **Adaptive performance** — lower resolution and capped FPS on devices with ≤ 3 GB RAM

---

## Requirements

| Item | Value |
|---|---|
| Min SDK | API 21 (Android 5.0) |
| Target SDK | API 34 (Android 14) |
| Language | Java 8 |
| Renderer | OpenGL ES 2.0 |

---

## Project Structure

```
app/src/main/java/ru/unogame/
├── MainActivity.java      # Entry point, OpenGL surface, UI dialogs
├── GameRenderer.java      # Frame rendering and game screen management
├── UnoEngine.java         # Game logic (rules, turns, bot, JSON state)
├── Scene3D.java           # 3D card and player rendering
├── MeUno.java             # Local player state
├── Fb.java                # Firebase multiplayer sync
├── Gfx.java               # Texture and graphics helpers
├── Sfx.java               # Sound effects and audio
├── CropActivity.java      # Avatar photo cropping screen
├── FileUtil.java          # File utilities
└── SketchwareUtil.java    # Misc utilities
```

---

## Getting Started

1. Clone the repository
   ```bash
   git clone https://github.com/IqwanXD/UnoGame.git
   ```
2. Open the project in **Android Studio**.
3. Add your `google-services.json` from Firebase Console into `app/`.
4. Build and run on a device or emulator running Android 5.0+.

---

## How the Card System Works

Cards are encoded as a single integer: `color × 20 + value`.

| Color | Index |
|---|---|
| Red | 0 |
| Yellow | 1 |
| Green | 2 |
| Blue | 3 |
| Wild | 4 |

Special values: `Skip = 10`, `Reverse = 11`, `Draw 2 = 12`, `Wild = 13`, `Wild Draw 4 = 14`.

---
