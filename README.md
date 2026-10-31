# Inkwell (placeholder name) – low-RAM handwriting notes for Android tablets

Original app, not derived from any other app's code or assets.

## Build
1. Open this folder in Android Studio (Koala or newer). Let it sync / create the Gradle wrapper.
2. Run on a tablet or a tablet emulator (try a 2 GB RAM profile to verify memory).

## Done (milestones 1-2)
- Vector ink engine: pen (pressure), highlighter, stroke eraser, undo/redo
- Pinch-zoom + two-finger pan, stylus-only drawing by default (finger toggle)
- Screen-sized RGB_565 render cache, viewport culling, 100-step undo
- Notebooks and pages in Room; one page in memory at a time; debounced autosave
- Blank / lined / grid paper

## Next
- Lasso select/move, shapes, text boxes, images
- PDF import/annotate/export (PdfRenderer, one page at a time)
- Folders, thumbnails, search, proper icon/theme

## Get an installable APK
Option A (no Android Studio): push this folder to a new GitHub repo. The "Build APK" workflow
runs automatically. Open the Actions tab, open the latest run, download the `inkwell-apk`
artifact, unzip it, and install `app-debug.apk` on your tablet (allow "Install unknown apps").
Option B: Android Studio > Build > Build APK(s). The debug APK is signed automatically and installs directly.
