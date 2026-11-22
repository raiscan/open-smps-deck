package com.opensmpsdeck.headless;

import com.openggf.audio.MusicRestoreSink;
import com.openggf.audio.session.SmpsDriverSession;
import com.openggf.audio.session.SmpsPhysicalPolicy;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.FmVoiceOperatorOrder;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.data.RomFingerprint;
import com.openggf.data.RomHeaderName;
import com.openggf.data.RomIdentity;
import com.openggf.data.RomIdentityTable;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.sonic1.audio.Sonic1SmpsCompatibilityPolicy;
import com.openggf.game.sonic1.audio.Sonic1SmpsSequencerConfig;
import com.openggf.game.sonic1.audio.smps.Sonic1SmpsData;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2SmpsCompatibilityPolicy;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsData;
import com.opensmpsdeck.project.AssetOrigin;
import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.GroupArrangement;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.TargetGame;
import com.opensmpsdeck.project.TargetProfile;
import com.opensmpsdeck.project.TargetClock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;

enum ClassicPackageDriver implements PackageDriver {
    S1(TargetGame.S1, Sonic1SmpsSequencerConfig.CONFIG, Sonic1SmpsCompatibilityPolicy.INSTANCE),
    S2(TargetGame.S2, Sonic2SmpsSequencerConfig.CONFIG, Sonic2SmpsCompatibilityPolicy.INSTANCE);

    private final String id;
    final TargetProfile profile;
    final SmpsSequencerConfig config;
    final SmpsPhysicalPolicy policy;

    ClassicPackageDriver(TargetGame target, SmpsSequencerConfig config, SmpsPhysicalPolicy policy) {
        this.id = target.id();
        this.profile = TargetProfile.of(target);
        this.config = config;
        this.policy = policy;
    }

    @Override public String id() { return id; }

    @Override public Set<Integer> musicIds() {
        return this == S1 ? Sonic1Music.titleMap().keySet() : Sonic2Music.titleMap().keySet();
    }

    @Override public void validateBase(int base) {
        require(base == 0 || this == S2 && base == 0x8000,
                this == S1 ? "bank.base must be 0" : "bank.base must be 0 or 32768");
    }

    @Override public int word(byte[] bytes, int offset) {
        return this == S1 ? ((bytes[offset] & 255) << 8) | (bytes[offset + 1] & 255)
                : PackageDriver.super.word(bytes, offset);
    }

    @Override public byte[] voiceBytes(byte[] source) {
        if (this == S2) FmVoiceOperatorOrder.swapMiddleOperatorsInPlace(source);
        return source;
    }

    /** Project patches use editable order; package patches use S2 bank order. */
    static AssetOrigin swapVoicePatchOffsets(AssetOrigin origin) {
        if (origin.transforms() == null || origin.transforms().isEmpty()) return origin;
        var converted = origin.transforms().stream().map(transform -> new AssetOrigin.Transform(
                "patch", null, null, null, transform.bytes().stream().map(patch -> {
                    int offset = patch.offset();
                    if (offset > 0 && offset < 25) {
                        int operator = (offset - 1) % 4;
                        if (operator == 1) offset++;
                        else if (operator == 2) offset--;
                    }
                    return new AssetOrigin.PatchByte(offset, patch.value());
                }).sorted(Comparator.comparingInt(AssetOrigin.PatchByte::offset)).toList())).toList();
        return new AssetOrigin(origin.type(), origin.ref(), converted, origin.license(), origin.attribution());
    }

    record TempoConversion(int targetByte, double relativeErrorPercent) {
        boolean exact() { return relativeErrorPercent == 0; }

        String warning(String field, int sourceByte, ClassicPackageDriver driver) {
            return String.format(Locale.ROOT,
                    "TEMPO_APPROXIMATED: %s S3K 0x%02X to %s 0x%02X (relative tick-rate error %+.6f%%)",
                    field, sourceByte, driver.name(), targetByte, relativeErrorPercent);
        }
    }

    TempoConversion projectTempo(StudioProject project) {
        if (project.nativeTempo() != null && project.target().id().equals(id()))
            return new TempoConversion(project.nativeTempo(), 0);
        return convertTempo(project.tempo());
    }

    /** The source clock is S3K's NTSC duration clock. See docs/architecture/driver-support.md. */
    TempoConversion convertTempo(int sourceByte) {
        require(sourceByte >= 0 && sourceByte <= 255, "S3K tempo must be in 0..255");
        int sourceTicks = 256 - sourceByte;
        if (this == S2) {
            int target = Math.min(255, sourceTicks);
            return new TempoConversion(target, 100.0 * (target - sourceTicks) / sourceTicks);
        }

        // The S1 rate increases monotonically with the countdown. The nearest
        // rate is at one of the two integers bracketing 256 / sourceByte.
        int lower = sourceByte == 0 ? 255 : Math.clamp(256 / sourceByte, 2, 255);
        int upper = Math.min(lower + 1, 255);
        int lowerError = sourceByte * lower - 256;
        int upperError = sourceByte * upper - 256;
        int target = Math.abs(lowerError) * upper <= Math.abs(upperError) * lower ? lower : upper;
        return new TempoConversion(target, 100.0 * (sourceByte * target - 256) / (target * sourceTicks));
    }

