# CamPro

Android camera app: **manual controls plus computational fusion**.

The stock camera app decides everything for you. CamPro exposes the parameters — exposure, focus, ISO behaviour — and then fuses multiple captures into a single frame to recover detail that one shot loses.

## What is inside

- `AndroidManifest.xml` — package `com.thiairo.campro`, camera permission and hardware camera feature
- `src/` — capture pipeline and manual-control UI
- `res/` — layouts, drawables, themes
- `build/` — build configuration

Related: [campro-aesthetic](https://github.com/LH434534/campro-aesthetic) — the visual layer of the same stack.

## Stack

Java · Android SDK · Camera API
