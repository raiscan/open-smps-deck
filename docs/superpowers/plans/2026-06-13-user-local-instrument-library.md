# User-Local Instrument Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a user-local instrument library that recursively scans SMPS rip folders for FM voices, PSG envelopes, modulation envelopes, and DAC samples, deduplicates them, and deploys selected assets into open songs.

**Architecture:** Add a focused `com.opensmpsdeck.library` package for library data, deployment, dialect parsing, harvesting, and scanning. Keep persistence in `com.opensmpsdeck.io` beside existing JSON file formats, and wire JavaFX dialogs through `MainWindow` without changing track bytecode or song arrangement data.

**Tech Stack:** Java 21, JavaFX controls, Gson, JUnit 5, Maven, existing OpenSMPSDeck model classes (`FmVoice`, `PsgEnvelope`, `DacSample`, `Song`) and existing importer helpers (`SmpsImporter`, `HexUtil`).

---

## File Structure

Create these files:

- `app/src/main/java/com/opensmpsdeck/library/InstrumentAssetKind.java`: enum for `FM_VOICE`, `PSG_ENVELOPE`, `MOD_ENVELOPE`, `DAC_SAMPLE`.
- `app/src/main/java/com/opensmpsdeck/library/SourceReference.java`: immutable source metadata for dedupe provenance.
- `app/src/main/java/com/opensmpsdeck/library/AddResult.java`: result of an add-or-merge operation.
- `app/src/main/java/com/opensmpsdeck/library/InstrumentLibraryEntry.java`: one library entry with kind-specific payload and metadata.
- `app/src/main/java/com/opensmpsdeck/library/InstrumentLibrary.java`: in-memory collection, dedupe, source-reference merge, dirty tracking.
- `app/src/main/java/com/opensmpsdeck/library/LibraryPaths.java`: user-local root resolution through Java Preferences.
- `app/src/main/java/com/opensmpsdeck/io/InstrumentLibraryFile.java`: `library.json` plus `dac/` payload persistence.
- `app/src/main/java/com/opensmpsdeck/library/InstrumentLibraryDeployer.java`: appends or reuses selected assets in a `Song`.
- `app/src/main/java/com/opensmpsdeck/library/DeployResult.java`: deployment appended/reused counts.
- `app/src/main/java/com/opensmpsdeck/library/rip/SmpsRipConfig.java`: parsed `config.ini` model.
- `app/src/main/java/com/opensmpsdeck/library/rip/SmpsRipConfigParser.java`: parses config sections and key/value companion references.
- `app/src/main/java/com/opensmpsdeck/library/rip/SmpsDriverDefinition.java`: parsed `DefDrv.txt` properties.
- `app/src/main/java/com/opensmpsdeck/library/rip/CoordFlagDefinition.java`: parsed `DefCFlag.txt` command lengths and jump offsets.
- `app/src/main/java/com/opensmpsdeck/library/rip/DialectCapability.java`: enum for `FULL_IMPORT`, `ASSET_ONLY`, `UNSUPPORTED`, `IGNORED`.
- `app/src/main/java/com/opensmpsdeck/library/rip/DialectCapabilityClassifier.java`: gates current full import to S1/S2/S3K until parameterized import exists.
- `app/src/main/java/com/opensmpsdeck/library/harvest/DacCodec.java`: DPCM decompression shared by importer and scanner.
- `app/src/main/java/com/opensmpsdeck/library/harvest/EnvelopeListParser.java`: shared parser for `PSG.lst` and `Modulat.lst`.
- `app/src/main/java/com/opensmpsdeck/library/harvest/DacIniParser.java`: parses SMPSPlay DAC ini variants.
- `app/src/main/java/com/opensmpsdeck/library/harvest/InsSetVoiceParser.java`: extracts normalized FM voices from supported instrument layouts.
- `app/src/main/java/com/opensmpsdeck/library/harvest/CompanionAssetHarvester.java`: harvests configured `VolEnv`, `ModEnv`, `DAC`, and `GlobalInsLib`.
- `app/src/main/java/com/opensmpsdeck/library/harvest/HarvestResult.java`: companion-harvest counts and warnings.
- `app/src/main/java/com/opensmpsdeck/library/scan/InstrumentLibraryScanner.java`: recursive scanner with symlink guard and summary.
- `app/src/main/java/com/opensmpsdeck/library/scan/ScanSummary.java`: scan counters and failure reasons.
- `app/src/main/java/com/opensmpsdeck/library/scan/ScanFailure.java`: one failed file/config reason.
- `app/src/main/java/com/opensmpsdeck/ui/LibraryActions.java`: JavaFX actions for scan/open/location/deploy integration.
- `app/src/main/java/com/opensmpsdeck/ui/LibraryBrowserDialog.java`: tabbed browser and deploy selection UI.
- `app/src/main/java/com/opensmpsdeck/ui/LibraryLocationDialog.java`: displays and changes library root.
- `app/src/main/java/com/opensmpsdeck/ui/ScanSummaryDialog.java`: displays scan result counts and failures.

Modify these files:

- `app/src/main/java/com/opensmpsdeck/io/SmpsImporter.java`: delegate DPCM decompression to `DacCodec`.
- `app/src/main/java/com/opensmpsdeck/ui/MainWindow.java`: add `Library` menu and wire `LibraryActions`.
- `app/src/main/java/module-info.java`: export or open packages only if compilation requires it.

Create these tests:

- `app/src/test/java/com/opensmpsdeck/library/TestInstrumentLibrary.java`
- `app/src/test/java/com/opensmpsdeck/io/TestInstrumentLibraryFile.java`
- `app/src/test/java/com/opensmpsdeck/library/TestInstrumentLibraryDeployer.java`
- `app/src/test/java/com/opensmpsdeck/library/rip/TestSmpsRipConfigParser.java`
- `app/src/test/java/com/opensmpsdeck/library/rip/TestSmpsDriverDefinition.java`
- `app/src/test/java/com/opensmpsdeck/library/rip/TestCoordFlagDefinition.java`
- `app/src/test/java/com/opensmpsdeck/library/rip/TestDialectCapabilityClassifier.java`
- `app/src/test/java/com/opensmpsdeck/library/harvest/TestDacIniParser.java`
- `app/src/test/java/com/opensmpsdeck/library/harvest/TestInsSetVoiceParser.java`
- `app/src/test/java/com/opensmpsdeck/library/harvest/TestCompanionAssetHarvester.java`
- `app/src/test/java/com/opensmpsdeck/library/scan/TestInstrumentLibraryScanner.java`

---

