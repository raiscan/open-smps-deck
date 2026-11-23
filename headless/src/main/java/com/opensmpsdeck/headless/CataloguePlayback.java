package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.TargetGame;
import com.opensmpsdeck.project.TargetProfile;

import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import com.opensmpsdeck.headless.InstrumentCatalogue.Match;
import com.opensmpsdeck.headless.InstrumentCatalogue.Recipe;
import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NativeProgram;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.SampleTrigger;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.Voice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Converts catalogue entries to self-contained editable projects and reusable project assets. */
public final class CataloguePlayback {
    private static final int PROBE_LENGTH = 60;

    private CataloguePlayback() {}

    /** Plays a fixed phrase or one-shot, with the entry's available playback context. */
    public static StudioProject audition(InstrumentCatalogue catalogue, String id) {
        return audition(catalogue, id, null);
    }

    static StudioProject audition(InstrumentCatalogue catalogue, String id, TargetGame target) {
        Match match = catalogue.match(id, target);
        return renderProbe(catalogue, match, recipe(match), false, target == null ? match.variant().target() : target);
    }

    /**
     * Renders the requested single attack with the variant's onset settings and local assets.
     * Sample and noise entries retain their original audition and ignore the tonal arguments.
     */
    public static StudioProject matchProbe(InstrumentCatalogue catalogue, String id, int midi, int gateTicks) {
        return matchProbe(catalogue, id, midi, gateTicks, false);
    }

    static StudioProject matchProbe(InstrumentCatalogue catalogue, String id, int midi, int gateTicks, boolean rawAsset) {
        return matchProbe(catalogue, id, midi, gateTicks, rawAsset, null);
    }

    static StudioProject matchProbe(InstrumentCatalogue catalogue, String id, int midi, int gateTicks,
                                    boolean rawAsset, TargetGame target) {
        return matchProbe(catalogue, id, midi, gateTicks, rawAsset, target, 180, 127);
    }

    /** A raw-asset probe written at a track volume below full, as a quiet part would play it. */
    static StudioProject leveledProbe(InstrumentCatalogue catalogue, String id, int midi, int gateTicks,
                                      TargetGame target, int volume) {
        return matchProbe(catalogue, id, midi, gateTicks, true, target, 180, volume);
    }

    static StudioProject heldFmProbe(InstrumentCatalogue catalogue, String id, int midi, int gateTicks) {
        return matchProbe(catalogue, id, midi, gateTicks, true, TargetGame.S3K, S3kRenderer.MAX_PRESENTATIONS, 127);
    }

    private static StudioProject matchProbe(InstrumentCatalogue catalogue, String id, int midi, int gateTicks,
                                            boolean rawAsset, TargetGame target, int maxTicks, int volume) {
        Match match = catalogue.match(id, target);
        if (target == null) target = match.variant().target();
        Recipe source = recipe(match);
        if (rawAsset) source = new Recipe(source.rate(), null, volume, source.durationTicks(),
                null, null, List.of(), List.of());
        requirePlayable(match, source, rawAsset, target);
        if (match.asset().kind() == Kind.DAC_SAMPLE || match.asset().kind() == Kind.PSG_NOISE)
            return renderProbe(catalogue, match, source, rawAsset, target);
        if (gateTicks < 1 || gateTicks > maxTicks)
            throw new IllegalArgumentException("Matching note length must be 1–" + maxTicks + " ticks (was " + gateTicks + ").");
        String chip = probeChip(match, rawAsset);
        var profile = TargetProfile.of(target);
        var range = chip.equals("PSG") ? profile.psg() : profile.fm();
        int lowest = range.low();
        int highest = range.high();
        if (midi < lowest || midi > highest)
            throw new IllegalArgumentException(chip + " matching probe supports MIDI " + lowest + "–" + highest
                    + " (requested " + midi + ").");
        int onset = source.notes().stream().mapToInt(NoteEvent::tick).min().orElse(0);
        List<CommandEvent> setup = source.commands().stream()
                .filter(event -> event.tick() <= onset)
                .filter(event -> switch (event.command()) {
                    case HOLD, TRANSPOSE, TRANSPOSE_ADD -> false;
                    default -> true;
                })
                .sorted(Comparator.comparingInt(CommandEvent::tick))
                .map(event -> new CommandEvent(event.id(), 0, event.command(), event.arguments(), event.assetId()))
                .toList();
        var note = new NoteEvent("match-note", 0, gateTicks, midi);
        var controlled = new Recipe(source.rate(), source.pan(), source.volume(), gateTicks,
                source.volumeEnvelope(), source.modulationEnvelope(), List.of(note), setup);
        return renderProbe(catalogue, match, controlled, rawAsset, target);
    }

