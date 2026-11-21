package com.opensmpsdeck.headless;

import com.google.gson.GsonBuilder;
import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import com.opensmpsdeck.project.LoadedProject;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.StudioProject;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.opensmpsdeck.headless.S3kRenderer.PRESENTATIONS_PER_SECOND;

final class ReconstructionCli {
    private static final long MICROS_PER_SECOND = 1_000_000;
    private static final int REPORT_VERSION = 4;
    static final String STEADY_REFERENCE = "reference-steady.wav";
    private static final String REFERENCE_ID = "reconstruction-steady";
    private static final List<String> OUTPUTS = List.of("arrangement.smps.json", "arrangement.smps.zip",
            "render.wav", "report.json");

    record Options(Long durationUs, String name, int ticksPerBeat, boolean keepOctaves, boolean fmOnly, boolean wholePartVoices, boolean audioRefine,
                           Map<Integer, Path> stems) {}
    private record FileEvidence(String file, String sha256) {}
    private record PartEvidence(int id, String name, int program, boolean drums, int notesInRange) {}
    private record MatchEvidence(Kind kind, String lane, ReconstructionVoices.Key key, ReconstructionVoices.Choice choice) {}
    private record StageEvidence(int repeatGroups, long consensusCorrections, long harmonyMerges, long harmonyDrops,
                                 int linkedClips, int phraseGroups, int sections,
                                 Map<String, Long> leadContinuity,
                                 ReconstructionMelodyGapFill.Stats melodyGapFill,
                                 ReconstructionAllocation.Fm1Gaps fm1InterNoteGaps) {}
    private record Effects(Map<Integer, ReconstructionEcho.Estimate> echoes,
                           Map<Integer, ReconstructionVibrato.Estimate> vibrato,
                           Map<Integer, ReconstructionReverb.Estimate> reverb) {}
    record Matched(Map<String, Map<ReconstructionVoices.Key, ReconstructionVoices.Choice>> byLane,
                           List<MatchEvidence> evidence, List<ReconstructionVoices.SectionTrial> sectionTrials) {}
    private record Report(int version, String matcher, long referenceUs, int presentations, int bankBytes,
                          int sourceNotes, int fmNotes, int psgNotes, int dacHits, boolean keepOctaves, boolean fmOnly,
                          Map<String, FileEvidence> inputs, Map<String, FileEvidence> outputs,
                          List<PartEvidence> parts, List<MatchEvidence> matches, List<String> midiWarnings,
                          boolean wholePartVoices, List<ReconstructionVoices.SectionTrial> sectionTrials,
                          boolean audioRefine, int refinedNotes, List<ReconstructionPitchRefinement.Change> pitchChanges,
                          List<ReconstructionEcho.Estimate> echoEstimates, int echoNotes, boolean echoBankLimit,
                          Map<Integer, ReconstructionVibrato.Estimate> vibratoEstimates,
                          List<ReconstructionReverb.Estimate> reverbEstimates,
                          List<S3kReconstruction.Reduction> reductions, List<ReconstructionAudioCheck.Section> audio,
                          StageEvidence stages, List<String> effectCopyLanes, ReconstructionBeatMap.Summary tempoCurve,
                          Map<Integer, ExternalTranscribers.Evidence> externalTranscription,
                          List<ReconstructionStages.Note> fastRunEvidence,
                          List<ReconstructionAllocation.RunPlacement> fastRuns,
                          Map<String, List<ReconstructionFigureVoices.Voice>> figureVoices,
                          List<ReconstructionBendEncoding.Applied> bends, Boolean bendSizeLimit,
                          ReconstructionDrums.Source drumSource,
                          List<S3kReconstruction.VoicedGate> voicedGates,
                          Map<String, String> assemblyInputs, ReconstructionSustain.Probe sustainProbe,
                          List<String> voicingWarnings, Integer voiceAssignmentExtraClips, Integer arpeggioExtraNoteBytes) {}

    @FunctionalInterface
    private interface Matching<T> {
        T run() throws IOException;
    }

    @FunctionalInterface
    private interface Arrangement {
        S3kReconstruction.Result arrange(Map<Integer, ReconstructionEcho.Estimate> echoes, boolean copies, boolean bends) throws IOException;
    }

    private ReconstructionCli() {}