### Task 1: Library Model And Persistence

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/library/InstrumentAssetKind.java`
- Create: `app/src/main/java/com/opensmpsdeck/library/SourceReference.java`
- Create: `app/src/main/java/com/opensmpsdeck/library/AddResult.java`
- Create: `app/src/main/java/com/opensmpsdeck/library/InstrumentLibraryEntry.java`
- Create: `app/src/main/java/com/opensmpsdeck/library/InstrumentLibrary.java`
- Create: `app/src/main/java/com/opensmpsdeck/library/LibraryPaths.java`
- Create: `app/src/main/java/com/opensmpsdeck/io/InstrumentLibraryFile.java`
- Test: `app/src/test/java/com/opensmpsdeck/library/TestInstrumentLibrary.java`
- Test: `app/src/test/java/com/opensmpsdeck/io/TestInstrumentLibraryFile.java`

- [ ] **Step 1: Write failing model tests**

Create `app/src/test/java/com/opensmpsdeck/library/TestInstrumentLibrary.java`:

```java
package com.opensmpsdeck.library;

import com.opensmpsdeck.model.FmVoice;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestInstrumentLibrary {

    @Test
    void duplicateFmVoiceMergesOneSourceReferenceOnce() {
        InstrumentLibrary library = new InstrumentLibrary();
        SourceReference source = new SourceReference(
                "C:/rips", "Z80", "Sonic 2", "", ".sm2",
                "01 Emerald Hill.sm2", "InsSet.17D8.bin", "00",
                "PtrFmt=Z80 InsMode=Default");
        byte[] data = new byte[FmVoice.VOICE_SIZE];
        data[0] = 0x27;

        InstrumentLibraryEntry first = InstrumentLibraryEntry.fmVoice("Voice A", data, source);
        InstrumentLibraryEntry second = InstrumentLibraryEntry.fmVoice("Voice B", data.clone(), source);

        AddResult firstResult = library.addOrMerge(first, Instant.parse("2026-06-13T10:00:00Z"));
        AddResult secondResult = library.addOrMerge(second, Instant.parse("2026-06-13T11:00:00Z"));

        assertTrue(firstResult.added());
        assertFalse(secondResult.added());
        assertFalse(secondResult.changed());
        assertEquals(1, library.entries(InstrumentAssetKind.FM_VOICE).size());
        InstrumentLibraryEntry stored = library.entries(InstrumentAssetKind.FM_VOICE).getFirst();
        assertEquals("Voice A", stored.displayName());
        assertEquals(1, stored.sourceReferences().size());
        assertEquals(Instant.parse("2026-06-13T10:00:00Z"), stored.updatedTimestamp());
    }

    @Test
    void identicalDacBytesWithDifferentRateAreDistinct() {
        InstrumentLibrary library = new InstrumentLibrary();
        SourceReference source = SourceReference.minimal("C:/rips", "DAC.ini", "81");
        byte[] data = new byte[]{0x10, 0x20, 0x30};

        library.addOrMerge(InstrumentLibraryEntry.dacSample(
                "Kick fast", data, 0x20, "PCM", null, null, null, "81", source), Instant.EPOCH);
        library.addOrMerge(InstrumentLibraryEntry.dacSample(
                "Kick slow", data, 0x30, "PCM", null, null, null, "81", source), Instant.EPOCH);

        assertEquals(2, library.entries(InstrumentAssetKind.DAC_SAMPLE).size());
    }
}
```

- [ ] **Step 2: Run model test and verify it fails**

Run:

```powershell
mvn -pl app -am -Dtest=TestInstrumentLibrary test
```

Expected: compilation fails because the `com.opensmpsdeck.library` classes do not exist.

- [ ] **Step 3: Implement minimal model classes**

Create `InstrumentAssetKind`, `SourceReference`, `AddResult`, `InstrumentLibraryEntry`, and `InstrumentLibrary`.

`AddResult`:

```java
package com.opensmpsdeck.library;

public record AddResult(boolean added, boolean changed, InstrumentLibraryEntry entry) {}
```

Core signatures:

```java
package com.opensmpsdeck.library;

public enum InstrumentAssetKind {
    FM_VOICE,
    PSG_ENVELOPE,
    MOD_ENVELOPE,
    DAC_SAMPLE
}
```

```java
package com.opensmpsdeck.library;

public record SourceReference(
        String scanRoot,
        String driverFamily,
        String gameName,
        String variantPath,
        String configExtension,
        String sourceSongFile,
        String sourceCompanionFile,
        String originalIndexOrId,
        String driverSummary) {

    public static SourceReference minimal(String scanRoot, String sourceCompanionFile, String originalIndexOrId) {
        return new SourceReference(scanRoot, "", "", "", "", "", sourceCompanionFile, originalIndexOrId, "");
    }
}
```

`InstrumentLibraryEntry` requirements:

```java
public final class InstrumentLibraryEntry {
    public static InstrumentLibraryEntry fmVoice(String displayName, byte[] voiceData, SourceReference source)
    public static InstrumentLibraryEntry psgEnvelope(String displayName, byte[] data, SourceReference source)
    public static InstrumentLibraryEntry modEnvelope(String displayName, byte[] data, SourceReference source)
    public static InstrumentLibraryEntry dacSample(String displayName, byte[] data, int rate,
            String compressionLabel, String pan, String param1, String param2, String dacId,
            SourceReference source)

    public String id()
    public InstrumentAssetKind kind()
    public String displayName()
    public String dedupeKey()
    public Instant createdTimestamp()
    public Instant updatedTimestamp()
    public List<SourceReference> sourceReferences()
    public byte[] data()
    public int algorithm()
    public int feedback()
    public int stepCount()
    public int playbackRate()
    public int byteLength()
    public String compressionLabel()
    public String dacId()
    public InstrumentLibraryEntry withTimestamps(Instant created, Instant updated)
    public InstrumentLibraryEntry withMergedSources(List<SourceReference> newSources, Instant now)
}
```

Dedupe keys:

```java
private static String dedupeKey(InstrumentAssetKind kind, byte[] data, int rate) {
    String hex = HexUtil.bytesToHex(data);
    return switch (kind) {
        case FM_VOICE -> "fm:" + hex;
        case PSG_ENVELOPE -> "psg:" + hex;
        case MOD_ENVELOPE -> "mod:" + hex;
        case DAC_SAMPLE -> "dac:" + rate + ":" + hex;
    };
}
```

`InstrumentLibrary` requirements:

```java
public final class InstrumentLibrary {
    public AddResult addOrMerge(InstrumentLibraryEntry entry, Instant now)
    public List<InstrumentLibraryEntry> entries()
    public List<InstrumentLibraryEntry> entries(InstrumentAssetKind kind)
    public boolean isDirty()
    public void clearDirty()
}
```

Only change `updatedTimestamp` and `dirty` when an entry is added or a new source reference is merged.

- [ ] **Step 4: Run model test and verify it passes**

Run:

```powershell
mvn -pl app -am -Dtest=TestInstrumentLibrary test
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 5: Write failing persistence tests**

Create `app/src/test/java/com/opensmpsdeck/io/TestInstrumentLibraryFile.java`:

```java
package com.opensmpsdeck.io;

import com.opensmpsdeck.library.InstrumentAssetKind;
import com.opensmpsdeck.library.InstrumentLibrary;
import com.opensmpsdeck.library.InstrumentLibraryEntry;
import com.opensmpsdeck.library.SourceReference;
import com.opensmpsdeck.model.FmVoice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TestInstrumentLibraryFile {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsJsonAndDacPayloads() throws Exception {
        InstrumentLibrary library = new InstrumentLibrary();
        SourceReference source = SourceReference.minimal(tempDir.toString(), "DAC.ini", "81");
        byte[] voice = new byte[FmVoice.VOICE_SIZE];
        voice[0] = 0x07;
        library.addOrMerge(InstrumentLibraryEntry.fmVoice("FM", voice, source), Instant.EPOCH);
        library.addOrMerge(InstrumentLibraryEntry.psgEnvelope("PSG", new byte[]{1, 2, (byte) 0x80}, source), Instant.EPOCH);
        library.addOrMerge(InstrumentLibraryEntry.modEnvelope("MOD", new byte[]{3, 4, (byte) 0x80}, source), Instant.EPOCH);
        library.addOrMerge(InstrumentLibraryEntry.dacSample(
                "DAC", new byte[]{0x40, 0x41}, 0x22, "DPCM", null, "01", "02", "81", source), Instant.EPOCH);

        InstrumentLibraryFile.save(library, tempDir);
        InstrumentLibrary loaded = InstrumentLibraryFile.load(tempDir);

        assertTrue(Files.exists(tempDir.resolve("library.json")));
        assertTrue(Files.isDirectory(tempDir.resolve("dac")));
        assertEquals(1, loaded.entries(InstrumentAssetKind.FM_VOICE).size());
        assertEquals(1, loaded.entries(InstrumentAssetKind.PSG_ENVELOPE).size());
        assertEquals(1, loaded.entries(InstrumentAssetKind.MOD_ENVELOPE).size());
        InstrumentLibraryEntry dac = loaded.entries(InstrumentAssetKind.DAC_SAMPLE).getFirst();
        assertArrayEquals(new byte[]{0x40, 0x41}, dac.data());
        assertEquals(0x22, dac.playbackRate());
        assertEquals("DPCM", dac.compressionLabel());
    }

    @Test
    void invalidJsonThrowsIOException() throws Exception {
        Files.writeString(tempDir.resolve("library.json"), "{ not json");

        assertThrows(java.io.IOException.class, () -> InstrumentLibraryFile.load(tempDir));
    }
}
```

- [ ] **Step 6: Run persistence test and verify it fails**

Run:

```powershell
mvn -pl app -am -Dtest=TestInstrumentLibraryFile test
```

Expected: compilation fails because `InstrumentLibraryFile` does not exist.

- [ ] **Step 7: Implement `InstrumentLibraryFile` and `LibraryPaths`**

Use `GsonBuilder().setPrettyPrinting().create()` and `HexUtil.bytesToHex` / `HexUtil.hexToBytes`. Save algorithm:

```java
public static void save(InstrumentLibrary library, Path root) throws IOException {
    Files.createDirectories(root);
    Files.createDirectories(root.resolve("dac"));
    JsonObject json = toJson(library, root);
    Path tmp = root.resolve("library.json.tmp");
    Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
    Files.move(tmp, root.resolve("library.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    library.clearDirty();
}
```

If `ATOMIC_MOVE` throws `AtomicMoveNotSupportedException`, retry with `REPLACE_EXISTING`.

For DAC payload files, write content-addressed files before writing `library.json`:

```java
private static String dacPayloadPath(InstrumentLibraryEntry entry) throws NoSuchAlgorithmException {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    digest.update(entry.data());
    digest.update((byte) entry.playbackRate());
    String hash = bytesToLowerHex(digest.digest());
    return "dac/" + hash + ".pcm";
}
```

`LibraryPaths`:

```java
public final class LibraryPaths {
    private static final Preferences PREFS =
            Preferences.userNodeForPackage(LibraryPaths.class).node("instrumentLibrary");
    private static final String ROOT_KEY = "root";

    public static Path getLibraryRoot()
    public static void setLibraryRoot(Path root)
    public static Path defaultLibraryRoot()
}
```

Default root:

```java
String appData = System.getenv("APPDATA");
if (appData != null && !appData.isBlank()) {
    return Path.of(appData, "OpenSMPSDeck", "instrument-library");
}
return Path.of(System.getProperty("user.home"), ".opensmpsdeck", "instrument-library");
```

- [ ] **Step 8: Run model and persistence tests**

Run:

```powershell
mvn -pl app -am -Dtest=TestInstrumentLibrary,TestInstrumentLibraryFile test
```

Expected: all tests pass.

- [ ] **Step 9: Commit Task 1**

```powershell
git add app/src/main/java/com/opensmpsdeck/library app/src/main/java/com/opensmpsdeck/io/InstrumentLibraryFile.java app/src/test/java/com/opensmpsdeck/library/TestInstrumentLibrary.java app/src/test/java/com/opensmpsdeck/io/TestInstrumentLibraryFile.java
git commit -m "feat: add instrument library persistence"
```

---

### Task 2: Deployment Into Active Songs

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/library/InstrumentLibraryDeployer.java`
- Create: `app/src/main/java/com/opensmpsdeck/library/DeployResult.java`
- Test: `app/src/test/java/com/opensmpsdeck/library/TestInstrumentLibraryDeployer.java`

- [ ] **Step 1: Write failing deployment tests**

Create `app/src/test/java/com/opensmpsdeck/library/TestInstrumentLibraryDeployer.java`:

```java
package com.opensmpsdeck.library;

import com.opensmpsdeck.model.DacSample;
import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.PsgEnvelope;
import com.opensmpsdeck.model.Song;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestInstrumentLibraryDeployer {

    @Test
    void appendsMissingAssetsAndReusesIdenticalOnSecondDeploy() {
        Song song = new Song();
        SourceReference source = SourceReference.minimal("C:/rips", "PSG.lst", "0");
        byte[] voiceData = new byte[FmVoice.VOICE_SIZE];
        voiceData[0] = 0x3F;
        List<InstrumentLibraryEntry> entries = List.of(
                InstrumentLibraryEntry.fmVoice("FM", voiceData, source),
                InstrumentLibraryEntry.psgEnvelope("PSG", new byte[]{1, 2, (byte) 0x80}, source),
                InstrumentLibraryEntry.modEnvelope("MOD", new byte[]{3, 4, (byte) 0x80}, source),
                InstrumentLibraryEntry.dacSample("DAC", new byte[]{0x55}, 0x20, "PCM", null, null, null, "81", source)
        );

        DeployResult first = InstrumentLibraryDeployer.deploy(song, entries);
        DeployResult second = InstrumentLibraryDeployer.deploy(song, entries);

        assertEquals(4, first.appendedCount());
        assertEquals(0, first.reusedCount());
        assertEquals(0, second.appendedCount());
        assertEquals(4, second.reusedCount());
        assertEquals(1, song.getVoiceBank().size());
        assertEquals(1, song.getPsgEnvelopes().size());
        assertEquals(1, song.getModEnvelopes().size());
        assertEquals(1, song.getDacSamples().size());
    }

    @Test
    void deploymentCopiesDataIntoSongModels() {
        Song song = new Song();
        SourceReference source = SourceReference.minimal("C:/rips", "DAC.ini", "81");
        byte[] dac = new byte[]{0x01, 0x02};
        InstrumentLibraryEntry entry = InstrumentLibraryEntry.dacSample(
                "DAC", dac, 0x44, "PCM", null, null, null, "81", source);

        InstrumentLibraryDeployer.deploy(song, List.of(entry));
        dac[0] = 0x7F;
        DacSample sample = song.getDacSamples().getFirst();

        assertArrayEquals(new byte[]{0x01, 0x02}, sample.getData());
        assertEquals(0x44, sample.getRate());
    }
}
```

- [ ] **Step 2: Run deployment test and verify it fails**

Run:

```powershell
mvn -pl app -am -Dtest=TestInstrumentLibraryDeployer test
```

Expected: compilation fails because `InstrumentLibraryDeployer` and `DeployResult` do not exist.

- [ ] **Step 3: Implement deployer**

Create `InstrumentLibraryDeployer`:

```java
package com.opensmpsdeck.library;