    @Override public ProjectCompiler.BankLayout compile(StudioProject project, int tempo) {
        return new ClassicArrangementCompiler(this, project, tempo).compile();
    }

    @Override public int previewFrames(StudioProject project, int tempo) {
        long frames = framesForTicks(GroupArrangement.endTick(project) + 2L, tempo);
        long sampleTail = project.samples().stream().mapToLong(this::dacTailFrames).max().orElse(0);
        long preview = frames + Math.max(60, sampleTail);
        require(preview <= S3kRenderer.MAX_PRESENTATIONS, "song exceeds the ten-minute preview limit");
        return (int) preview;
    }

    long dacTailFrames(DacSample sample) {
        int loops = sample.rate() == 0 ? 256 : sample.rate();
        long period = profile.dacBaseCycles() + 26L * (loops - 1);
        long cyclesPerByte = ceil(5 * period, 28) + 37;
        // At most one arrangement tick per presentation, including with speed shoes.
        return ceil((sample.pcm().length + 1L) * cyclesPerByte, 21334) + 4;
    }

    private long framesForTicks(long ticks, int tempo) {
        validateTempo(tempo);
        return new TargetClock(profile.game(), tempo).frameAtTick(ticks - 1) + 1;
    }

    void validateTempo(int tempo) {
        require(tempo >= (this == S1 ? 2 : 1), "tempo " + tempo + " cannot advance music durations");
    }

    private static long ceil(long numerator, int denominator) {
        return (numerator + denominator - 1) / denominator;
    }

    @Override public Image openRom(Path path) throws IOException {
        int size = this == S1 ? 0x80000 : 0x100000;
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(size + 1);
        }
        require(bytes.length == size, "expected a " + size + "-byte cartridge image");
        var rom = Rom.fromReader(RomByteReader.fromBytes(bytes), path.getFileName().toString());
        var header = RomHeaderName.of(rom.readDomesticName(), rom.readInternationalName());
        boolean known = RomIdentityTable.verify(this == S1 ? RomIdentity.S1 : RomIdentity.S2,
                RomFingerprint.of(bytes)).isPresent();
        boolean sega = bytes[0x100] == 'S' && bytes[0x101] == 'E' && bytes[0x102] == 'G' && bytes[0x103] == 'A';
        require(known || sega && header == (this == S1 ? RomHeaderName.S1 : RomHeaderName.S2),
                "cartridge header does not identify " + id);
        return new Image(new ClassicStockAssets(this == S1, rom, bytes), known);
    }

    @Override public void preflight(CompiledProject project) {
        ClassicPackageBank.validate(this, project);
    }

    AbstractSmpsData playbackData(CompiledProject project) {
        var psg = S3kRenderer.nativeEnvelopes(project.psgEnvelopes());
        int base = project.nativeProgram() == null ? 0 : project.nativeProgram().bankBase();
        if (this == S1) {
            var data = new Sonic1SmpsData(project.song());
            var slots = new byte[psg.keySet().stream().mapToInt(Integer::intValue).max().orElse(0)][];
            psg.forEach((index, bytes) -> slots[index - 1] = bytes);
            data.setPsgEnvelopes(slots);
            return data;
        }
        var data = new Sonic2SmpsData(project.song(), base);
        data.setPsgEnvelopes(psg);
        return data;
    }

    @Override public void render(CompiledProject project, Path output, int frames) throws IOException {
        try (var target = new ExportTarget(output, false)) {
            WaveFile.write(target.path(), frames, sink ->
                    render(project, frames, sink, ChipWriteObserver.NONE));
            target.publish();
        }
    }

    void render(CompiledProject project, int frames, S3kRenderer.PcmSink sink, ChipWriteObserver observer) throws IOException {
        render(project, frames, S3kRenderer.ChannelMask.NONE, sink, observer);
    }

    void render(CompiledProject project, int frames, S3kRenderer.ChannelMask mask,
                S3kRenderer.PcmSink sink, ChipWriteObserver observer) throws IOException {
        S3kRenderer.validatePresentations(frames);
        try (var session = openPlayback(project, mask, observer)) {
            S3kRenderer.renderFrames(session, frames, sink);
        }
    }

    SmpsDriverSession openPlayback(CompiledProject project, S3kRenderer.ChannelMask mask, ChipWriteObserver observer) {
        return S3kRenderer.openPackageMusic(id, playbackData(project), dac(project), config, policy, mask, observer);
    }

    SmpsSequencer timeline(CompiledProject project, MusicRestoreSink restore) {
        var data = playbackData(project);
        var sequencer = new SmpsSequencer(data, dac(project), restore, config);
        sequencer.setFallbackVoiceData(data);
        return sequencer;
    }

    private DacData dac(CompiledProject project) {
        return S3kRenderer.nativeDac(project.samples(), project.nativeProgram(), profile.dacBaseCycles());
    }

    void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(id + ": " + message);
    }
}