    static void usage(PrintStream out) {
        out.println("       reconstruct import FOLDER WORK_DIR [--assign FILE=PART ...] [--exclude FILE ...]");
        out.println("           [--catalogue INDEX.json] [--transcribe-unmapped NAME] [--run]");
        out.println("       reconstruct run|transcribe MIDI WAV INDEX.json WORK_DIR [options]");
        out.println("           [--candidates NOTES.json] [--harmony-max-hz RATE] [--lead-part PART] [--no-fast-runs]");
        out.println("           [--transcriber NAME --transcribe-part PART=ROLE ... --stem PART=WAV ...]");
        out.println("           [--transcribe-unmapped ROLE=WAV ...] [--transcriber-device DEVICE] [--transcriber-config FILE]");
        out.println("           [--replace-notes PART=FILE ...] [--add-notes PART=FILE ...]");
        out.println("           [--estimate-tempo | --tempo BPM [--bar-offset S]] [--audio-repeats]");
        out.println("           [--steady-tempo off|auto|BPM] [--drum-source catalogue|sliced|auto]");
        out.println("       reconstruct time-map WORK_DIR --source S | --project S");
        out.println("       reconstruct structure|roles|allocate|assemble WORK_DIR");
        out.println("       reconstruct voices WORK_DIR [list | create|rename|merge|split|move|assign JSON]");
        out.println("       reconstruct voice WORK_DIR [--lane LANE ...] [--refit-levels] [--variant ID]");
        out.println("       reconstruct describe WORK_DIR [--json] [--section ID] [--parts IDS] [--bars FROM-TO] [--out FILE]");
        out.println("       reconstruct edit WORK_DIR add [--dry-run] JSON | add [--dry-run] --file BATCH | list | remove ID");
        out.println("       reconstruct edit propose WORK_DIR [--dry-run] JSON | [--dry-run] --file BATCH");
        out.println("       reconstruct proposals list|accept|reject WORK_DIR [ID | --group GROUP]");
        out.println("       reconstruct run WORK_DIR --with-proposals [--id ID | --group GROUP]");
        out.println("       reconstruct trace WORK_DIR [--by-onset] [--start S] [--end S] [--time source|project] [--part N] [--lane LANE]");
        out.println("           [--pitch P | --pitch-min P --pitch-max P] [--status STATUS] [--json]");
        out.println("       reconstruct trace-diff OLD_WORK_DIR NEW_WORK_DIR [--span START,END] [--json]");
        out.println("       reconstruct rerun WORK_DIR NEW_WORK_DIR [--search DIR ...] [--keep-voicing] [--overwrite-detached]");
        out.println("       reconstruct plan-template WORK_DIR [--out FILE]");
        out.println("       reconstruct sections WORK_DIR | sections add|set|remove|rename WORK_DIR JSON");
        out.println("       reconstruct view WORK_DIR (--start S --end S | --section NAME) [--sections] [--color-by voice] [options] OUT.png");
        out.println("           [--time source|project] [--notes FILE] [--harmonics-at PITCH[@TIME] ...] [--ridges] [--ridge-count N] [--shared-db|--db] [--pitch-preset drums] [--force]");
        out.println("       reconstruct audition WORK_DIR (--lane FM1 --start S --end S [--time source|project] | --note ID) (--variant ID ... | --shortlist)");
        out.println("           [--gain MULTIPLIER] [--solo|--in-mix] [--reference WAV] [--rms-json FILE] OUT.wav");
        out.println("       reconstruct run --from STAGE WORK_DIR [--harmony-max-hz RATE] [--keep-voicing] [--overwrite-detached]");
        out.println("       java -jar opensmpsdeck-headless-0.1.0-SNAPSHOT.jar reconstruct MIDI WAV INDEX.json OUTPUT_DIR");
        out.println("           [--seconds N] [--name NAME] [--ticks-per-beat N] [--keep-octaves] [--fm-only] [--whole-part-voices] [--audio-refine] [--stem PART=WAV]...");
    }

    static void run(String[] args, PrintStream out) throws IOException {
        run(args, out, System.err);
    }

    static void run(String[] args, PrintStream out, PrintStream err) throws IOException {
        if (args.length == 2 && args[1].equals("--help")) {
            usage(out);
            return;
        }
        if (args.length >= 2 && args[1].equals("voices")) {
            ReconstructionVoicePlan.cli(java.util.Arrays.copyOfRange(args, 2, args.length), out);
            return;
        }
        if (args.length >= 2 && args[1].equals("sections")) {
            ReconstructionSections.cli(java.util.Arrays.copyOfRange(args, 2, args.length), out);
            return;
        }
        if (args.length >= 2 && args[1].equals("import")) {
            SunoImport.cli(java.util.Arrays.copyOfRange(args, 2, args.length), out);
            return;
        }
        if (args.length >= 2 && args[1].equals("view")) {
            ReconstructionView.cli(java.util.Arrays.copyOfRange(args, 2, args.length), out);
            return;
        }
        if (args.length >= 2 && args[1].equals("proposals")) {
            ReconstructionProposals.cli(java.util.Arrays.copyOfRange(args, 2, args.length), out);
            return;
        }
        if (args.length >= 2 && args[1].equals("run") && List.of(args).contains("--with-proposals")) {
            ReconstructionReview.previewCli(java.util.Arrays.copyOfRange(args, 2, args.length), out);
            return;
        }
        if (args.length >= 2 && args[1].equals("edit")) {
            ReconstructionEditCommand.cli(java.util.Arrays.copyOfRange(args, 2, args.length), out);
            return;
        }
        if (args.length >= 2 && args[1].equals("audition")) {
            ReconstructionAudition.cli(java.util.Arrays.copyOfRange(args, 2, args.length), out);
            return;
        }
        if (args.length >= 2 && List.of("trace", "trace-diff").contains(args[1])) {
            ReconstructionTrace.run(args[1], java.util.Arrays.copyOfRange(args, 2, args.length), out);
            return;
        }
        if (args.length >= 2 && args[1].equals("rerun")) {
            ReconstructionManifest.run(java.util.Arrays.copyOfRange(args, 2, args.length), out, err);
            return;
        }
        if (args.length >= 2 && args[1].equals("describe")) {
            ReconstructionDescribe.run(java.util.Arrays.copyOfRange(args, 2, args.length), out);
            return;
        }
        if (args.length >= 2 && args[1].equals("time-map")) {
            if (args.length != 5 || !List.of("--source", "--project").contains(args[3]))
                throw new IllegalArgumentException("reconstruct time-map expects WORK_DIR --source S | --project S");
            var score = ReconstructionStages.readScore(Path.of(args[2]).resolve("score.json"));
            var timeline = ReconstructionTimeMap.of(score);
            double input = Double.parseDouble(args[4]);
            boolean source = args[3].equals("--source");
            double limit = source ? timeline.sourceDuration() : timeline.project(timeline.sourceDuration());
            if (!(input >= 0 && input <= limit))
                throw new IllegalArgumentException("Time must be within the requested score interval");
            out.println(source ? timeline.project(input) : timeline.source(input));
            return;
        }
        if (args.length >= 2 && args[1].equals("plan-template")) {
            if (args.length == 3) out.print(ReconstructionStages.planTemplateJson(Path.of(args[2])));
            else if (args.length == 5 && args[3].equals("--out"))
                out.println(ReconstructionStages.writePlanTemplate(Path.of(args[2]), Path.of(args[4])));
            else throw new IllegalArgumentException("reconstruct plan-template expects WORK_DIR [--out FILE]");
            return;
        }
        if (args.length >= 2 && ReconstructionStages.isCommand(args[1])) {
            ReconstructionStages.run(args[1], java.util.Arrays.copyOfRange(args, 2, args.length), out, err);
            return;
        }
        if (args.length < 5) throw new IllegalArgumentException("reconstruct expects MIDI WAV INDEX.json OUTPUT_DIR");
        Path midi = Path.of(args[1]), reference = Path.of(args[2]), index = Path.of(args[3]);
        Path target = Path.of(args[4]).toAbsolutePath().normalize();
        if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
        Options options = parseOptions(args, midi.getFileName().toString());
        var transcription = ReconstructionStages.transcribe(midi, reference, options);
        assembleLegacy(midi, reference, index, target, options, transcription, out);
    }

