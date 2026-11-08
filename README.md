# NekoPaint

A free, open-source drawing app for Android with a Material 3 design, in the spirit of
full-featured painting apps.

> **Status: very early.** A drawing core and a minimal Android app (one layer, brush, eraser,
> colour, undo/redo, pinch-zoom, PNG export). Layers UI, project files and more brushes come next.

## Principles

- **Free software**, GPL-3.0-or-later.
- **No accounts, no telemetry, no Google SDKs.** Your drawings are files in a folder you choose.
  Android's own file picker lets that folder live on Google Drive, Nextcloud, Dropbox and so on
  without the app talking to any of them directly.
- **Open formats.** Planned: OpenRaster as the native project format, import of PSD and PNG.

## What is in the repository

| Path | What it is |
| --- | --- |
| `core/` | Drawing engine in plain Java with no Android dependencies, tested on a normal JVM |
| `app/` | Android app (Material 3, minSdk 26) |

The core currently has:

- layers with visibility, opacity and blend modes (normal, multiply, screen) on premultiplied
  ARGB pixels,
- a round brush with size, hardness, flow, spacing and pressure, and an eraser,
- an editor with exact undo and redo that stores only the changed rectangles.

It is a CPU implementation. It serves as the reference and test oracle; a GPU path may be added
later.

## Building and testing

Requires JDK 8 or newer and Gradle.

```
gradle :core:test
gradle :app:assembleDebug
```

Every push is built by GitHub Actions; the debug APK is attached to the run as an artifact.

## Other apps

NekoPaint is **not affiliated with** ibis Paint, ibis Inc. or any other drawing app, and the
names belong to their owners. No code, assets, icons, brush names or screens from other apps are
used here, and NekoPaint does not use any other app's online services or private file formats.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
