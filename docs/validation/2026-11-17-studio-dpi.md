# Native DPI correction

The tiny UI had two causes: fixed 10–12 px type, and JavaFX 21 GTK's scale
discovery on the user's KDE/XWayland configuration. Read-only desktop inspection
found GTK DPI approximately 153.6 / Xresources 153 (160%), while GNOME's scale
value was 1. JavaFX can select that unrelated 1 before GTK's DPI. AWT's live DPI
property was absent on this desktop, so the GTK configuration fallback matters.

## Changes

Before JavaFX startup, bridge Linux live XSettings, GTK configuration or Xft DPI
into `glass.gtk.uiScale`. A valid normal live scale stops stale fallbacks without
forcing an otherwise native high-DPI monitor down to 1x. Explicit JavaFX property
and environment, GDK scale and disabled HiDPI choices are respected. Native
output scaling remains authoritative: no second scene transform. Controls,
canvases, dialogs and pointer coordinates share the same scale.

Increase baseline control/secondary/canvas type. Bound initial and minimum window
sizes to logical visual bounds, wrap note controls, scroll the inspector, and use
Arrangement/Notes tabs on short logical screens. Publish virtual content bounds
before restoring horizontal anchors so ScrollPane's later layout does not move
the viewport after live refresh.

The bridge is specific to the pinned JavaFX 21 Linux path. Other platforms retain
native JavaFX handling. Preferences are read at launch; changed desktop scaling
requires a restart. No desktop settings are modified.

## Verification

- Focused isolated-Xvfb `mvn -B -pl studio -am verify -Dstudio.ui.test=true
  -Dstudio.snapshot=<path>`: **61 Java tests pass** (33 project-core, 14 headless,
  14 Studio), no failures/errors/skips. New checks cover DPI precedence, readable
  controls and reaching both editors on a short logical screen.
- `python3 tools/verify_studio_dpi.py --scratch <disk scratch>`: **six packaged
  native UI cases pass** on isolated 1920x1080 Xvfb displays: explicit 100%,
  automatic GTK 150% and 160%, and explicit 200% via JVM property, JavaFX
  environment and GDK scale despite a conflicting desktop hint.
- Every case checks native output scale, absence of a duplicate scene transform,
  visual work-area bounds, and JavaFX Robot **physical mouse events**: select and
  drag a clip, horizontally scroll, double-click to add a note, resize it and
  click Save. The shared file is read back to verify edits. Screenshots at
  `studio/target/qa/dpi-*.png` were visually inspected; 200% uses editor tabs.
- Full isolated-display suite: **760 tests, 9 failures, 0 errors, 2 skips**.
  The same nine legacy assertions remain: eight absent Drowning-rip failures
  and the existing DAC subdirectory parsing failure. No new failures; the
  unchanged upstream audio snapshot still passes its integrity check.
- Independent review corrected two precedence issues before delivery: valid
  live 100% must stop stale higher hints, and the JavaFX environment override
  must be respected alongside its JVM property. Native scale/pointer checks are
  included in CI; local verification does not establish hosted CI completion.

No windows were opened on the shared desktop. The OS configuration was inspected
read-only; physical rendering and interaction were tested on isolated displays.