import com.opensmpsdeck.model.DacSample;
import com.opensmpsdeck.model.FmVoice;
import com.opensmpsdeck.model.PsgEnvelope;
import com.opensmpsdeck.model.Song;

import java.util.Arrays;
import java.util.List;

public final class InstrumentLibraryDeployer {
    private InstrumentLibraryDeployer() {}

    public static DeployResult deploy(Song song, List<InstrumentLibraryEntry> entries) {
        int appended = 0;
        int reused = 0;
        for (InstrumentLibraryEntry entry : entries) {
            boolean exists = switch (entry.kind()) {
                case FM_VOICE -> containsVoice(song, entry.data());
                case PSG_ENVELOPE -> containsEnvelope(song.getPsgEnvelopes(), entry.data());
                case MOD_ENVELOPE -> containsEnvelope(song.getModEnvelopes(), entry.data());
                case DAC_SAMPLE -> containsDac(song, entry.data(), entry.playbackRate());
            };
            if (exists) {
                reused++;
                continue;
            }
            append(song, entry);
            appended++;
        }
        return new DeployResult(appended, reused);
    }
}
```

Create `app/src/main/java/com/opensmpsdeck/library/DeployResult.java`:

```java
package com.opensmpsdeck.library;

public record DeployResult(int appendedCount, int reusedCount) {}
```

Append using current model fields only:

```java
case FM_VOICE -> song.getVoiceBank().add(new FmVoice(entry.displayName(), entry.data()));
case PSG_ENVELOPE -> song.getPsgEnvelopes().add(new PsgEnvelope(entry.displayName(), entry.data()));
case MOD_ENVELOPE -> song.getModEnvelopes().add(new PsgEnvelope(entry.displayName(), entry.data()));
case DAC_SAMPLE -> song.getDacSamples().add(new DacSample(entry.displayName(), entry.data(), entry.playbackRate()));
```

- [ ] **Step 4: Run deployment tests**

Run:

```powershell
mvn -pl app -am -Dtest=TestInstrumentLibraryDeployer test
```

Expected: all tests pass.

- [ ] **Step 5: Commit Task 2**

```powershell
git add app/src/main/java/com/opensmpsdeck/library/InstrumentLibraryDeployer.java app/src/test/java/com/opensmpsdeck/library/TestInstrumentLibraryDeployer.java
git commit -m "feat: deploy library assets into songs"
```

---

### Task 3: Config, Driver, And Capability Parsing

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/library/rip/SmpsRipConfig.java`
- Create: `app/src/main/java/com/opensmpsdeck/library/rip/SmpsRipConfigParser.java`
- Create: `app/src/main/java/com/opensmpsdeck/library/rip/SmpsDriverDefinition.java`
- Create: `app/src/main/java/com/opensmpsdeck/library/rip/CoordFlagDefinition.java`
- Create: `app/src/main/java/com/opensmpsdeck/library/rip/DialectCapability.java`
- Create: `app/src/main/java/com/opensmpsdeck/library/rip/DialectCapabilityClassifier.java`
- Test: `app/src/test/java/com/opensmpsdeck/library/rip/TestSmpsRipConfigParser.java`
- Test: `app/src/test/java/com/opensmpsdeck/library/rip/TestSmpsDriverDefinition.java`
- Test: `app/src/test/java/com/opensmpsdeck/library/rip/TestCoordFlagDefinition.java`
- Test: `app/src/test/java/com/opensmpsdeck/library/rip/TestDialectCapabilityClassifier.java`

- [ ] **Step 1: Write parser tests**

Create tests with these focused assertions:

```java
@Test
void configParserResolvesSectionKeys() throws Exception {
    Path dir = tempDir;
    Files.writeString(dir.resolve("config.ini"), """
            [.s3k]
            Driver=DefDrv.txt
            Commands=DefCFlag.txt
            Drums=DefDrum.txt
            VolEnv=PSG.lst
            ModEnv=Modulat.lst
            DAC=DAC_Voice.ini
            GlobalInsLib=InsSet.17D8.bin
            """);

    SmpsRipConfig config = SmpsRipConfigParser.parse(dir.resolve("config.ini"));

    assertEquals(1, config.sections().size());
    SmpsRipConfig.Section section = config.sections().get(".s3k");
    assertEquals(".s3k", section.extension());
    assertEquals(dir.resolve("DAC_Voice.ini").normalize(), section.resolve("DAC"));
    assertEquals(dir.resolve("InsSet.17D8.bin").normalize(), section.resolve("GlobalInsLib"));
}
```

```java
@Test
void driverDefinitionParsesKeyProperties() throws Exception {
    Path file = tempDir.resolve("DefDrv.txt");
    Files.writeString(file, """
            PtrFmt=Z80
            TempoMode=Overflow
            InsMode=Default
            InsRegs=Bit7
            FMChnOrder=0,1,2,3,4,5
            PSGChnOrder=0,1,2,3
            FMBaseNote=0x81
            FMBaseOctave=2
            VolMode=SMPS
            DACChns=1
            """);

    SmpsDriverDefinition definition = SmpsDriverDefinition.parse(file);

    assertEquals("Z80", definition.ptrFmt());
    assertEquals("Overflow", definition.tempoMode());
    assertEquals("Default", definition.insMode());
    assertEquals("Bit7", definition.insRegs());
    assertFalse(definition.hasPreSmpsTrackHeader());
}
```