    private static void assembleLegacy(Path midi, Path reference, Path index, Path target,
                                       Options options, ReconstructionStages.Score transcription,
                                       PrintStream out) throws IOException {
        long durationUs = transcription.durationUs();
        int frames = transcription.frames();
        MidiScore score = transcription.source();
        var prepared = new S3kReconstruction.Score(score.parts(),
                transcription.notes().stream().map(ReconstructionStages.Note::midi).toList(), transcription.reductions());
        if (prepared.notes().isEmpty()) throw new IllegalArgumentException("No playable MIDI notes in the requested interval");
        InstrumentCatalogue catalogue = InstrumentCatalogue.open(index);
        var choices = select(catalogue, reference, options, score, prepared, durationUs, Kind.FM_VOICE);
        var spill = options.fmOnly() ? Map.<ReconstructionVoices.Key, ReconstructionVoices.Choice>of()
                : select(catalogue, reference, options, score, prepared, durationUs, Kind.PSG_VOLUME);
        var sectionPlan = options.wholePartVoices() ? new ReconstructionVoices.SectionPlan(choices, List.of())
                : matchQuietly(() -> ReconstructionVoices.refineSections(catalogue, reference, options.stems(),
                        score, prepared.notes(), durationUs, choices));
        var evidence = new ArrayList<MatchEvidence>();
        recordMatches(evidence, null, Kind.FM_VOICE, sectionPlan.choices());
        recordMatches(evidence, null, Kind.PSG_VOLUME, spill);
        var matched = new Matched(Map.of(), evidence, sectionPlan.trials());
        var effects = effects(score, reference, options, durationUs);
        Files.createDirectory(target);
        render(midi, reference, index, target, target, options, transcription, matched, effects, null, null, null, null, null, List.of(),
                (echoes, copies, bends) -> S3kReconstruction.arrange(options.name(), options.ticksPerBeat(), frames, prepared,
                        catalogue, sectionPlan.choices(), spill, echoes), out, null, null);
    }

    static ReconstructionVoicing.Result fitVoicing(ReconstructionStages.Score score,
            ReconstructionAllocation.Result allocation, ReconstructionVoicing.Result previous,
            java.util.Set<String> targets, boolean refitLevels, ReconstructionAllocation.Result previousAllocation, String variant)
            throws IOException {
        var catalogue = InstrumentCatalogue.open(Path.of(score.inputs().catalogue()));
        var sourcePlacement = ReconstructionAllocation.sourcePlacement(allocation, score.frames(), ReconstructionTimeMap.of(score));
        var allocated = ReconstructionVoicing.byLane(allocation);
        for (String target : targets)
            if (!allocated.containsKey(target)) throw new IllegalArgumentException("Refit lane is not allocated: " + target);
        if (!targets.isEmpty() && previous == null && !targets.equals(allocated.keySet()))
            throw new IllegalArgumentException("A lane-targeted refit needs existing voicing for the other lanes");
        InstrumentCatalogue.Variant chosen = null;
        if (variant != null) {
            var match = catalogue.match(variant);
            if (match.asset().kind() != InstrumentCatalogue.Kind.FM_VOICE)
                throw new IllegalArgumentException("--variant requires an FM voice");
            chosen = match.variant();
        }
        var selected = chosen;
        var fitted = matchQuietly(() -> ReconstructionVoiceStage.fit(score, catalogue, sourcePlacement, previous, targets, selected));
        var result = previous == null ? fitted : ReconstructionVoicing.preserveRefit(fitted, previous, targets, refitLevels,
                ReconstructionVoicing.changedMaterial(previousAllocation, allocation), allocated);
        if (previous != null)
            ReconstructionVoicing.validate(result, allocation, catalogue, ReconstructionTimeMap.of(score).frames());
        String warning = matchQuietly(() -> ReconstructionSustain.check(score, catalogue, allocated.get("FM1"),
                result.representative("FM1", allocated.get("FM1"))));
        return new ReconstructionVoicing.Result(result.version(), result.lanes(), result.fitWindows(), warning,
                result.voiceAssignments(), result.sourceInstruments());
    }