    private static StudioProject renderProbe(InstrumentCatalogue catalogue, Match match, Recipe recipe, boolean rawAsset, TargetGame target) {
        requirePlayable(match, recipe, rawAsset, target);
        var base = new StudioProject(4, name(match), "S3K", 15, 0, List.of(), List.of(), List.of(), target, null);
        var install = new Install(catalogue, base);
        var asset = match.asset();
        Sequence probe;
        String lane;
        switch (asset.kind()) {
            case DAC_SAMPLE -> {
                String sample = install.dac(asset, recipe.rate());
                probe = new Sequence("probe", "Sample hit", "DAC", PROBE_LENGTH, null, 127, List.of(),
                        List.of(), "RESET", null, null, List.of(new SampleTrigger("probe-hit", 0, sample)));
                lane = "DAC";
            }
            case PSG_NOISE -> {
                probe = install.tones(match, "probe", "PSG", null, recipe);
                lane = "PSG3";
            }
            case FM_VOICE, PSG_VOLUME, MODULATION -> {
                String chip = probeChip(match, rawAsset);
                String voice = null;
                if (asset.kind() == Kind.FM_VOICE) {
                    voice = install.voice(asset);
                } else if (chip.equals("FM")) {
                    var reference = DemoProject.create().voices().getFirst();
                    install.voices.add(reference);
                    voice = reference.id();
                }
                probe = install.tones(match, "probe", chip, voice, recipe);
                lane = chip.equals("FM") ? "FM1" : "PSG3";
            }
            default -> throw new IllegalArgumentException("Unsupported catalogue probe for " + asset.kind());
        }
        install.sequences.add(probe);
        install.clips.add(new Clip("probe-clip", probe.id(), lane, 0, probe.lengthTicks()));
        StudioProject result = install.finish();
        if (target != TargetGame.S3K) return result;
        byte[] linear = ProjectCompiler.compile(result, ProjectCompiler.Encoding.LINEAR);
        int frames = ProjectRenderTiming.presentationCount(result);
        if (asset.kind() == Kind.DAC_SAMPLE && recipe.pan() != null && recipe.pan() != 0xC0)
            return pannedDac(result, linear, recipe.pan(), frames);
        return result;
    }

    private static void requirePlayable(Match match, Recipe recipe, boolean rawAsset, TargetGame target) {
        if (target != TargetGame.S3K) {
            for (var event : recipe.commands()) {
                switch (event.command()) {
                    case FM_VOLUME_ENVELOPE, MODULATION_ENVELOPE, SSG_EG ->
                            throw unsupported(target, event.command().name());
                    default -> { }
                }
            }
            if (recipe.modulationEnvelope() != null || match.asset().kind() == Kind.MODULATION)
                throw unsupported(target, "MODULATION_ENVELOPE");
            if ((recipe.volumeEnvelope() != null || match.asset().kind() == Kind.PSG_VOLUME)
                    && probeChip(match, rawAsset).equals("FM"))
                throw unsupported(target, "FM_VOLUME_ENVELOPE");
        }
        if (match.variant().target() != target)
            throw new IllegalArgumentException("Catalogue variant is for " + match.variant().driver()
                    + "; choose a " + target.id() + " variant.");
    }

    private static IllegalArgumentException unsupported(TargetGame target, String command) {
        return new IllegalArgumentException(target.id() + ": " + command + " has no native equivalent");
    }