```java
@Test
void coordFlagDefinitionParsesLengthsAndJumpOffsets() throws Exception {
    Path file = tempDir.resolve("DefCFlag.txt");
    Files.writeString(file, """
            [Main]
            E6\tVolume\tLen=1
            F7\tJump\tLen=2\tJmpOfs=0
            [Meta]
            01\tMetaJump\tLen=2\tJmpOfs=0
            """);

    CoordFlagDefinition definition = CoordFlagDefinition.parse(file);

    assertEquals(1, definition.mainCommand(0xE6).parameterLength());
    assertEquals(2, definition.mainCommand(0xF7).parameterLength());
    assertEquals(0, definition.mainCommand(0xF7).jumpOffset());
    assertEquals(2, definition.metaCommand(0x01).parameterLength());
}
```

```java
@Test
void beforeParameterizedImportOnlyExistingModesAreFullImport() {
    SmpsDriverDefinition s2 = new SmpsDriverDefinition("Z80", "Timeout", "Default", "Bit7", false);
    SmpsDriverDefinition mmw = new SmpsDriverDefinition("68k", "Timeout", "Default", "Algo", false);

    assertEquals(DialectCapability.FULL_IMPORT,
            DialectCapabilityClassifier.classify(".sm2", s2, true));
    assertEquals(DialectCapability.ASSET_ONLY,
            DialectCapabilityClassifier.classify(".mmw", mmw, true));
}
```

- [ ] **Step 2: Run parser tests and verify they fail**

Run:

```powershell
mvn -pl app -am -Dtest=TestSmpsRipConfigParser,TestSmpsDriverDefinition,TestCoordFlagDefinition,TestDialectCapabilityClassifier test
```

Expected: compilation fails because the rip parser package does not exist.

- [ ] **Step 3: Implement parsers and classifier**

Implementation requirements:

```java
public record SmpsRipConfig(Path configFile, Map<String, Section> sections) {
    public record Section(String extension, Path directory, Map<String, String> values) {
        public String value(String key)
        public Path resolve(String key)
    }
}
```

`SmpsRipConfigParser` rules:

- Trim whitespace.
- Ignore blank lines and lines starting with `;` or `#`.
- Section headers are lines starting `[` and ending `]`.
- Keys are case-insensitive for lookup; preserve original values.
- Resolve relative file values against the config directory.

`SmpsDriverDefinition` must expose:

```java
public record SmpsDriverDefinition(
        String ptrFmt,
        String tempoMode,
        String insMode,
        String insRegs,
        boolean hasPreSmpsTrackHeader) {

    public static SmpsDriverDefinition parse(Path file) throws IOException
    public String summary()
}
```

Keep additional parsed fields in a `Map<String, String>` if the implementation needs them for source references.

`CoordFlagDefinition` must expose:

```java
public record CoordFlagCommand(int byteValue, String name, int parameterLength, int jumpOffset) {}

public final class CoordFlagDefinition {
    public static CoordFlagDefinition parse(Path file) throws IOException
    public CoordFlagCommand mainCommand(int byteValue)
    public CoordFlagCommand metaCommand(int byteValue)
}
```

Parse both `Len=2` style cells and tabular cells containing `Len` and `JmpOfs`. Store `jumpOffset` as `-1` when missing.

`DialectCapabilityClassifier` rules before parameterized import:

```java
private static final Set<String> CURRENT_FULL_IMPORT_EXTENSIONS = Set.of(".smp", ".sm2", ".s3k", ".bin");
```

Return `FULL_IMPORT` only when the normalized extension is in that set and the file is a song candidate from the config section. Return `ASSET_ONLY` for parseable config sections outside that set. Return `UNSUPPORTED` for preSMPS track headers or missing driver definitions only when a song file is selected for import; companion harvesting still runs.

- [ ] **Step 4: Run parser tests**

Run:

```powershell
mvn -pl app -am -Dtest=TestSmpsRipConfigParser,TestSmpsDriverDefinition,TestCoordFlagDefinition,TestDialectCapabilityClassifier test
```

Expected: all parser tests pass.

- [ ] **Step 5: Commit Task 3**

```powershell
git add app/src/main/java/com/opensmpsdeck/library/rip app/src/test/java/com/opensmpsdeck/library/rip
git commit -m "feat: parse smps rip dialect metadata"
```

---

### Task 4: Companion Asset Harvesting

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/library/harvest/DacCodec.java`
- Create: `app/src/main/java/com/opensmpsdeck/library/harvest/EnvelopeListParser.java`
- Create: `app/src/main/java/com/opensmpsdeck/library/harvest/DacIniParser.java`
- Create: `app/src/main/java/com/opensmpsdeck/library/harvest/InsSetVoiceParser.java`
- Create: `app/src/main/java/com/opensmpsdeck/library/harvest/CompanionAssetHarvester.java`
- Create: `app/src/main/java/com/opensmpsdeck/library/harvest/HarvestResult.java`
- Modify: `app/src/main/java/com/opensmpsdeck/io/SmpsImporter.java`
- Test: `app/src/test/java/com/opensmpsdeck/library/harvest/TestDacIniParser.java`
- Test: `app/src/test/java/com/opensmpsdeck/library/harvest/TestInsSetVoiceParser.java`
- Test: `app/src/test/java/com/opensmpsdeck/library/harvest/TestCompanionAssetHarvester.java`

- [ ] **Step 1: Write harvesting tests**

Create these tests:

```java
@Test
void dacIniParserHandlesMetadataAndDpcm() throws Exception {
    Files.createDirectories(tempDir.resolve("DAC"));
    Files.write(tempDir.resolve("DAC").resolve("Kick.dpcm"), new byte[]{0x12});
    Files.writeString(tempDir.resolve("DAC_Voice.ini"), """
            [81]
            Compr=DPCM
            File=DAC\\Kick.dpcm
            Rate=0x22
            Pan=40
            Param1=01
            Param2=02
            """);

    List<DacIniParser.Entry> entries = DacIniParser.parse(tempDir.resolve("DAC_Voice.ini"));

    assertEquals(1, entries.size());
    assertEquals(0x81, entries.getFirst().id());
    assertEquals("DPCM", entries.getFirst().compressionLabel());
    assertEquals(0x22, entries.getFirst().rate());
    assertEquals("01", entries.getFirst().param1());
}
```

```java
@Test
void insSetParserNormalizesDefaultOrderingAndSkipsUnsupported() throws Exception {
    byte[] nativeOrder = new byte[FmVoice.VOICE_SIZE];
    for (int i = 0; i < nativeOrder.length; i++) nativeOrder[i] = (byte) i;
    Files.write(tempDir.resolve("InsSet.17D8.bin"), nativeOrder);

    SmpsDriverDefinition defaultDefinition =
            new SmpsDriverDefinition("Z80", "Overflow", "Default", "Bit7", false);
    SmpsDriverDefinition customDefinition =
            new SmpsDriverDefinition("Z80", "Overflow", "Custom", "Bit7", false);

    List<FmVoice> voices = InsSetVoiceParser.parse(tempDir.resolve("InsSet.17D8.bin"), defaultDefinition);
    List<FmVoice> skipped = InsSetVoiceParser.parse(tempDir.resolve("InsSet.17D8.bin"), customDefinition);

    assertArrayEquals(FmVoice.swapMiddleOperators(nativeOrder), voices.getFirst().getData());
    assertTrue(skipped.isEmpty());
}
```

```java
@Test
void companionHarvesterAddsEnvelopeDacAndInsSetAssets() throws Exception {
    writeLst(tempDir.resolve("PSG.lst"), "Env", new byte[]{1, 2, (byte) 0x80});
    writeLst(tempDir.resolve("Modulat.lst"), "Mod", new byte[]{3, 4, (byte) 0x80});
    Files.createDirectories(tempDir.resolve("DAC"));
    Files.write(tempDir.resolve("DAC").resolve("Kick.bin"), new byte[]{0x40});
    Files.writeString(tempDir.resolve("DAC.ini"), """
            [81]
            Compr=PCM
            File=DAC\\Kick.bin
            Rate=32
            """);
    byte[] voice = new byte[FmVoice.VOICE_SIZE];
    Files.write(tempDir.resolve("InsSet.17D8.bin"), voice);

    SmpsRipConfig.Section section = new SmpsRipConfig.Section(".s3k", tempDir, Map.of(
            "VolEnv", "PSG.lst",
            "ModEnv", "Modulat.lst",
            "DAC", "DAC.ini",
            "GlobalInsLib", "InsSet.17D8.bin"));
    InstrumentLibrary library = new InstrumentLibrary();

    HarvestResult result = CompanionAssetHarvester.harvest(
            library, tempDir, section,
            new SmpsDriverDefinition("Z80", "Overflow", "Default", "Bit7", false),
            SourceReference.minimal(tempDir.toString(), "config.ini", ".s3k"));

    assertEquals(4, result.addedCount());
    assertEquals(1, library.entries(InstrumentAssetKind.PSG_ENVELOPE).size());
    assertEquals(1, library.entries(InstrumentAssetKind.MOD_ENVELOPE).size());
    assertEquals(1, library.entries(InstrumentAssetKind.DAC_SAMPLE).size());
    assertEquals(1, library.entries(InstrumentAssetKind.FM_VOICE).size());
}
```

The test helper `writeLst` writes the existing `LST_ENV` format used by `SmpsImporter.parsePsgLst`.

- [ ] **Step 2: Run harvesting tests and verify they fail**

Run:

```powershell
mvn -pl app -am -Dtest=TestDacIniParser,TestInsSetVoiceParser,TestCompanionAssetHarvester test
```

Expected: compilation fails because harvesting classes do not exist.

- [ ] **Step 3: Implement `DacCodec` and update `SmpsImporter`**

Move the DPCM delta table behavior into `DacCodec`:

```java
package com.opensmpsdeck.library.harvest;

