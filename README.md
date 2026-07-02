# WereWolf

A Wear OS watch face app that lets you transform a man into a werewolf.

Turn the crown to scrub through a full moon rising, then flick your wrist —
er, tap the screen — to switch phases and watch an ordinary man turn into a
howling werewolf, frame by frame.

## How it works

- **Phase 1 — The Moon**: rotate the digital crown to cycle through moon
  phases, ending on the full moon.
- **Phase 2 — The Transformation**: tap the full moon to switch phases and
  keep rotating the crown to watch the man transform into a werewolf. On
  reaching the final frame, the screen shakes and a howl plays.
- Tap again on the last frame to flip back to the moon phase.

## Gallery

| Full Moon | First Man | Werewolf |
|:---:|:---:|:---:|
| ![Full moon](app/src/main/res/drawable/moon9.png) | ![First man](app/src/main/res/drawable/werewolf0.png) | ![Werewolf](app/src/main/res/drawable/werewolf9.png) |

## Tech

- Kotlin + Jetpack Compose for Wear OS
- Rotary input (digital crown) driven animation via `onRotaryScrollEvent`
- `MediaPlayer` howl sound effect on transformation

## Building

```bash
./gradlew assembleDebug
```
