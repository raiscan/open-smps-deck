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

Open the [native bank example](../examples/native-evening-clock.smps.json)
for an address-based event list and fixed-length hex edits. The Native bank
view shares project undo, save, live refresh and asset export. Preview uses
the frame limit stored in the bank source. See the
[native source format](../docs/architecture/native-bank-source.md).

**Import S3&K…** lists the music catalogue from a verified local locked-on
cartridge image and saves the selected song as a new native project.
The imported project opens on a read-only arrangement in a separate watched
window. Select a clip to inspect the sequencer's note or DAC events, or use
**Edit in Native bank** to change the source. **Save editable copy…** opens
a separate arrangement project with editable notes, clips and DAC hits
reconstructed from the preview.
[Studio import steps](../docs/user-guide/studio.md#import-s3k-music)

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

Use **ROM settings…** to register a clean locked-on S3&K ROM. Then choose
**Export… → Test in game…** to write a personal-use ROM or a shareable BPS.
[Test in game user guide](../docs/user-guide/14-test-in-game.md)

## Verification

```sh
mvn -B -pl studio -am test
# Explicit UI tests use their own isolated display; do not use the shared desktop.
xvfb-run -a -s '-screen 0 1600x1000x24' mvn -B -pl studio -am test -Dstudio.ui.test=true
# After package: real native mouse events against the executable at 100–200%.
python3 tools/verify_studio_dpi.py --scratch /path/to/disk/scratch
```

The [S3K playback foundation workflow](../.github/workflows/playback-foundation.yml)
runs the UI suite with `-Dstudio.ui.test=true` under Xvfb on pushes and pull
requests. The property is opt-in for local runs because these tests need an
isolated display. Under Xvfb the suite drives
actual scene mouse events and controls, saves notes/clips, replaces the file,
checks invalid-write recovery and dirty conflicts, preserves selection/viewport,
and exercises huge virtual timelines without allocating huge canvas buffers.
Use `-Dstudio.snapshot=/path/to/editor.png` with the isolated UI test to capture a
scene snapshot for visual QA. The tests inject a paced, silent audio line into
the main window, reconstruction transport and audition controls. They require
a display only. If you explicitly want the tests to use real audio, add
`-Dstudio.test.realAudio=true` and select your intended output device first.

## Rendering performance

The editors paint at most once per frame. `draw()` only marks a view dirty, and
code that reads painted geometry, such as hit testing, calls `flush()` first.
Avoid canvas `clip()` and dashed or path strokes in paint code: JavaFX renders
them through CPU masks. Paint the plot, then paint the gutters over it.

`StudioPerformanceProbe` replays wheel zoom, scroll, pitch scroll, playhead
follow and window resize on a reconstruction and an arrangement. It reports
frame times and CPU per frame for the process, FX thread, render thread and
spectrum workers. Pass scratch copies, since the probe writes journals:

```sh
xvfb-run -a -s '-screen 0 1920x1080x24' java -Dprism.forceGPU=true \
  -Djavafx.animation.fullspeed=true \
  -cp studio/target/opensmpsdeck-studio-0.1.0-SNAPSHOT.jar:studio/target/test-classes \
  com.opensmpsdeck.studio.StudioPerformanceProbe "$scratch/reconstruction" \
  "$scratch/reconstruction/arrangement.smps.json" "$scratch/report.tsv"
```

Under Xvfb, `prism.forceGPU` selects llvmpipe, so rasterizing costs CPU. Compare
FX-thread and render-thread CPU across builds, not absolute frame rates. Add
`-Dprobe.capture=<folder>` to save PNGs of each view for visual comparison.

## Design captures

From the repository root, build with Java 25 and run the capture suite on Linux.
It uses Xvfb when available, so it can run without a physical display or a
desktop login. The native folder chooser capture also uses Python 3, X11/XTest,
and FFmpeg with `x11grab`. JavaFX needs GTK and its usual Linux dependencies.

```sh
mkdir -p "$HOME/scratch/agent-tmp/runtime"
scratch=$(mktemp -d "$HOME/scratch/agent-tmp/runtime/task-XXXXXXXX")
mvn -B -pl studio -am package -Djava.io.tmpdir="$scratch"
tools/capture_studio.sh --output "$scratch/captures"
```

For a private reconstruction use `--work-dir /path/to/reconstruction`. Studio
opens its `arrangement.smps.json` by default. Use `--project /path/to/song.smps.json`
to open a different project in the arrangement window. With only `--project`,
Analysis still opens the synthetic fixture. The import checks and native bank
captures always use public inputs.

Each run writes `1920x1080/index.md` and `1366x768/index.md` under the output
directory, with one PNG per named state at each size. The indexes record the
actual logical window and PNG dimensions, including the smaller modal
dialogs, the default launch sizes, and the short-window tabs. Use a fresh
output directory for each review round. The suite prints states it could not reach and records the
reasons in the relevant index. Input-dependent states such as reference
review and vocal proposals require corresponding data in the supplied project.

The suite uses a temporary Studio profile and leaves the open projects and
reconstruction journals untouched. It stages an unsaved edit in memory and
captures Roles, Notes, Review and Export, along with the five transport
scopes and the loop controls. Capture runs have no audio device or ROM
requirement. Without Xvfb it uses the current Linux
display for JavaFX snapshots and skips the native screenshot.