public final class DacCodec {
    private static final int[] DPCM_DELTA_TABLE = {
            0, 1, 2, 4, 8, 16, 32, 64,
            -128, -1, -2, -4, -8, -16, -32, -64
    };

    public static byte[] decompressDpcm(byte[] compressed) {
        byte[] output = new byte[compressed.length * 2];
        int accumulator = 0x80;
        for (int i = 0; i < compressed.length; i++) {
            int b = compressed[i] & 0xFF;
            accumulator = (accumulator + DPCM_DELTA_TABLE[(b >> 4) & 0x0F]) & 0xFF;
            output[i * 2] = (byte) accumulator;
            accumulator = (accumulator + DPCM_DELTA_TABLE[b & 0x0F]) & 0xFF;
            output[i * 2 + 1] = (byte) accumulator;
        }
        return output;
    }
}
```

In `SmpsImporter`, replace calls to `decompressDpcm(raw)` with `DacCodec.decompressDpcm(raw)` and keep existing importer behavior unchanged.

- [ ] **Step 4: Implement DAC and InsSet parsers**

`DacIniParser.Entry`:

```java
public record Entry(
        int id,
        String compressionLabel,
        String file,
        int rate,
        String pan,
        String param1,
        String param2) {

    public boolean isDpcm() {
        return "DPCM".equalsIgnoreCase(compressionLabel);
    }
}
```

`DacIniParser.parse(Path ini)` must support `Compr=True`, `Compr=DPCM`, `Compr=PCM`, `File`, `Rate`, `Pan`, `Param1`, and `Param2`. Parse section ids as hex.

`InsSetVoiceParser.parse(Path file, SmpsDriverDefinition definition)` rules:

- Return empty list for `InsMode=Custom` or `InsMode=Interleaved`.
- Read consecutive `FmVoice.VOICE_SIZE` chunks.
- For `InsMode=Default`, normalize with `FmVoice.swapMiddleOperators`.
- For `InsMode=Hardware`, keep bytes only when `InsRegs=Algo` or `InsRegs=Bit7` has a confirmed mapping in tests; otherwise return empty list.
- Never return raw bytes for unknown layouts.

- [ ] **Step 5: Implement `CompanionAssetHarvester`**

Required public entry:

```java
public final class CompanionAssetHarvester {
    public static HarvestResult harvest(
            InstrumentLibrary library,
            Path scanRoot,
            SmpsRipConfig.Section section,
            SmpsDriverDefinition driver,
            SourceReference baseSource)
}
```

Create `app/src/main/java/com/opensmpsdeck/library/harvest/HarvestResult.java`:

```java
package com.opensmpsdeck.library.harvest;

import java.util.List;

public record HarvestResult(
        int addedCount,
        int duplicateCount,
        int skippedCount,
        List<String> warnings) {}
```

Create `EnvelopeListParser`:

```java
package com.opensmpsdeck.library.harvest;

import com.opensmpsdeck.model.PsgEnvelope;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class EnvelopeListParser {
    private EnvelopeListParser() {}

    public static List<PsgEnvelope> parse(byte[] data) {
        List<PsgEnvelope> envelopes = new ArrayList<>();
        if (data.length < 8) return envelopes;
        String header = new String(data, 0, 7, StandardCharsets.US_ASCII);
        if (!"LST_ENV".equals(header)) return envelopes;
        int count = data[7] & 0xFF;
        int pos = 8;
        for (int i = 0; i < count && pos < data.length; i++) {
            int nameLen = data[pos++] & 0xFF;
            if (pos + nameLen > data.length) break;
            String envName = new String(data, pos, nameLen, StandardCharsets.US_ASCII);
            pos += nameLen;
            if (pos >= data.length) break;
            int dataLen = data[pos++] & 0xFF;
            if (pos + dataLen > data.length) break;
            byte[] envData = new byte[dataLen];
            System.arraycopy(data, pos, envData, 0, dataLen);
            pos += dataLen;
            envelopes.add(new PsgEnvelope(envName, envData));
        }
        return envelopes;
    }
}
```

Update `SmpsImporter.parsePsgLst(byte[])` to return `EnvelopeListParser.parse(data)` so current importer tests keep their existing entry point.

DAC file resolution order:

1. `DAC/uncompressed/<basename>` as raw PCM.
2. Direct path relative to the config directory.
3. `DAC/<basename>`.

- [ ] **Step 6: Run harvesting tests and importer regression tests**

Run:

```powershell
mvn -pl app -am -Dtest=TestDacIniParser,TestInsSetVoiceParser,TestCompanionAssetHarvester,TestSmpsImporter test
```

Expected: all selected tests pass.

- [ ] **Step 7: Commit Task 4**

```powershell
git add app/src/main/java/com/opensmpsdeck/library/harvest app/src/main/java/com/opensmpsdeck/io/SmpsImporter.java app/src/test/java/com/opensmpsdeck/library/harvest
git commit -m "feat: harvest configured smps companion assets"
```

---

### Task 5: Recursive Scanner And Summary

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/library/scan/InstrumentLibraryScanner.java`
- Create: `app/src/main/java/com/opensmpsdeck/library/scan/ScanSummary.java`
- Create: `app/src/main/java/com/opensmpsdeck/library/scan/ScanFailure.java`
- Test: `app/src/test/java/com/opensmpsdeck/library/scan/TestInstrumentLibraryScanner.java`

