# SMPS Timeline Studio

A new JavaFX editor over a shared JSON project, separate from the legacy tracker.
Clips, linked sequences, a piano roll and external file refresh use the same
source the headless CLI reads. Playback uses the pinned OpenGGF S3&K driver and
accurate chip core.

The source editor includes Notes and Commands tabs, with timed native controls,
linked voice/volume settings and explicit RESET/CARRY repetition. See the
[S3K support matrix](../docs/architecture/s3k-support.md) for remaining coverage.

**Assets** adds native FM voice editing, separate parameter/raw-byte drafts, PSG
volume and modulation envelope assets, and linked source assignment. Preview/WAV
include the resources; **Export package** writes their native bytes and manifest.
The [package contract](../docs/architecture/native-asset-package.md) documents
engine integration and the currently bounded envelope profile.

**DAC samples** adds WAV and raw unsigned PCM import, waveform and rate
editing, and a linked one-shot hit grid on the DAC lane. Preview and package
export carry the sample data. Open the
[DAC percussion example](../examples/dac-percussion.smps.json) to try it.

## Run

Build with JDK 25 and Maven, on a desktop with JavaFX-supported native libraries:

```sh
mvn -B -pl studio -am package
java -jar studio/target/opensmpsdeck-studio-0.1.0-SNAPSHOT.jar examples/lantern-walk.smps.json
```

The JAR bundles JavaFX native libraries for the platform on which Maven resolves
them; it is not a cross-platform installer. A Linux desktop needs GTK/X11 as
usual. Without an argument the app creates/opens an original demo at
`~/.local/share/opensmpsdeck/studio-demo.smps.json`, never overwriting an existing
file. You can supply any valid project path instead.

Studio uses JavaFX's native display scale for controls, canvas drawing, pointer
picking and dialogs. On Linux it bridges GTK/Xft DPI into JavaFX at startup when
needed, including KDE/XWayland fractional scaling. Explicit `glass.gtk.uiScale`
property/environment and `GDK_SCALE` settings take precedence. A desktop scaling
change requires restarting Studio. For an explicit launch override, use e.g.
`java -Dglass.gtk.uiScale=1.5 -jar ...`.

The window fits the screen's logical work area. Short work areas use Arrangement
and Notes tabs instead of stacking two cramped editors; double-click a clip or
source to open Notes. The clip inspector scrolls independently.

See the [Studio guide](../docs/user-guide/studio.md) for editing and external-agent
workflows and [project format](../project-core/README.md) for the file contract.

## Verification

```sh
mvn -B -pl studio -am test
# Explicit UI tests use their own isolated display; do not use the shared desktop.
xvfb-run -a -s '-screen 0 1600x1000x24' mvn -B -pl studio -am test -Dstudio.ui.test=true
# After package: real native mouse events against the executable at 100–200%.
python3 tools/verify_studio_dpi.py --scratch /path/to/disk/scratch
```

Ordinary runs explicitly skip the display-only smoke class. Under Xvfb it drives
actual scene mouse events and controls, saves notes/clips, replaces the file,
checks invalid-write recovery and dirty conflicts, preserves selection/viewport,
and exercises huge virtual timelines without allocating huge canvas buffers.
Use `-Dstudio.snapshot=/path/to/editor.png` with the isolated UI test to capture a
scene snapshot for visual QA. No browser, server or physical audio device is
needed by these tests; audible preview requires an available audio output device.
