package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.GroupArrangement;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import static com.opensmpsdeck.headless.NoteHandoff.round;

/** Resolves editable piano-roll notes in a sequence or the first repeat of an opened clip. */
public final class ArrangementNoteContext {
    private record Placement(Clip clip, long origin, long end) {}
    private record Detail(NoteHandoff.Note note, List<CommandEvent> commands) {}
    private record Description(NoteHandoff.Project project, boolean stale, NoteHandoff.Context context,
                               List<String> missingIds, List<Clip> linkedClips, List<Detail> selected, List<Detail> neighbors) {}

    private final StudioProject project;
    private final Sequence sequence;
    private final Placement placement;
    private final NoteHandoff.Project identity;

    private ArrangementNoteContext(Path path, StudioProject project, boolean draft, String sequenceId, List<String> clips) {
        this.project = project;
        this.sequence = project.sequences().stream().filter(s -> s.id().equals(sequenceId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown sequence " + sequenceId));
        this.placement = resolve(project, clips);
        if (placement != null && !placement.clip.sequenceId().equals(sequenceId))
            throw new IllegalArgumentException("The clip does not use sequence " + sequenceId);
        this.identity = new NoteHandoff.Project(path.toAbsolutePath().normalize().toString(), project.name(),
                "arrangement", ReconstructionArtifacts.fingerprint(ProjectFormat.write(project)), draft,
                sequenceId, clips.isEmpty() ? null : List.copyOf(clips));
    }

    public static String copy(Path path, StudioProject project, boolean draft, String sequenceId,
                              List<String> clips, List<NoteEvent> selection) {
        return new ArrangementNoteContext(path, project, draft, sequenceId, clips).block(selection, false).text();
    }

    public static NoteHandoff.Capture capture(Path path, StudioProject project, boolean draft, String sequenceId,
                                              List<String> clips, List<NoteEvent> selection, NoteHandoff.ViewRange range) {
        var view = new ArrangementNoteContext(path, project, draft, sequenceId, clips);
        long origin = view.placement == null ? 0 : view.placement.origin;
        long from = origin + Math.round(range.from()), to = origin + Math.round(range.to());
        long barTicks = 4L * project.ticksPerBeat();
        var area = new NoteHandoff.Area("project", round(view.seconds(from)), round(view.seconds(to)), from, to,
                (int) (1 + from / barTicks), (int) (1 + Math.max(from, to - 1) / barTicks),
                range.minMidi(), range.maxMidi(), range.viewState());
        return view.block(selection, true).capture(area);
    }

    private NoteHandoff.Block block(List<NoteEvent> selection, boolean lasso) {
        var selected = selection.stream().map(this::note).sorted(Comparator.comparingLong(NoteHandoff.Note::tick)).toList();
        var sample = NoteHandoff.sample(selected);
        var shown = lasso ? selected : sample;
        var context = context(selected, 1, selected.size() - shown.size());
        String fetch = selected.isEmpty() ? null : "opensmpsdeck describe-notes " + NoteHandoff.quote(identity.path())
                + " --sequence " + NoteHandoff.quote(sequence.id())
                + (identity.clip() == null ? "" : " --clip " + NoteHandoff.quote(NoteHandoff.JSON.toJson(identity.clip())))
                + " --notes " + NoteHandoff.noteArgument(sample) + " --context 1bar --revision " + identity.revision();
        return new NoteHandoff.Block(identity, context, shown, fetch);
    }

    static void run(String[] args, PrintStream out) throws IOException {
        if (args.length == 0) throw new IllegalArgumentException("describe-notes expects PROJECT");
        var options = new HashMap<String, String>();
        for (int i = 1; i < args.length; i += 2) {
            var key = args[i];
            if (!List.of("--sequence", "--clip", "--notes", "--context", "--revision").contains(key))
                throw new IllegalArgumentException("Unknown describe-notes option " + key);
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + key);
            if (options.putIfAbsent(key, args[i + 1]) != null) throw new IllegalArgumentException("Duplicate option " + key);
        }
        String sequence = options.get("--sequence");
        if (sequence == null) throw new IllegalArgumentException("Expected --sequence ID");
        var ids = NoteHandoff.ids(options.get("--notes"));
        var clips = options.containsKey("--clip") ? NoteHandoff.idArray(options.get("--clip")) : List.<String>of();
        var path = Path.of(args[0]);
        var view = new ArrangementNoteContext(path, ProjectFiles.load(path).project(), false, sequence, clips);
        out.println(NoteHandoff.JSON.toJson(view.describe(ids, NoteHandoff.contextBars(options.get("--context")),
                options.get("--revision"))));
    }

    private Description describe(List<String> ids, int following, String expected) {
        var selected = sequence.notes().stream().filter(note -> ids.contains(note.id())).map(this::detail).toList();
        var context = context(selected.stream().map(Detail::note).toList(), following, 0);
        var neighbors = neighbors(ids, context);
        var missing = ids.stream().filter(id -> selected.stream().noneMatch(detail -> detail.note.id().equals(id))).toList();
        var linked = GroupArrangement.flatten(project).stream().filter(clip -> clip.sequenceId().equals(sequence.id())).toList();
        return new Description(identity, expected != null && !expected.equals(identity.revision()), context,
                missing, linked, selected, neighbors);
    }

    private List<Detail> neighbors(List<String> selected, NoteHandoff.Context context) {
        if (context.firstBar() == null) return List.of();
        long barTicks = 4L * project.ticksPerBeat();
        long from = (context.firstBar() - 1) * barTicks;
        long until = context.throughBar() * barTicks;
        long origin = placement == null ? 0 : placement.origin;
        long end = placement == null ? sequence.lengthTicks() : placement.end;
        int period = sequence.lengthTicks();
        long first = origin + Math.max(0, (from - origin) / period) * period;
        var result = new java.util.ArrayList<Detail>();
        for (long repeat = first; repeat < Math.min(end, until); repeat += period) {
            for (var event : sequence.notes()) {
                long tick = repeat + event.tick();
                if (tick >= Math.min(end, until) || tick + event.duration() <= from
                        || repeat == origin && selected.contains(event.id())) continue;
                result.add(detail(event, repeat));
            }
        }
        return result;
    }

    private Detail detail(NoteEvent event) {
        return detail(event, placement == null ? 0 : placement.origin);
    }

    private Detail detail(NoteEvent event, long origin) {
        return new Detail(note(event, origin), sequence.commands().stream()
                .filter(command -> command.tick() < (long) event.tick() + event.duration()).toList());
    }

    private NoteHandoff.Context context(List<NoteHandoff.Note> notes, int following, int omitted) {
        Integer first = notes.stream().map(NoteHandoff.Note::bar).min(Integer::compare).orElse(null);
        Integer last = notes.stream().map(NoteHandoff.Note::bar).max(Integer::compare).orElse(null);
        return new NoteHandoff.Context(first, last, last == null ? null : last + following, following, notes.size(), omitted);
    }

    private NoteHandoff.Note note(NoteEvent note) {
        return note(note, placement == null ? 0 : placement.origin);
    }

    private NoteHandoff.Note note(NoteEvent note, long origin) {
        long tick = origin + note.tick();
        long duration = note.duration();
        String playback = "scheduled";
        String reason = "placement on the project timeline";
        if (placement == null) {
            playback = "unplaced";
            reason = "sequence-relative location";
        } else if (tick >= placement.end) {
            playback = "dropped";
            reason = "first clip repeat ends before the attack";
        } else if (tick + duration > placement.end) {
            duration = placement.end - tick;
            playback = "truncated";
            reason = "clipped at the placement end";
        }
        long barTicks = 4L * project.ticksPerBeat();
        var voice = lastCommand(NativeCommand.FM_VOICE, note.tick());
        var modulation = lastCommand(NativeCommand.MODULATION_ENVELOPE, note.tick());
        var effect = sequence.commands().stream().filter(c -> c.tick() <= note.tick()
                        && (c.command() == NativeCommand.MODULATION || c.command() == NativeCommand.MODULATION_OFF))
                .max(Comparator.comparingInt(CommandEvent::tick)).orElse(null);
        String event = placement != null && origin != placement.origin ? note.id() + "@" + tick : null;
        return new NoteHandoff.Note(note.id(), event, sequence.name(), null,
                voice == null ? sequence.voiceId() == null ? sequence.psgEnvelopeId() : sequence.voiceId() : voice.assetId(),
                placement == null ? null : placement.clip.lane(), (int) (1 + tick / barTicks),
                round(1 + (tick % barTicks) / (double) project.ticksPerBeat()), null, round(seconds(tick)), tick, note.tick(),
                duration, round(seconds(tick + duration) - seconds(tick)), ReconstructionDescribe.pitch(note.pitch()), note.pitch(),
                null, "arrangement", null, playback, reason, null, null, List.of(), List.of(),
                modulation == null ? sequence.modulationEnvelopeId() : modulation.assetId(),
                effect != null && effect.command() == NativeCommand.MODULATION ? effect.arguments().toString() : null);
    }

    private CommandEvent lastCommand(NativeCommand command, int tick) {
        return sequence.commands().stream().filter(c -> c.command() == command && c.tick() <= tick)
                .max(Comparator.comparingInt(CommandEvent::tick)).orElse(null);
    }

    private double seconds(long tick) {
        return project.nativeTempo() == null ? tick / project.clock().ticksPerSecond() : project.clock().secondsAtTick(tick);
    }

    private static Placement resolve(StudioProject project, List<String> path) {
        if (path.isEmpty()) return null;
        long origin = 0, end = Long.MAX_VALUE;
        String parent = null;
        for (String id : path.subList(0, path.size() - 1)) {
            var entry = GroupArrangement.placements(project, parent).stream().filter(p -> p.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown group placement " + id));
            origin += entry.startTick();
            end = Math.min(end, origin + entry.lengthTicks());
            parent = entry.groupId();
        }
        var clip = GroupArrangement.clips(project, parent).stream().filter(c -> c.id().equals(path.getLast())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown clip " + path.getLast()));
        origin += clip.startTick();
        return new Placement(clip, origin, Math.min(end, origin + clip.lengthTicks()));
    }
}