- [ ] **Step 1: Write scanner tests**

Create tests:

```java
@Test
void recursiveScanFindsNestedConfigAndIsIdempotent() throws Exception {
    Path game = tempDir.resolve("Z80").resolve("Sonic 2");
    Files.createDirectories(game.resolve("DAC"));
    Files.writeString(game.resolve("config.ini"), """
            [.sm2]
            Driver=DefDrv.txt
            Commands=DefCFlag.txt
            VolEnv=PSG.lst
            ModEnv=Modulat.lst
            DAC=DAC.ini
            GlobalInsLib=InsSet.17D8.bin
            """);
    Files.writeString(game.resolve("DefDrv.txt"), """
            PtrFmt=Z80
            TempoMode=Timeout
            InsMode=Default
            InsRegs=Bit7
            """);
    Files.writeString(game.resolve("DefCFlag.txt"), "[Main]\nE6\tVolume\tLen=1\n");
    writeLst(game.resolve("PSG.lst"), "Env", new byte[]{1, (byte) 0x80});
    writeLst(game.resolve("Modulat.lst"), "Mod", new byte[]{2, (byte) 0x80});
    Files.write(game.resolve("DAC").resolve("Kick.bin"), new byte[]{0x40});
    Files.writeString(game.resolve("DAC.ini"), """
            [81]
            Compr=PCM
            File=DAC\\Kick.bin
            Rate=32
            """);
    Files.write(game.resolve("InsSet.17D8.bin"), new byte[FmVoice.VOICE_SIZE]);

    InstrumentLibrary library = new InstrumentLibrary();
    ScanSummary first = new InstrumentLibraryScanner().scan(tempDir, library);
    library.clearDirty();
    ScanSummary second = new InstrumentLibraryScanner().scan(tempDir, library);

    assertEquals(1, first.configDirectoriesFound());
    assertEquals(4, first.newAssets());
    assertEquals(0, second.newAssets());
    assertFalse(library.isDirty());
}
```

```java
@Test
void nonCurrentImportDialectIsAssetOnlyBeforeLayerSeven() throws Exception {
    Path game = tempDir.resolve("68k").resolve("Aah Harimanada");
    Files.createDirectories(game);
    Files.writeString(game.resolve("config.ini"), """
            [.trs]
            Driver=DefDrv.txt
            Commands=DefCFlag.txt
            VolEnv=PSG.lst
            """);
    Files.writeString(game.resolve("DefDrv.txt"), """
            PtrFmt=68k
            TempoMode=Timeout
            InsMode=Default
            InsRegs=Algo
            """);
    Files.writeString(game.resolve("DefCFlag.txt"), "[Main]\nE6\tVolume\tLen=1\n");
    writeLst(game.resolve("PSG.lst"), "Env", new byte[]{1, (byte) 0x80});
    Files.write(game.resolve("01 Song.trs"), new byte[]{0, 0, 0, 0});

    InstrumentLibrary library = new InstrumentLibrary();
    ScanSummary summary = new InstrumentLibraryScanner().scan(tempDir, library);

    assertEquals(0, summary.fullSongImportsAttempted());
    assertEquals(1, summary.assetOnlyFoldersHarvested());
    assertEquals(1, library.entries(InstrumentAssetKind.PSG_ENVELOPE).size());
}
```

- [ ] **Step 2: Run scanner tests and verify they fail**

Run:

```powershell
mvn -pl app -am -Dtest=TestInstrumentLibraryScanner test
```

Expected: compilation fails because scanner classes do not exist.

- [ ] **Step 3: Implement scan summary records**

`ScanSummary` must include:

```java
public record ScanSummary(
        int filesVisited,
        int configDirectoriesFound,
        int fullSongImportsAttempted,
        int fullSongImportsSucceeded,
        int assetOnlyFoldersHarvested,
        int unsupportedSongDialects,
        int newAssets,
        int duplicateAssets,
        Map<InstrumentAssetKind, Integer> newAssetsByKind,
        Map<InstrumentAssetKind, Integer> duplicateAssetsByKind,
        Map<InstrumentAssetKind, Integer> totalLibraryCountsByKind,
        List<ScanFailure> failures) {}
```

`ScanFailure`:

```java
public record ScanFailure(Path path, String reason) {}
```

- [ ] **Step 4: Implement recursive scanner**

Requirements:

- Walk from selected root with `Files.walkFileTree`.
- Do not follow symlinks.
- Keep a `Set<Path>` of `toRealPath()` directories to prevent loops.
- Treat directories containing `config.ini` as rip contexts.
- For each config section, parse `Driver` and `Commands`; record failures but continue.
- Call `CompanionAssetHarvester.harvest` for every parseable section.
- Attempt `SmpsImporter.importFile` only for song files whose extension classifier returns `FULL_IMPORT`.
- Do not treat arbitrary `.bin` files as songs under a config directory unless the config section extension is `.bin`.
- Build source references from root-relative path: first segment is driver family, second is game name, remaining parent path is variant.

Full import harvesting:

```java
Song imported = new SmpsImporter().importFile(songFile.toFile());
for (FmVoice voice : imported.getVoiceBank()) {
    library.addOrMerge(InstrumentLibraryEntry.fmVoice(voice.getName(), voice.getData(), source), now);
}
for (PsgEnvelope env : imported.getPsgEnvelopes()) {
    library.addOrMerge(InstrumentLibraryEntry.psgEnvelope(env.getName(), env.getData(), source), now);
}
for (PsgEnvelope env : imported.getModEnvelopes()) {
    library.addOrMerge(InstrumentLibraryEntry.modEnvelope(env.getName(), env.getData(), source), now);
}
for (DacSample sample : imported.getDacSamples()) {
    library.addOrMerge(InstrumentLibraryEntry.dacSample(
            sample.getName(), sample.getData(), sample.getRate(), "", null, null, null, "", source), now);
}
```

- [ ] **Step 5: Run scanner tests**

