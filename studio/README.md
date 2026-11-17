# SMPS Timeline Studio

A new JavaFX editor over a shared JSON project, separate from the legacy tracker.
Clips, linked sequences, a piano roll and external file refresh use the same
source the headless CLI reads. Playback uses the pinned OpenGGF S3&K driver and
accurate chip core.

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

See the [Studio guide](../docs/user-guide/studio.md) for editing and external-agent
workflows and [project format](../project-core/README.md) for the file contract.

## Verification

```sh
mvn -B -pl studio -am test
# Explicit UI tests use their own isolated display; do not use the shared desktop.
xvfb-run -a -s '-screen 0 1600x1000x24' mvn -B -pl studio -am test -Dstudio.ui.test=true
```

Ordinary runs explicitly skip the display-only smoke class. Under Xvfb it drives
actual scene mouse events and controls, saves notes/clips, replaces the file,
checks invalid-write recovery and dirty conflicts, preserves selection/viewport,
and exercises huge virtual timelines without allocating huge canvas buffers.
Use `-Dstudio.snapshot=/path/to/editor.png` with the isolated UI test to capture a
scene snapshot for visual QA. No browser, server or physical audio device is
needed by these tests; audible preview requires an available audio output device.