    static ReconstructionVoicing.Result keepVoicing(ReconstructionStages.Score score,
            ReconstructionAllocation.Result allocation, ReconstructionVoicing.Result previous,
            java.util.function.Consumer<String> report) throws IOException {
        var catalogue = InstrumentCatalogue.open(Path.of(score.inputs().catalogue()));
        var timeline = ReconstructionTimeMap.of(score);
        var allocated = ReconstructionVoicing.byLane(allocation);
        var targets = ReconstructionVoicing.lanesNeedingFit(previous, allocated);
        ReconstructionVoicing.Result fitted = null;
        boolean needsSourceFit = previous.sourceInstruments() != null && allocated.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("DAC"))
                .flatMap(entry -> entry.getValue().stream())
                .filter(entry -> entry.role() != ReconstructionRoles.Role.drums)
                .anyMatch(entry -> {
                    var sound = previous.sourceInstruments().get(ReconstructionVoicePlan.SourceInstrument.id(entry.note().part()));
                    return sound == null;
                });
        if (!targets.isEmpty() || needsSourceFit) {
            var source = ReconstructionAllocation.sourcePlacement(allocation, score.frames(), timeline);
            fitted = matchQuietly(() -> ReconstructionVoiceStage.fit(score, catalogue, source, previous, previous.sourceInstruments() == null ? targets : java.util.Set.of(), null));
        }
        var result = ReconstructionVoicing.keepCompatible(previous, fitted, allocated, report);
        if (result.sourceInstruments() != null) {
            var instruments = new LinkedHashMap<>(result.sourceInstruments());
            var spilled = allocated.entrySet().stream().filter(e -> e.getKey().startsWith("PSG"))
                    .flatMap(e -> e.getValue().stream()).filter(n -> n.role() != ReconstructionRoles.Role.drums)
                    .collect(java.util.stream.Collectors.groupingBy(n -> n.note().part()));
            for (var part : score.source().parts()) {
                String id = ReconstructionVoicePlan.SourceInstrument.id(part.id());
                var sound = instruments.get(id);
                if (sound == null || sound.psgVariantId() != null || !spilled.containsKey(part.id())) continue;
                var match = ReconstructionSpill.match(catalogue, sound.fm(), part, spilled.get(part.id()));
                instruments.put(id, new ReconstructionVoicing.SourceSound(sound.fm(), match.variantId(), match.volumeOffset()));
            }
            result = result.withSourceInstruments(instruments);
        }
        result = ReconstructionVoicing.withCymbalGains(result,
                ReconstructionCymbals.gains(score, allocated.getOrDefault("PSG3", List.of())));
        ReconstructionVoicing.validate(result, allocation, catalogue, timeline.frames());
        return result;
    }

    static void assembleAllocated(Path work, ReconstructionStages.Score score, ReconstructionStructure.Result structure,
                                  ReconstructionAllocation.Result allocation, ReconstructionVoicing.Result voicing,
                                  PrintStream out, boolean preserveReview, boolean overwriteDetached, ReconstructionEdits intents,
                                  List<ReconstructionEdits.Outcome> outcomes) throws IOException {
        Path destination = work.resolve(OUTPUTS.getFirst());
        var existing = ReconstructionReview.requireAttached(work, overwriteDetached);
        var inputs = score.inputs();
        Path midi = Path.of(inputs.midi()), reference = Path.of(inputs.wav()), index = Path.of(inputs.catalogue());
        var options = inputs.options(score.durationUs());
        var timeline = ReconstructionTimeMap.of(score);
        var catalogue = InstrumentCatalogue.open(index);
        var ordered = new ReconstructionAllocation.Result(allocation.version(), allocation.notes().stream()
                .sorted(Comparator.comparingInt(entry -> entry.startTick() == null
                        ? entry.note().startTick(timeline.frames()) : entry.startTick())).toList());
        var scopes = ReconstructionVoicing.validate(voicing, ordered, catalogue, timeline.frames());
        var voiceAssignments = voicing.voiceAssignments() == null
                ? Map.<String, ReconstructionVoicePlan.Assignment>of() : voicing.voiceAssignments();
        var authored = ReconstructionNoteVoicing.resolve(intents.intents(), ordered, catalogue, voiceAssignments, scopes, outcomes);
        var drumGains = ReconstructionVoicing.scopedDrumGains(voicing.lanes().get("DAC"), allocation, timeline);
        var matched = voicedMatches(catalogue, ordered, voicing);
        var effects = new Effects(Map.of(), Map.of(), Map.of());
        var instruments = voicing.sourceInstruments();
        var copyLanes = java.util.stream.Stream.concat(voicing.lanes().values().stream(),
                        instruments == null ? java.util.stream.Stream.empty()
                                : instruments.values().stream().map(ReconstructionVoicing.SourceSound::fm))
                .map(lane -> lane.effects().copy())
                .filter(java.util.Objects::nonNull).map(ReconstructionVoicing.Copy::lane).distinct().sorted().toList();
        var leadNotes = ordered.notes().stream().filter(entry -> "FM1".equals(entry.lane())).toList();
        var leadVoice = voicing.representative("FM1", leadNotes);
        var playback = ReconstructionRetriggers.articulate(ordered.notes(), score.retriggers(), timeline, voicing::articulation);
        var articulated = new ReconstructionAllocation.Result(ordered.version(), playback.notes(), ordered.timeBase(),
                null, null, null, null, allocation.chordVoicings());
        var placements = new java.util.ArrayList<ReconstructionAllocation.RunPlacement>();
        if (allocation.fastRuns() != null) placements.addAll(allocation.fastRuns());
        placements.addAll(playback.placements());
        var assemblyInputs = assemblyInputs(score, allocation, voicing);
        var layout = ReconstructionClipLinks.Layout.read(work);
        var layoutPath = work.resolve(ReconstructionClipLinks.LAYOUT);
        var sustain = matchQuietly(() -> ReconstructionSustain.inspect(score, catalogue, leadNotes, leadVoice));
        var staging = Files.createTempDirectory(work, ".assemble-");
        try {
            render(midi, reference, index, staging, work, options, score, matched, effects, structure, allocation.leadContinuity(),
                    allocation.melodyGapFill(), allocation.fm1InterNoteGaps(), placements.isEmpty() ? null : placements, copyLanes,
                    (echoes, copies, bends) -> S3kReconstruction.arrangeAllocated(options.name(), options.ticksPerBeat(), timeline.frames(),
                            new S3kReconstruction.Score(score.source().parts(), List.of(), score.reductions()), articulated,
                            structure, catalogue, matched.byLane(), echoes, voicing.lanes(), copies, bends,
                            authored.scopes(), playback.origins(), drumGains, layout, authored.instruments(), instruments),
                    out, preserveReview ? existing : null, assemblyInputs);
            var edits = new ArrayList<>(ReconstructionEdits.Journal.read(work).all());
            edits.addAll(outcomes);
            Path reportFile = staging.resolve("report.json");
            var report = ReconstructionArtifacts.readTree(reportFile).getAsJsonObject();
            var json = new com.google.gson.Gson();
            var losses = json.fromJson(report.getAsJsonArray("reductions"), S3kReconstruction.Reduction[].class);
            report.add("chordAudit", json.toJsonTree(ReconstructionChordReduction.audit(score, allocation,
                    ReconstructionSections.load(work, score, structure), List.of(losses))));
            if (!edits.isEmpty()) report.add("editResults", json.toJsonTree(edits));
            if (!voiceAssignments.isEmpty()) {
                var applied = new java.util.TreeMap<String, Long>();
                allocation.notes().stream().filter(entry -> voiceAssignments.containsKey(entry.note().id()))
                        .forEach(entry -> applied.merge(entry.lane(), 1L, Long::sum));
                report.add("voiceAssignmentsByLane", json.toJsonTree(applied));
                if (!report.has("voiceAssignmentExtraClips")) report.addProperty("voiceAssignmentExtraClips", 0);
            }
            var plan = ReconstructionVoicePlan.saved(work);
            if (plan != null && !plan.issues().isEmpty())
                report.add("voiceEditIssues", json.toJsonTree(plan.issues()));
            if (instruments != null) {
                var project = ProjectFiles.load(staging.resolve(OUTPUTS.getFirst())).project();
                report.addProperty("fmPatchSwitchBytes", fmPatchSwitchBytes(project));
                report.addProperty("fmPatchBankBytes", project.voices().size() * 25);
            }
            if (!allocation.chords().isEmpty()) report.add("chordVoicings", json.toJsonTree(allocation.chords()));
            report.add("sustainProbe", json.toJsonTree(sustain));
            report.add("voicingWarnings", json.toJsonTree(sustain.warning() == null ? List.of() : List.of(sustain.warning())));
            var affinity = ReconstructionAllocation.Affinity.read(work);
            if (affinity != null && affinity.allocationHash().equals(assemblyInputs.get("allocation")))
                report.add("allocationAffinityChanges", json.toJsonTree(affinity.changes()));
            ReconstructionArtifacts.write(reportFile, report);
            var savedProject = ProjectFiles.load(staging.resolve(OUTPUTS.getFirst())).project();
            ProjectFiles.save(destination, savedProject, existing == null ? null : existing.revision());
            if (timeline.warped()) Files.move(staging.resolve(STEADY_REFERENCE), work.resolve(STEADY_REFERENCE),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            for (String name : OUTPUTS.subList(1, OUTPUTS.size())) {
                Files.move(staging.resolve(name), work.resolve(name),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!Files.exists(layoutPath))
                ReconstructionArtifacts.write(layoutPath, layout == null ? ReconstructionClipLinks.Layout.of(savedProject.clips()) : layout);
        } finally {
            Files.deleteIfExists(staging.resolve(STEADY_REFERENCE));
            for (String name : OUTPUTS) Files.deleteIfExists(staging.resolve(name));
            Files.deleteIfExists(staging);
        }
    }

    static Map<String, String> assemblyInputs(ReconstructionStages.Score score,
                                              ReconstructionAllocation.Result allocation,
                                              ReconstructionVoicing.Result voicing) {
        return Map.of("score", ReconstructionArtifacts.fingerprint(score),
                "allocation", ReconstructionArtifacts.fingerprint(allocation),
                "voicing", ReconstructionArtifacts.fingerprint(
                        new ReconstructionVoicing.Result(voicing.version(), voicing.lanes(), voicing.fitWindows(), null,
                                voicing.voiceAssignments(), voicing.sourceInstruments())));
    }

    private static Effects effects(MidiScore score, Path reference, Options options, long durationUs) throws IOException {
        if (!options.audioRefine()) return new Effects(Map.of(), Map.of(), Map.of());
        return new Effects(ReconstructionEcho.detect(score, reference, options.stems(), durationUs),
                ReconstructionVibrato.detect(score, reference, options.stems(), durationUs),
                ReconstructionReverb.detect(score, reference, options.stems(), durationUs));
    }

    static Matched voicedMatches(InstrumentCatalogue catalogue, ReconstructionAllocation.Result allocation,
                                         ReconstructionVoicing.Result voicing) {
        var result = new LinkedHashMap<String, Map<ReconstructionVoices.Key, ReconstructionVoices.Choice>>();
        var evidence = new ArrayList<MatchEvidence>();
        ReconstructionVoicing.byLane(allocation).forEach((id, notes) -> {
            var lane = voicing.lanes().get(id);
            var choices = ReconstructionVoiceStage.choices(catalogue, lane, notes);
            result.put(id, choices);
            var kind = id.startsWith("PSG") ? Kind.PSG_VOLUME : Kind.FM_VOICE;
            var laneChoices = new LinkedHashMap<>(choices);
            for (var entry : notes) {
                int part = entry.note().part();
                var sound = entry.role() == ReconstructionRoles.Role.drums ? null : voicing.sourceSound(id, part);
                if (sound == null) continue;
                var key = new ReconstructionVoices.Key(part, null);
                if (choices.containsKey(key)) continue;
                boolean fm = kind == Kind.FM_VOICE;
                var profile = sound.fm();
                var choice = ReconstructionVoiceStage.choice(catalogue, fm ? profile.variantId() : sound.psgVariantId(),
                        null, profile.patchReason(), fm ? profile.shortlist() : List.of());
                choices.put(key, choice);
                if (fm) {
                    var asset = catalogue.asset(choice.assetId());
                    var articulation = sound.settings(lane, true).articulation();
                    String emitted = ReconstructionVoicing.patchId(asset, ReconstructionVoicing.patchBytes(asset, articulation));
                    evidence.add(new MatchEvidence(kind, id, key, new ReconstructionVoices.Choice(emitted,
                            choice.variantId(), choice.name(), null, choice.status(), choice.probes(), choice.shortlist())));
                } else {
                    evidence.add(new MatchEvidence(kind, id, key, choice.report()));
                }
            }
            if (lane.variantId() != null && lane.articulation().minimumReleaseRate() > 0) {
                var asset = catalogue.match(lane.variantId()).asset();
                String emitted = ReconstructionVoicing.patchId(asset, ReconstructionVoicing.patchBytes(asset, lane.articulation()));
                choices.forEach((key, choice) -> evidence.add(new MatchEvidence(kind, id, key,
                        new ReconstructionVoices.Choice(emitted, choice.variantId(), choice.name(), null,
                                choice.status(), choice.probes(), choice.shortlist()))));
            } else {
                recordMatches(evidence, id, kind, laneChoices);
            }
        });
        return new Matched(result, evidence, List.of());
    }

    static int fmPatchSwitchBytes(StudioProject project) {
        var sources = project.sequences().stream().collect(java.util.stream.Collectors.toMap(
                com.opensmpsdeck.project.Sequence::id, java.util.function.Function.identity()));
        int bytes = 0;
        for (var clip : project.clips()) {
            if (!clip.lane().startsWith("FM")) continue;
            var sequence = sources.get(clip.sequenceId());
            for (var command : sequence.commands()) {
                if (command.command() == com.opensmpsdeck.project.NativeCommand.FM_VOICE
                        && command.tick() < clip.lengthTicks())
                    bytes += 2 * (1 + (clip.lengthTicks() - 1 - command.tick()) / sequence.lengthTicks());
            }
        }
        return bytes;
    }

    private static void recordMatches(List<MatchEvidence> evidence, String lane, Kind kind,
                                      Map<ReconstructionVoices.Key, ReconstructionVoices.Choice> choices) {
        choices.forEach((key, choice) -> evidence.add(new MatchEvidence(
                key.drumPitch() == null ? kind : Kind.DAC_SAMPLE, lane, key, choice.report())));
    }

    private static void render(Path midi, Path reference, Path index, Path target, Path destination,
                               Options options, ReconstructionStages.Score transcription, Matched matched,
                               Effects effects, ReconstructionStructure.Result structure, ReconstructionLeadContinuity.Stats leadContinuity,
                               ReconstructionMelodyGapFill.Stats melodyGapFill, ReconstructionAllocation.Fm1Gaps fm1InterNoteGaps,
                               List<ReconstructionAllocation.RunPlacement> fastRuns, List<String> copyLanes,
                               Arrangement arrangement, PrintStream out, LoadedProject existing, Map<String, String> assemblyInputs) throws IOException {
        long durationUs = transcription.durationUs();
        var timeline = ReconstructionTimeMap.of(transcription);
        int frames = timeline.frames();
        MidiScore score = transcription.source();
        boolean complete = false;
        try {
            boolean echoBankLimit = false, bendSizeLimit = false;
            S3kReconstruction.Result result;
            com.opensmpsdeck.project.CompiledProject compiled;
            Path source = target.resolve(OUTPUTS.get(0));
            Path packagePath = target.resolve(OUTPUTS.get(1));
            Path audio = target.resolve(OUTPUTS.get(2));
            Path aligned = timeline.warped() ? target.resolve(STEADY_REFERENCE) : reference;
            while (true) {
                result = arrangement.arrange(echoBankLimit ? Map.of() : effects.echoes(), !echoBankLimit, !bendSizeLimit);
                var project = result.project();
                if (existing != null) project = project.withReview(existing.project().references(), existing.project().notes());
                if (timeline.warped() || existing != null) {
                    var references = new ArrayList<>(project.references().stream()
                            .filter(r -> !r.id().equals(REFERENCE_ID)).toList());
                    if (timeline.warped()) references.add(new com.opensmpsdeck.project.AudioReference(
                            REFERENCE_ID, "Steady reference", STEADY_REFERENCE, 0, 0, "reference"));
                    project = project.withReview(references, project.notes());
                }
                try {
                    compiled = ProjectCompiler.compileWithAssets(result.project());
                } catch (IllegalArgumentException failure) {
                    if (!failure.getMessage().contains("32 KiB S3K bank")) throw failure;
                    if (!result.bends().isEmpty()) bendSizeLimit = true;
                    else if (result.echoNotes() > 0) echoBankLimit = true;
                    else if (result.arpeggioExtraNoteBytes() != null && result.arpeggioExtraNoteBytes() > 0)
                        throw new IllegalArgumentException(failure.getMessage() + " (arpeggios add "
                                + result.arpeggioExtraNoteBytes() + " note bytes; use longer steps or a smaller scope)", failure);
                    else throw failure;
                    continue;
                }
                try {
                    ProjectFiles.save(source, project, null);
                } catch (IOException failure) {
                    if (result.bends().isEmpty() || !failure.getMessage().endsWith("byte source limit")) throw failure;
                    bendSizeLimit = true;
                    continue;
                }
                break;
            }
            if (timeline.warped()) ReferenceStretch.write(reference, aligned, frames, timeline.sourceDuration(), timeline::source);
            ProjectPackage.write(result.project(), packagePath);
            WaveFile.render(compiled, audio, frames);
            var inputEvidence = new LinkedHashMap<String, FileEvidence>();
            inputEvidence.put("midi", evidence(midi));
            inputEvidence.put("wav", evidence(reference));
            inputEvidence.put("catalogue", evidence(index));
            for (var stem : options.stems().entrySet()) inputEvidence.put("stem-" + stem.getKey(), evidence(stem.getValue()));
            var outputEvidence = new LinkedHashMap<String, FileEvidence>();
            outputEvidence.put("project", evidence(source));
            outputEvidence.put("package", evidence(packagePath));
            outputEvidence.put("wav", evidence(audio));
            if (timeline.warped()) outputEvidence.put("referenceSteady", evidence(aligned));
            var noteCounts = transcription.sourceCounts();
            var partEvidence = score.parts().stream().map(p -> new PartEvidence(p.id(), p.name(), p.program(),
                    p.drums(), noteCounts.getOrDefault(p.id(), 0))).toList();
            var stages = structure == null ? null : new StageEvidence(structure.repeats().size(),
                    structure.corrections().stream().filter(ReconstructionStructure.Correction::applied).count(),
                    result.reductions().stream().filter(r -> "rapid-inner-tone-reattack".equals(r.reason())).count(),
                    result.reductions().stream().filter(r -> "isolated-harmony-octave-double".equals(r.reason())).count(),
                    result.project().clips().size() - result.project().sequences().size(),
                    structure.phrases() == null ? 0 : structure.phrases().size(), structure.sections().size(),
                    leadContinuity == null ? null : leadContinuity.reportCounts(fm1InterNoteGaps != null),
                    melodyGapFill, fm1InterNoteGaps);
            Map<Integer, ExternalTranscribers.Evidence> external = null;
            if (transcription.inputs() != null && transcription.inputs().externalNotes() != null) {
                external = new java.util.TreeMap<>();
                for (var entry : transcription.inputs().externalNotes().entrySet())
                    external.put(entry.getKey(), entry.getValue().provenance());
            }
            var runEvidence = new java.util.ArrayList<>(transcription.notes().stream().filter(note -> note.run() != null).toList());
            if (transcription.retriggers() != null)
                for (var burst : transcription.retriggers()) runEvidence.add(burst.figure());
            var assembled = result.project();
            var legacySustain = assemblyInputs == null ? matchQuietly(() -> ReconstructionSustain.inspect(assembled)) : null;
            var report = new Report(REPORT_VERSION, CatalogueMatcher.ASSET_PROFILE, durationUs, frames, compiled.song().length,
                    noteCounts.values().stream().mapToInt(Integer::intValue).sum(), result.fmNotes(), result.psgNotes(),
                    result.dacHits(), options.keepOctaves(), options.fmOnly(), inputEvidence, outputEvidence, partEvidence,
                    matched.evidence(), score.warnings(), options.wholePartVoices(), matched.sectionTrials(),
                    options.audioRefine(), (int) score.notes().stream().filter(note -> note.startUs() < durationUs).count(),
                    transcription.pitchChanges(), List.copyOf(effects.echoes().values()), result.echoNotes(), echoBankLimit,
                    effects.vibrato(), List.copyOf(effects.reverb().values()), result.reductions(),
                    ReconstructionAudioCheck.compare(aligned, audio, frames), stages,
                    result.echoNotes() == 0 ? List.of() : copyLanes, timeline.summary(), external,
                    runEvidence.isEmpty() ? null : runEvidence, fastRuns, transcription.figureVoices(),
                    result.bends().isEmpty() ? null : result.bends(), bendSizeLimit ? true : null,
                    drumSource(transcription, result.dacHits()),
                    assemblyInputs == null ? null : result.voicedGates(), assemblyInputs, legacySustain,
                    legacySustain == null || legacySustain.warning() == null ? List.of() : List.of(legacySustain.warning()),
                    result.assignmentClipCost() == 0 ? null : result.assignmentClipCost(),
                    result.arpeggioExtraNoteBytes());
            var json = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
            Files.writeString(target.resolve(OUTPUTS.get(3)), json.toJson(report) + "\n", java.nio.file.StandardOpenOption.CREATE_NEW);
            complete = true;
            out.printf("Reconstructed %d FM notes, %d PSG notes, and %d DAC hits over %d presentations into %s%n",
                    result.fmNotes(), result.psgNotes(), result.dacHits(), frames, destination);
        } finally {
            if (!complete) {
                Files.deleteIfExists(target.resolve(STEADY_REFERENCE));
                for (String name : OUTPUTS) Files.deleteIfExists(target.resolve(name));
                Files.delete(target);
            }
        }
    }

    private static ReconstructionDrums.Source drumSource(ReconstructionStages.Score score, int hits) {
        if (score.inputs() == null) return null;
        var requested = score.inputs().drumSource();
        return requested == null && hits > 0 ? ReconstructionDrums.Source.catalogue : requested;
    }

    private static Map<ReconstructionVoices.Key, ReconstructionVoices.Choice> select(
            InstrumentCatalogue catalogue, Path reference, Options options, MidiScore score,
            S3kReconstruction.Score prepared, long durationUs, Kind kind) throws IOException {
        return matchQuietly(() -> ReconstructionVoices.select(catalogue, reference, options.stems(),
                score, prepared.notes(), durationUs, kind));
    }

    private static <T> T matchQuietly(Matching<T> matching) throws IOException {
        // The engine logs its chip selection at INFO for every probe render.
        Logger logger = Logger.getLogger("com.openggf.audio.session.SmpsPhysicalDevice");
        Level previous = logger.getLevel();
        try {
            if (logger.isLoggable(Level.INFO)) logger.setLevel(Level.WARNING);
            return matching.run();
        } finally {
            logger.setLevel(previous);
        }
    }

    static Options parseOptions(String[] args, String file) {
        Long duration = null;
        String name = file.replaceFirst("(?i)\\.midi?$", "");
        int ticksPerBeat = 30;
        boolean keepOctaves = false;
        boolean fmOnly = false;
        boolean wholePartVoices = false;
        boolean audioRefine = false;
        var stems = new java.util.TreeMap<Integer, Path>();
        var seen = new java.util.HashSet<String>();
        for (int i = 5; i < args.length; i++) {
            String option = args[i];
            if (!option.equals("--stem") && !seen.add(option)) throw new IllegalArgumentException("Duplicate option " + option);
            if (option.equals("--keep-octaves")) {
                keepOctaves = true;
                continue;
            }
            if (option.equals("--fm-only")) {
                fmOnly = true;
                continue;
            }
            if (option.equals("--whole-part-voices")) {
                wholePartVoices = true;
                continue;
            }
            if (option.equals("--audio-refine")) {
                audioRefine = true;
                continue;
            }
            if (++i == args.length) throw new IllegalArgumentException("Missing argument to " + option);
            String value = args[i];
            switch (option) {
                case "--seconds" -> {
                    double seconds = Double.parseDouble(value);
                    if (!(seconds > 0 && seconds <= S3kRenderer.MAX_PRESENTATIONS / (double) PRESENTATIONS_PER_SECOND))
                        throw new IllegalArgumentException("--seconds must be in (0, 600]");
                    duration = Math.round(seconds * MICROS_PER_SECOND);
                }
                case "--name" -> name = value;
                case "--ticks-per-beat" -> {
                    ticksPerBeat = Integer.parseInt(value);
                    if (ticksPerBeat < 1) throw new IllegalArgumentException("--ticks-per-beat must be positive");
                }
                case "--stem" -> {
                    int split = value.indexOf('=');
                    if (split < 1 || split == value.length() - 1) throw new IllegalArgumentException("--stem expects PART=WAV");
                    int part = Integer.parseInt(value.substring(0, split));
                    if (part < 0) throw new IllegalArgumentException("MIDI part ID must be nonnegative");
                    if (stems.putIfAbsent(part, Path.of(value.substring(split + 1))) != null)
                        throw new IllegalArgumentException("Duplicate --stem for part " + part);
                }
                default -> throw new IllegalArgumentException("Unknown reconstruct option " + option);
            }
        }
        if (name.isBlank()) throw new IllegalArgumentException("Project name must not be blank");
        return new Options(duration, name, ticksPerBeat, keepOctaves, fmOnly, wholePartVoices, audioRefine, stems);
    }

    static String audioHash(Path path, java.nio.file.OpenOption... options) throws IOException {
        return evidence(path, options).sha256();
    }

    private static FileEvidence evidence(Path path, java.nio.file.OpenOption... options) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (var input = new DigestInputStream(Files.newInputStream(path, options), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return new FileEvidence(path.getFileName().toString(), HexFormat.of().formatHex(digest.digest()));
    }
}