    private static StudioProject pannedDac(StudioProject probe, byte[] linear, int pan, int frames) {
        int stream = Byte.toUnsignedInt(linear[6]) | Byte.toUnsignedInt(linear[7]) << 8;
        byte[] bank = Arrays.copyOf(linear, linear.length + 2);
        System.arraycopy(linear, stream, bank, stream + 2, linear.length - stream);
        bank[stream] = (byte) 0xE0;
        bank[stream + 1] = (byte) pan;
        var binding = new NativeProgram.DacSlot(DacSample.FIRST_NOTE,
                probe.samples().getFirst().id(), probe.samples().getFirst().rate());
        var program = NativeProgram.of(0, 0, 0, frames, bank, List.of(), List.of(binding));
        return new StudioProject(5, probe.name(), "S3K", probe.ticksPerBeat(), probe.tempo(),
                List.of(), List.of(), List.of(), List.of(), probe.samples(), program, probe.target(), probe.nativeTempo());
    }

    /**
     * Copies the selected raw asset, preserving existing local assets and IDs. A PSG noise
     * recipe is a reusable source with its related assets and a clip after the arrangement.
     * FM audition settings describe the probe; the raw FM voice is the reusable asset.
     */
    public static StudioProject apply(InstrumentCatalogue catalogue, String id, StudioProject target) {
        ProjectFormat.validate(target);
        if (target.nativeProgram() != null)
            throw new IllegalArgumentException("Save an editable copy before applying catalogue instruments to a native bank.");
        var match = catalogue.match(id, target.target());
        // An occurrence-less legacy PSG entry can still be explicitly copied as a raw
        // envelope. The target compiler validates its byte grammar before deployment.
        boolean rawLegacyEnvelope = match.variant().target() == TargetGame.S3K
                && match.asset().kind() == Kind.PSG_VOLUME && match.variant().occurrences().isEmpty();
        if (!rawLegacyEnvelope) requirePlayable(match, recipe(match), false, target.target());
        var install = new Install(catalogue, target);
        var asset = match.asset();
        switch (asset.kind()) {
            case FM_VOICE -> install.voice(asset);
            case PSG_VOLUME, MODULATION -> install.envelope(asset, asset.kind());
            case DAC_SAMPLE -> install.dac(asset, recipe(match).rate());
            case PSG_NOISE -> install.noiseSource(match);
        }
        StudioProject result = install.finish();
        TargetPlayback.prepare(result);
        return result;
    }

    private static String probeChip(InstrumentCatalogue.Match match, boolean rawAsset) {
        if (rawAsset && match.asset().kind() == Kind.PSG_VOLUME) return "PSG";
        if (match.asset().kind() == Kind.FM_VOICE) return "FM";
        var recipe = recipe(match);
        if (recipe.pan() != null || recipe.commands().stream().anyMatch(event -> !event.command().supports("PSG")))
            return "FM";
        if (recipe.commands().stream().anyMatch(event -> !event.command().supports("FM")))
            return "PSG";
        for (var occurrence : match.variant().occurrences()) {
            String lane = occurrence.lane();
            if (lane != null && lane.matches("FM[1-6]")) return "FM";
            if (lane != null && lane.matches("PSG[1-3]")) return "PSG";
        }
        return match.asset().kind() == Kind.PSG_VOLUME ? "PSG" : "FM";
    }

    private static InstrumentCatalogue.Recipe recipe(InstrumentCatalogue.Match match) {
        return match.variant().recipe();
    }

    private static String name(InstrumentCatalogue.Match match) {
        return match.variant().name();
    }

    private static List<Integer> unsigned(byte[] bytes) {
        var result = new ArrayList<Integer>(bytes.length);
        for (byte raw : bytes) result.add(Byte.toUnsignedInt(raw));
        return result;
    }

    private static String freeId(String base, Predicate<String> taken) {
        String candidate = base;
        for (int suffix = 2; taken.test(candidate); suffix++) candidate = base + "-" + suffix;
        return candidate;
    }

    private static final class Install {
        private final InstrumentCatalogue catalogue;
        private final StudioProject target;
        private final List<Voice> voices;
        private final List<Envelope> envelopes;
        private final List<DacSample> samples;
        private final List<Sequence> sequences;
        private final List<Clip> clips;
        private int version;

        Install(InstrumentCatalogue catalogue, StudioProject target) {
            this.catalogue = catalogue;
            this.target = target;
            version = target.formatVersion();
            voices = new ArrayList<>(target.voices());
            envelopes = new ArrayList<>(target.envelopes());
            samples = new ArrayList<>(target.samples());
            sequences = new ArrayList<>(target.sequences());
            clips = new ArrayList<>(target.clips());
        }

