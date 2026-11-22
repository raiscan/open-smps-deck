# Throwaway live-edit spike

This source is outside the Maven modules. It exercises the pinned playback
session and the existing arrangement editor's note mutation. The paced headless
sink estimates presentation time using a two-block device queue. It checks PCM
against continuous reference renders and reports any missed replacement block.
It neither needs nor writes an audio device or WAV output.

Build the shaded headless JAR with JDK 25. From the repo root, set `scratch`
to a new disk-backed scratch directory and `pristine` to a private copy of a
complete audio-only S3K reconstruction with its source assets available.
Use a reconstruction with an empty journal and an unlinked sustained FM1
note. Keep all input assets and generated outputs outside the checkout.

```sh
jar=headless/target/opensmpsdeck-headless-0.1.0-SNAPSHOT.jar
spike=tools/spikes/live-edit-playback
mkdir "$scratch/live-classes" "$scratch/timing-classes" "$scratch/timed-source"
javac -cp "$jar" -d "$scratch/live-classes" "$spike/LiveEditSpike.java"
java -Djava.io.tmpdir="$scratch" -cp "$scratch/live-classes:$jar" \
  com.opensmpsdeck.headless.LiveEditSpike "$pristine/arrangement.smps.json"
```

The stage probe generates instrumented copies of two classes and places them
before the existing JAR on the classpath. It creates its own writable copy,
runs one warm-up and two measured passes, and restores the initial artifacts
before each pass. The full `assemble` stage includes WAV render, held-note
diagnostics and artifact publication. `prepareAssembly + arrange + compile`
measures the in-memory path up to a playable bank. It does not include ZIP
export or report generation. The journal probe calls the existing edit command
without a rebuild and deletes that probe's journal between samples.

```sh
python3 "$spike/instrument.py" "$scratch/timed-source"
javac -cp "$jar" -d "$scratch/timing-classes" \
  "$scratch/timed-source/"*.java "$spike/StageTiming.java"
java -Xmx6g -Djava.io.tmpdir="$scratch" -cp "$scratch/timing-classes:$jar" \
  com.opensmpsdeck.headless.StageTiming "$pristine" "$scratch/measured-copy"
```

See the [design](../../../docs/architecture/live-edit-playback.md) for
measurement conditions and the proposed integration.
