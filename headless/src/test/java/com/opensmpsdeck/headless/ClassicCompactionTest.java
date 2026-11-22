package com.opensmpsdeck.headless;

import com.openggf.audio.synth.ChipWriteObserver;
import com.opensmpsdeck.headless.rom.RomLibrary;
import com.opensmpsdeck.project.ClassicBankEncoder;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.GroupArrangement;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NativeProgram;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler.BankLimitException;
import com.opensmpsdeck.project.ProjectCompiler.Encoding;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.TargetGame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ClassicCompactionTest {
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(ClassicPackageDriver.class)
    void repeatedMaterialUsesLoopsAndCalls(ClassicPackageDriver driver) throws Exception {
        var source = repeatedProject(driver);
        var compact = driver.compile(source, source.nativeTempo());
        var linear = driver.compile(source, source.nativeTempo(), Encoding.LINEAR);
        assertTrue(compact.bytes().length < linear.bytes().length - 10);
        var repeated = driver.compile(source, source.nativeTempo());
        assertArrayEquals(compact.bytes(), repeated.bytes());
        assertEquals(compact.relocations(), repeated.relocations());
        var flow = controlOffsets(driver, compact.bytes());
        var loop = assertSingle(flow, compact.bytes(), 0xF7);
        assertEquals(0, compact.bytes()[loop + 1]);
        assertEquals(3, compact.bytes()[loop + 2]);
        assertTrue(ClassicPackageBank.branchTarget(driver, compact.bytes(), 0, loop + 3) < loop);
        assertEquals(2, flow.stream().filter(at -> (compact.bytes()[at] & 255) == 0xF8).count());
        assertSingle(flow, compact.bytes(), 0xE3);
        assertPlayback(source);
    }

    @ParameterizedTest
    @EnumSource(ClassicPackageDriver.class)
    void portableAndDisassemblyRoundTrip(ClassicPackageDriver driver) throws Exception {
        var source = repeatedProject(driver);
        var layout = driver.compile(source, source.nativeTempo());
        var archive = writePackage(source, driver);
        var pkg = PortablePackageReader.read(archive);
        var compiled = PortablePackageReader.assemble(pkg, List.of());
        assertArrayEquals(layout.bytes(), compiled.song());
        assertEquals(layout.relocations(), pkg.manifest().bank().relocations());
        assertTrue(pkg.manifest().bank().relocationsComplete());
        assertEquals(driver == ClassicPackageDriver.S1 ? 2 : 5, layout.relocations().size());

        var kit = DisassemblyKit.build(pkg, List.of(), false, List.of());
        int base = driver == ClassicPackageDriver.S1 ? 0 : 0x8000;
        byte[] rebased = layout.bytes().clone();
        if (base != 0) {
            for (int at : layout.relocations()) rebased[at + 1] += (byte) 0x80;
        }
        assertArrayEquals(rebased, ClassicDisasmTestAssembler.assemble(kit, base == 0, base));
        assertRenderIdentity(driver, compiled, withBank(compiled, rebased, base), 180);
    }

    @ParameterizedTest
    @EnumSource(ClassicPackageDriver.class)
    void rejectsInvalidTargetsAndNesting(ClassicPackageDriver driver) {
        byte[] song = driver.compile(repeatedProject(driver), 255).bytes();
        var flow = controlOffsets(driver, song);
        int loop = assertSingle(flow, song, 0xF7);
        int ret = assertSingle(flow, song, 0xE3);
        int call = flow.stream().filter(at -> (song[at] & 255) == 0xF8).findFirst().orElseThrow();
        int body = ClassicPackageBank.branchTarget(driver, song, 0, call + 1);

        byte[] wrongCounter = song.clone();
        wrongCounter[loop + 1] = 1;
        assertInvalid(driver, wrongCounter, "counter 0");
        byte[] zeroCount = song.clone();
        zeroCount[loop + 2] = 0;
        assertInvalid(driver, zeroCount, "nonzero count");
        byte[] intoDuration = song.clone();
        int entry = ClassicPackageBank.branchTarget(driver, song, 0, loop + 3);
        point(driver, intoDuration, loop + 3, entry + 1);
        assertInvalid(driver, intoDuration, "event start");
        byte[] outside = song.clone();
        point(driver, outside, call + 1, song.length);
        assertInvalid(driver, outside, "outside the track region");
        byte[] intoMain = song.clone();
        point(driver, intoMain, call + 1, entry);
        assertInvalid(driver, intoMain, "standalone routine");
        byte[] nested = song.clone();
        nested[body] = (byte) 0xF8;
        point(driver, nested, body + 1, body);
        assertInvalid(driver, nested, "nested control flow");
        byte[] wrongReturn = song.clone();
        wrongReturn[ret] = (byte) 0xF9;
        assertInvalid(driver, wrongReturn, "unsupported stream command F9");
    }

    @ParameterizedTest
    @EnumSource(ClassicPackageDriver.class)
    void reportsExactSizeAndFitsLargeRepetition(ClassicPackageDriver driver) {
        int ticks = 20_000;
        var sequence = new Sequence("psg", "One note", "PSG", 1, null, 127,
                List.of(new NoteEvent("note", 0, 1, 60)));
        var source = new StudioProject(4, "Long repeats", "S3K", 24, 0, List.of(), List.of(sequence),
                List.of(new Clip("clip", "psg", "PSG1", 0, ticks)), driver.profile.game(), 255);
        var compact = driver.compile(source, 255);
        assertTrue(compact.bytes().length < 2000);
        var error = assertThrows(BankLimitException.class, () -> driver.compile(source, 255, Encoding.LINEAR));
        assertEquals(17 + 9L * ticks + 1, error.compiledBytes());
        assertEquals(32768, error.limitBytes());
        assertTrue(error.getMessage().contains("Shorten the song"));
    }

    @ParameterizedTest
    @EnumSource(ClassicPackageDriver.class)
    void acceptsForwardJumpsAndRejectsIndefiniteJumps(ClassicPackageDriver driver) {
        byte[] bank = java.util.HexFormat.of().parseHex("0000010101ff00000000000000000000f2f6000090029102f2");
        bank[driver == ClassicPackageDriver.S1 ? 7 : 6] = 16;
        bank[driver == ClassicPackageDriver.S1 ? 11 : 10] = 17;
        point(driver, bank, 18, 22);
        assertEquals(List.of(17), controlOffsets(driver, bank));
        point(driver, bank, 18, 17);
        assertInvalid(driver, bank, "must be forward");
    }

    @Test void refusesUnequalTransposeVolumeAndHoldBoundaries() {
        for (var driver : ClassicPackageDriver.values()) {
            for (var command : List.of(NativeCommand.TRANSPOSE, NativeCommand.VOLUME, NativeCommand.TRANSPOSE_ADD)) {
                var bank = new ClassicBankEncoder(driver.profile.game(), Encoding.COMPACT, 1, 1, 255);
                bank.track(6).finish();
                var track = bank.track(10);
                for (int value = 0; value < 2; value++) {
                    track.state(command, value);
                    for (int pitch = 0x90; pitch < 0x96; pitch++) track.event(pitch, 2);
                }
                track.finish();
                assertTrue(controlOffsets(driver, bank.finish().bytes()).isEmpty(), command.name());
            }
            var bank = new ClassicBankEncoder(driver.profile.game(), Encoding.COMPACT, 1, 1, 255);
            bank.track(6).finish();
            var track = bank.track(10);
            // Four bytes only save space when the entire three-pass loop can be used.
            for (int i = 0; i < 3; i++) {
                track.event(0x90, 2);
                track.event(0x91, 2);
            }
            track.hold();
            track.event(-1, 2);
            track.finish();
            assertTrue(controlOffsets(driver, bank.finish().bytes()).isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(ClassicPackageDriver.class)
    void fullExampleAndSyntheticDacPlaybackAreByteExact(ClassicPackageDriver driver) throws Exception {
        for (String name : List.of("lantern-walk", "signal-garden", "native-controls")) {
            var source = ProjectFiles.load(Path.of("..", "examples", name + ".smps.json")).project();
            assertPlayback(source.withTarget(driver.profile.game()).withSettings(source.name(), source.ticksPerBeat(), 0)
                    .withNativeTempo(driver == ClassicPackageDriver.S1 ? 7 : 193));
        }
        var source = ClassicPortablePackageTest.project(NativeCommand.DETUNE, 60);
        var repeated = new StudioProject(4, source.name(), source.mode(), 24, 0, source.voices(),
                source.sequences(), source.clips().stream().map(clip ->
                new Clip(clip.id(), clip.sequenceId(), clip.lane(), 0, 120)).toList(),
                source.envelopes(), source.samples(), driver.profile.game(), 255);
        assertPlayback(repeated);
    }

    @ParameterizedTest
    @EnumSource(ClassicPackageDriver.class)
    void patchedRetailBankRendersLikeLinear(ClassicPackageDriver driver) throws Exception {
        String rom = System.getenv("OPENSMPSDECK_" + driver.name() + "_ROM");
        assumeTrue(rom != null && !rom.isBlank(), "Requires the corresponding clean retail ROM");
        var source = repeatedProject(driver);
        var archive = writePackage(source, driver);
        var original = PortablePackageReader.assemble(PortablePackageReader.read(archive), List.of());
        var linear = TargetPlayback.prepare(source, Encoding.LINEAR).compiled();
        Path output = directory.resolve("patched.gen");
        var result = TestInGame.export(new RomLibrary(directory.resolve("roms.json")), archive,
                Path.of(rom), new TestInGame.Outputs(output, null, false), null,
                new TestInGame.Options(null, false, false, false)).getAsJsonObject("report");
        boolean s1 = driver == ClassicPackageDriver.S1;
        int base = s1 ? 0 : result.get("songPointer").getAsInt();
        int offset = s1 ? result.getAsJsonObject("songRomRange").get("start").getAsInt()
                : result.get("bank").getAsInt() * 0x8000;
        byte[] patched = Files.readAllBytes(output);
        byte[] bank = Arrays.copyOfRange(patched, offset, offset + original.song().length);
        var pkg = PortablePackageReader.read(archive);
        for (int at : pkg.manifest().bank().relocations())
            assertEquals(driver.word(original.song(), at) + base, driver.word(bank, at));
        for (int at : controlOffsets(driver, original.song())) {
            int flag = original.song()[at] & 255;
            if (flag == 0xE3) continue;
            int operand = at + (flag == 0xF7 ? 3 : 1);
            assertEquals(ClassicPackageBank.branchTarget(driver, original.song(), 0, operand),
                    ClassicPackageBank.branchTarget(driver, bank, base, operand));
        }
        assertRenderIdentity(driver, linear, withBank(original, bank, base), 180);
    }

    private Path writePackage(StudioProject source, ClassicPackageDriver driver) throws Exception {
        Path path = directory.resolve(driver.id() + ".smps.zip");
        PortablePackageWriter.write(source, path, new PortableOptions(driver.id(), true, null, null, null,
                List.of(), List.of(), null));
        return path;
    }

    private static CompiledProject withBank(CompiledProject project, byte[] bytes, int base) {
        var program = project.nativeProgram();
        return new CompiledProject(bytes, project.envelopes(), project.samples(),
                NativeProgram.of(base, 0, program.localVoiceCount(), program.previewFrames(), bytes,
                        program.envelopeSlots(), program.dacSlots()), List.of());
    }

    private static void assertPlayback(StudioProject source) throws Exception {
        var driver = TargetPlayback.classic(source.target());
        var linear = TargetPlayback.prepare(source, Encoding.LINEAR).compiled();
        var compact = TargetPlayback.prepare(source).compiled();
        assertEquals(source.nativeTempo(), compact.song()[5] & 255);
        int frames = (int) source.clock().frameAtTick(GroupArrangement.endTick(source)) + 120;
        assertRenderIdentity(driver, linear, compact, frames);
    }

    private static void assertRenderIdentity(ClassicPackageDriver driver, CompiledProject left,
                                             CompiledProject right, int frames) throws Exception {
        var linear = new Observation(MessageDigest.getInstance("SHA-256"));
        var compact = new Observation(MessageDigest.getInstance("SHA-256"));
        driver.render(left, frames, linear::capture, linear);
        driver.render(right, frames, compact::capture, compact);
        assertArrayEquals(linear.pcm.digest(), compact.pcm.digest(), driver.id() + " PCM");
        assertEquals(linear.writes.size(), compact.writes.size(), driver.id() + " chip-write count");
        for (int i = 0; i < linear.writes.size(); i++)
            assertEquals(linear.writes.get(i), compact.writes.get(i), driver.id() + " chip write " + i);
    }

    private static List<Integer> controlOffsets(ClassicPackageDriver driver, byte[] song) {
        var offsets = new ArrayList<Integer>();
        ClassicPackageBank.walk(driver, song, 0, 0, java.util.Set.of(), java.util.Set.of(), (at, count, dac) -> {
            int flag = song[at] & 255;
            if (flag == 0xE3 || flag >= 0xF6 && flag <= 0xF8) offsets.add(at);
        });
        return offsets;
    }

    private static int assertSingle(List<Integer> offsets, byte[] song, int flag) {
        var sites = offsets.stream().filter(at -> (song[at] & 255) == flag).toList();
        assertEquals(1, sites.size(), "flag %02X".formatted(flag));
        return sites.getFirst();
    }

    private static void assertInvalid(ClassicPackageDriver driver, byte[] bytes, String reason) {
        var failure = assertThrows(IllegalArgumentException.class, () -> controlOffsets(driver, bytes));
        assertTrue(failure.getMessage().contains(reason), failure::getMessage);
    }

    private static void point(ClassicPackageDriver driver, byte[] bytes, int operand, int destination) {
        if (driver == ClassicPackageDriver.S1) {
            int relative = destination - operand - 1;
            bytes[operand] = (byte) (relative >>> 8);
            bytes[operand + 1] = (byte) relative;
        } else {
            bytes[operand] = (byte) destination;
            bytes[operand + 1] = (byte) (destination >>> 8);
        }
    }

    private record Write(int frame, int port, int register, int value) {}
    private static final class Observation implements ChipWriteObserver {
        final MessageDigest pcm;
        final List<Write> writes = new ArrayList<>();
        int frame;

        Observation(MessageDigest pcm) { this.pcm = pcm; }

        void capture(short[] samples) {
            var bytes = ByteBuffer.allocate(samples.length * 2);
            bytes.asShortBuffer().put(samples);
            pcm.update(bytes.array());
            frame++;
        }

        @Override public void onYm2612Write(int port, int register, int value) {
            writes.add(new Write(frame, port, register, value));
        }

        @Override public void onPsgWrite(int value) {
            writes.add(new Write(frame, -1, -1, value));
        }
    }

    private static StudioProject repeatedProject(ClassicPackageDriver driver) {
        var notes = new ArrayList<NoteEvent>();
        int[] phrase = {60, 62, 64, 65, 67, 69};
        notes.add(new NoteEvent("intro", 0, 2, 72));
        int tick = 2;
        for (int block = 0; block < 5; block++) {
            for (int pitch : phrase) {
                notes.add(new NoteEvent("note-" + tick, tick, 2, pitch));
                tick += 2;
            }
            if (block >= 2 && block < 4) {
                notes.add(new NoteEvent("note-" + tick, tick, 2, 75 + block));
                tick += 2;
            }
        }
        var sequence = new Sequence("psg", "Repeated phrase", "PSG", tick, null, 127, notes);
        return new StudioProject(4, "Classic repeats", "S3K", 24, 0, List.of(), List.of(sequence),
                List.of(new Clip("clip", "psg", "PSG1", 0, tick)), driver.profile.game(),
                driver == ClassicPackageDriver.S1 ? 7 : 193);
    }
}