        String voice(InstrumentCatalogue.Asset asset) {
            requireKind(asset, Kind.FM_VOICE);
            var bytes = unsigned(asset.bytes());
            var origin = catalogue.origin(asset, target.target());
            for (int i = 0; i < voices.size(); i++) {
                var existing = voices.get(i);
                if (!existing.bytes().equals(bytes) || existing.origin() != null && existing.origin().clonedFrom() != null) continue;
                if (existing.origin() == null && origin != null) voices.set(i, existing.withOrigin(origin));
                return existing.id();
            }
            String id = freeId(asset.id(), candidate -> voices.stream().anyMatch(voice -> voice.id().equals(candidate)));
            voices.add(new Voice(id, asset.name(), bytes, origin));
            return id;
        }

        String envelope(InstrumentCatalogue.Asset asset, Kind kind) {
            requireKind(asset, kind);
            String type = kind == Kind.PSG_VOLUME ? Envelope.PSG_VOLUME : Envelope.MODULATION;
            var bytes = unsigned(asset.bytes());
            var origin = catalogue.origin(asset, target.target());
            for (int i = 0; i < envelopes.size(); i++) {
                var existing = envelopes.get(i);
                if (!existing.type().equals(type) || !existing.bytes().equals(bytes)
                        || existing.origin() != null && existing.origin().clonedFrom() != null) continue;
                if (existing.origin() == null && origin != null) envelopes.set(i, existing.withOrigin(origin));
                return existing.id();
            }
            String id = freeId(asset.id(), candidate -> envelopes.stream().anyMatch(env -> env.id().equals(candidate)));
            version = 4;
            envelopes.add(new Envelope(id, asset.name(), type, bytes, origin));
            return id;
        }

        String dac(InstrumentCatalogue.Asset asset, int rate) {
            byte[] bytes = asset.bytes();
            var origin = catalogue.origin(asset, target.target());
            for (int i = 0; i < samples.size(); i++) {
                var existing = samples.get(i);
                if (existing.rate() != rate || !Arrays.equals(existing.pcm(), bytes)) continue;
                if (existing.origin() == null && origin != null) samples.set(i, existing.withOrigin(origin));
                return existing.id();
            }
            String id = freeId(asset.id(), candidate -> samples.stream().anyMatch(sample -> sample.id().equals(candidate)));
            version = 4;
            samples.add(DacSample.of(id, asset.name(), rate, bytes).withOrigin(origin));
            return id;
        }

        Sequence tones(Match match, String id, String type, String voice, Recipe recipe) {
            var asset = match.asset();
            int length = recipe.durationTicks();
            boolean noise = asset.kind() == Kind.PSG_NOISE;
            List<NoteEvent> notes = recipe.notes().isEmpty() ? phrase(length, noise) : recipe.notes();
            var commands = new ArrayList<CommandEvent>();
            if (noise) {
                byte[] data = asset.bytes();
                if (data.length != 1 || data[0] == 0)
                    throw new IllegalArgumentException("PSG noise asset needs one nonzero F3 operand.");
                if (!startsWith(recipe, NativeCommand.PSG_NOISE))
                    prepend(commands, recipe.commands(), "probe-noise", NativeCommand.PSG_NOISE, Byte.toUnsignedInt(data[0]));
            }
            if (recipe.pan() != null) {
                if (!type.equals("FM")) throw new IllegalArgumentException("Pan is only supported for FM audition.");
                if (!startsWith(recipe, NativeCommand.PAN))
                    prepend(commands, recipe.commands(), "probe-pan", NativeCommand.PAN, recipe.pan());
            }
            for (var event : recipe.commands()) {
                String localAsset = event.assetId() == null ? null : switch (event.command().asset()) {
                    case VOICE -> voice(catalogue.asset(event.assetId()));
                    case PSG_VOLUME -> envelope(catalogue.asset(event.assetId()), Kind.PSG_VOLUME);
                    case MODULATION -> envelope(catalogue.asset(event.assetId()), Kind.MODULATION);
                    case NONE -> throw new IllegalArgumentException("Command " + event.id() + " does not accept an asset.");
                };
                commands.add(new CommandEvent(event.id(), event.tick(), event.command(), event.arguments(), localAsset));
            }
            String volumeEnvelope = asset.kind() == Kind.PSG_VOLUME ? envelope(asset, Kind.PSG_VOLUME)
                    : relatedEnvelope(recipe.volumeEnvelope(), Kind.PSG_VOLUME);
            String modulationEnvelope = asset.kind() == Kind.MODULATION ? envelope(asset, Kind.MODULATION)
                    : relatedEnvelope(recipe.modulationEnvelope(), Kind.MODULATION);
            if (volumeEnvelope != null && type.equals("FM")) {
                if (!startsWith(recipe, NativeCommand.FM_VOLUME_ENVELOPE)) {
                    String commandId = freeId("probe-volume-envelope", candidate -> commands.stream()
                            .anyMatch(event -> event.id().equals(candidate)));
                    commands.addFirst(new CommandEvent(commandId, 0, NativeCommand.FM_VOLUME_ENVELOPE,
                            List.of(0x0F), volumeEnvelope));
                }
                volumeEnvelope = null;
            }
            return new Sequence(id, name(match), type, length, voice, recipe.volume(), notes, commands,
                    "RESET", volumeEnvelope, modulationEnvelope);
        }