Run:

```powershell
mvn -pl app -am -Dtest=TestInstrumentLibraryScanner test
```

Expected: all scanner tests pass.

- [ ] **Step 6: Commit Task 5**

```powershell
git add app/src/main/java/com/opensmpsdeck/library/scan app/src/test/java/com/opensmpsdeck/library/scan/TestInstrumentLibraryScanner.java
git commit -m "feat: scan smps rip folders into library"
```

---

### Task 6: JavaFX Library UI

**Files:**
- Create: `app/src/main/java/com/opensmpsdeck/ui/LibraryActions.java`
- Create: `app/src/main/java/com/opensmpsdeck/ui/LibraryBrowserDialog.java`
- Create: `app/src/main/java/com/opensmpsdeck/ui/LibraryLocationDialog.java`
- Create: `app/src/main/java/com/opensmpsdeck/ui/ScanSummaryDialog.java`
- Modify: `app/src/main/java/com/opensmpsdeck/ui/MainWindow.java`

- [ ] **Step 1: Add `LibraryActions`**

Constructor:

```java
final class LibraryActions {
    LibraryActions(Stage stage, Supplier<SongTab> activeSongTab, Runnable refreshTitles)
}
```

Methods:

```java
void onOpenLibrary()
void onScanFolder()
void onLibraryLocation()
```

Behavior:

- `onOpenLibrary` loads `InstrumentLibraryFile.load(LibraryPaths.getLibraryRoot())`, opens `LibraryBrowserDialog`, deploys selected assets through `InstrumentLibraryDeployer`, then calls `songTab.getInstrumentPanel().refresh()`, `songTab.setDirty(true)` only when appended count is greater than zero, and `refreshTitles.run()`.
- `onScanFolder` uses `DirectoryChooser`, applies `DialogPaths.applyTo(chooser, "instrumentLibraryScan")`, remembers the chosen directory, runs `InstrumentLibraryScanner` in a JavaFX `Task<ScanSummary>`, saves with `InstrumentLibraryFile.save` only when `library.isDirty()`, then shows `ScanSummaryDialog`.
- `onLibraryLocation` opens `LibraryLocationDialog` and stores the chosen path in `LibraryPaths`.

- [ ] **Step 2: Add browser dialog**

`LibraryBrowserDialog` should extend `Dialog<List<InstrumentLibraryEntry>>`.

Controls:

- `TabPane` with tabs for FM Voices, PSG Envelopes, Mod Envelopes, DAC Samples.
- One `TableView<InstrumentLibraryEntry>` per tab.
- `Deploy` and `Cancel` buttons through `DialogPane`.
- `Reveal Sources` button showing source references in an `Alert`.
- `Preview` button disabled for asset kinds without a current preview path.

Columns:

```java
displayName
source game
variant
source song or companion file
driver family
config extension
source count
algorithm / feedback for FM
step count for PSG and mod
rate / byte length for DAC
```

Return all selected entries from all tabs when `Deploy` is pressed.

- [ ] **Step 3: Add location and summary dialogs**

`LibraryLocationDialog`:

- Show current `LibraryPaths.getLibraryRoot()`.
- Provide `Choose...` using `DirectoryChooser`.
- Return selected `Path`.

`ScanSummaryDialog`:

- Show the counters from `ScanSummary`.
- Show failures grouped as `path: reason` in a non-editable `TextArea`.

- [ ] **Step 4: Wire `MainWindow` menu**

Modify `MainWindow`:

```java
private final LibraryActions libraryActions;
```

Initialize after `fileActions`:

```java
this.libraryActions = new LibraryActions(stage, this::getActiveSongTab, this::refreshActiveTabTitle);
```

In `createMenuBar()` add:

```java
Menu libraryMenu = new Menu("Library");
MenuItem openLibraryItem = new MenuItem("Open Library...");
openLibraryItem.setOnAction(e -> libraryActions.onOpenLibrary());
MenuItem scanFolderItem = new MenuItem("Scan Folder...");
scanFolderItem.setOnAction(e -> libraryActions.onScanFolder());
MenuItem libraryLocationItem = new MenuItem("Library Location...");
libraryLocationItem.setOnAction(e -> libraryActions.onLibraryLocation());
libraryMenu.getItems().addAll(openLibraryItem, scanFolderItem, new SeparatorMenuItem(), libraryLocationItem);
menuBar.getMenus().addAll(fileMenu, libraryMenu);
```

If `getActiveSongTab()` is private and unavailable to the constructor expression, add:

```java
private SongTab getActiveSongTab() {
    Tab selected = tabPane.getSelectionModel().getSelectedItem();
    return selected != null ? tabMap.get(selected) : null;
}
```

- [ ] **Step 5: Compile UI**

Run:

```powershell
mvn -pl app -am -DskipTests compile
```

Expected: compilation succeeds.

- [ ] **Step 6: Run focused non-UI tests**

Run:

```powershell
mvn -pl app -am -Dtest=TestInstrumentLibrary,TestInstrumentLibraryFile,TestInstrumentLibraryDeployer,TestInstrumentLibraryScanner test
```

Expected: all selected tests pass.

- [ ] **Step 7: Commit Task 6**

```powershell
git add app/src/main/java/com/opensmpsdeck/ui/LibraryActions.java app/src/main/java/com/opensmpsdeck/ui/LibraryBrowserDialog.java app/src/main/java/com/opensmpsdeck/ui/LibraryLocationDialog.java app/src/main/java/com/opensmpsdeck/ui/ScanSummaryDialog.java app/src/main/java/com/opensmpsdeck/ui/MainWindow.java
git commit -m "feat: add instrument library ui"
```

---

### Task 7: Final Verification And Documentation

**Files:**
- Modify: `docs/superpowers/specs/2026-06-13-user-local-instrument-library-design.md` only if implementation discovers a design correction.
- Modify: this plan file only to check off completed steps during execution.

- [ ] **Step 1: Run full app tests**

Run:

```powershell
mvn -pl app -am test
```

Expected: app and required synth-core tests pass.

- [ ] **Step 2: Run full repo tests when Task 1-6 are complete**

Run:

```powershell
mvn test
```

Expected: all module tests pass.

- [ ] **Step 3: Manual smoke test**

Run the app through the app module main class:

```powershell
mvn exec:java -pl app -Dexec.mainClass=com.opensmpsdeck.Launcher
```

Manual checks:

- `Library > Library Location...` opens and displays a writable user-local root.
- `Library > Scan Folder...` scans a single synthetic test rip folder.
- `Library > Open Library...` shows tabs for FM, PSG, mod, and DAC assets.
- Deploying one asset of each kind appends to the active song.
- Re-deploying the same assets reports reused/skipped counts and does not duplicate entries.
- Saving and reloading the project keeps deployed song assets.

- [ ] **Step 4: Confirm final git state**

Run:

```powershell
git status --short
```

Expected: only intentional plan checkboxes or unrelated pre-existing files are shown.