        private String relatedEnvelope(String id, Kind kind) {
            return id == null ? null : envelope(catalogue.asset(id), kind);
        }

        void noiseSource(InstrumentCatalogue.Match match) {
            version = 4;
            String base = match.variant().id();
            String id = freeId(base, candidate -> sequences.stream().anyMatch(source -> source.id().equals(candidate)));
            Sequence source = tones(match, id, "PSG", null, recipe(match));
            if (sequences.stream().anyMatch(existing -> sameSound(existing, source))) return;
            sequences.add(source);
            // PSG noise writes affect shared hardware. Appending after all current clips
            // avoids changing the sound of any existing PSG parts.
            long end = clips.stream().mapToLong(clip -> (long) clip.startTick() + clip.lengthTicks()).max().orElse(0);
            if (end + source.lengthTicks() <= Integer.MAX_VALUE) {
                String clipId = freeId(id + "-clip", candidate -> clips.stream().anyMatch(clip -> clip.id().equals(candidate)));
                clips.add(new Clip(clipId, id, "PSG3", (int) end, source.lengthTicks()));
            }
        }

        StudioProject finish() {
            var result = new StudioProject(version, target.name(), target.mode(), target.ticksPerBeat(),
                    target.tempo(), voices, sequences, clips, envelopes, samples, target.target(), target.nativeTempo()).withDerivedFrom(target.derivedFrom());
            ProjectFormat.validate(result);
            return result.equals(target) ? target : result;
        }
    }

    private static void requireKind(InstrumentCatalogue.Asset asset, Kind kind) {
        if (asset.kind() != kind)
            throw new IllegalArgumentException("Catalogue asset '" + asset.id() + "' must be " + kind + ".");
    }

    private static List<NoteEvent> phrase(int length, boolean noise) {
        int first = Math.max(1, length / 3);
        if (length < 4) return List.of(new NoteEvent("probe-note", 0, length, 60));
        int second = Math.min(first + Math.max(1, length / 10), length - 1);
        return List.of(new NoteEvent("probe-first", 0, first, 60),
                new NoteEvent("probe-second", second, length - second, noise ? 72 : 67));
    }

    private static boolean startsWith(InstrumentCatalogue.Recipe recipe, NativeCommand command) {
        return recipe.commands().stream().anyMatch(event -> event.tick() == 0 && event.command() == command);
    }

    private static void prepend(List<CommandEvent> commands, List<CommandEvent> recipeCommands,
                                String id, NativeCommand command, int operand) {
        String unique = freeId(id, candidate -> recipeCommands.stream().anyMatch(event -> event.id().equals(candidate)));
        commands.add(new CommandEvent(unique, 0, command, List.of(operand)));
    }

    private static boolean sameSound(Sequence a, Sequence b) {
        return a.name().equals(b.name()) && a.type().equals(b.type()) && a.lengthTicks() == b.lengthTicks()
                && a.volume() == b.volume() && a.notes().equals(b.notes()) && a.commands().equals(b.commands())
                && a.repeatState().equals(b.repeatState())
                && Objects.equals(a.psgEnvelopeId(), b.psgEnvelopeId())
                && Objects.equals(a.modulationEnvelopeId(), b.modulationEnvelopeId());
    }
}
