package com.opensmpsdeck.project;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared resolution of linked arrangements into finite channel placements. */
public final class GroupArrangement {
    public static final int MAX_DEPTH = 4;
    static final int MAX_EXPANDED_PLACEMENTS = 32_768;

    private final StudioProject project;
    private final Map<String, GroupSequence> groups = new LinkedHashMap<>();
    private record Child(GroupPlacement placement, int firstTick) {}
    private record Resolved(int depth, int firstTick, List<Clip> clips, List<Child> groups) {}
    private final Map<String, Resolved> resolved = new HashMap<>();

    public GroupArrangement(StudioProject project) {
        this.project = project;
        for (GroupSequence group : project.groupSequences()) {
            requireId(group.id());
            if (groups.putIfAbsent(group.id(), group) != null)
                throw new IllegalArgumentException("Duplicate group sequence ID '" + group.id() + "'");
        }
    }

    public static List<Clip> flatten(StudioProject project) {
        if (project.groupPlacements().isEmpty()) return project.clips();
        return new GroupArrangement(project).expand(project.clips(), project.groupPlacements(), Integer.MAX_VALUE);
    }

    /** Expand one instance into absolute ticks of its containing arrangement. */
    public List<Clip> expand(GroupPlacement placement) {
        return expand(List.of(), List.of(placement), Integer.MAX_VALUE);
    }

    public static GroupSequence source(StudioProject project, String id) {
        return project.groupSequences().stream().filter(group -> group.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown group sequence '" + id + "'"));
    }

    public static List<Clip> clips(StudioProject project, String parentId) {
        return parentId == null ? project.clips() : source(project, parentId).clips();
    }

    public static List<GroupPlacement> placements(StudioProject project, String parentId) {
        return parentId == null ? project.groupPlacements() : source(project, parentId).groupPlacements();
    }

    /** Authored clip occurrences across the song and all group definitions. */
    public static java.util.stream.Stream<Clip> authoredClips(StudioProject project) {
        return java.util.stream.Stream.concat(project.clips().stream(),
                project.groupSequences().stream().flatMap(group -> group.clips().stream()));
    }

    public static long endTick(StudioProject project) {
        return Math.max(project.clips().stream().mapToInt(Clip::endTick).max().orElse(0),
                project.groupPlacements().stream().mapToInt(GroupPlacement::endTick).max().orElse(0));
    }

    List<Clip> validate(Map<String, Sequence> sequences) {
        for (GroupSequence group : groups.values()) {
            if (group.name() == null || group.name().isBlank() || group.lengthTicks() < 1)
                throw new IllegalArgumentException("Group '" + group.id() + "' requires a name and positive length");
            validateScope(group.clips(), group.groupPlacements(), group.lengthTicks(), sequences, "Group '" + group.id() + "'");
        }
        validateScope(project.clips(), project.groupPlacements(), Integer.MAX_VALUE, sequences, "Song");

        resolveGroups();
        for (GroupSequence group : groups.values()) {
            checkConflicts(expand(group.clips(), group.groupPlacements(), group.lengthTicks()), "Group '" + group.id() + "'");
        }
        var expanded = expand(project.clips(), project.groupPlacements(), Integer.MAX_VALUE);
        checkConflicts(expanded, "Song");
        return expanded;
    }

    private void validateScope(List<Clip> clips, List<GroupPlacement> placements, int limit,
                               Map<String, Sequence> sequences, String scope) {
        Set<String> ids = new LinkedHashSet<>();
        for (Clip clip : clips) {
            validatePlacement(clip, ids, limit, scope);
            Sequence sequence = sequences.get(clip.sequenceId());
            if (sequence == null)
                throw new IllegalArgumentException("clip '" + clip.id() + "'.sequenceId: undefined sequence '" + clip.sequenceId() + "'");
            if (clip.lane() == null || !clip.lane().matches("FM[1-5]|PSG[1-3]|DAC"))
                throw new IllegalArgumentException("clip '" + clip.id() + "'.lane: use FM1..FM5, PSG1..PSG3 or DAC");
            if (!clip.lane().startsWith(sequence.type()))
                throw new IllegalArgumentException("clip '" + clip.id() + "'.lane: lane type must match sequence type " + sequence.type());
        }
        for (GroupPlacement placement : placements) {
            validatePlacement(placement, ids, limit, scope);
            group(placement.groupId());
        }
    }

    private static void validatePlacement(Placement placement, Set<String> ids, int limit, String scope) {
        requireId(placement.id());
        if (!ids.add(placement.id()))
            throw new IllegalArgumentException(scope + ": duplicate id '" + placement.id() + "'");
        if (placement.startTick() < 0 || placement.lengthTicks() < 1
                || (long) placement.startTick() + placement.lengthTicks() > limit)
            throw new IllegalArgumentException(scope + ": placement '" + placement.id()
                    + "' must have a positive length and lie within ticks 0.." + limit);
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Placement and group IDs must be nonblank");
    }

    private GroupSequence group(String id) {
        GroupSequence group = groups.get(id);
        if (group == null) throw new IllegalArgumentException("Undefined group sequence '" + id + "'");
        return group;
    }

    private void resolveGroups() {
        if (resolved.size() == groups.size()) return;
        var parents = new HashMap<String, List<GroupSequence>>();
        var remaining = new LinkedHashMap<String, Integer>();
        var ready = new ArrayDeque<GroupSequence>();
        for (var group : groups.values()) {
            int children = group.groupPlacements().size();
            remaining.put(group.id(), children);
            if (children == 0) ready.add(group);
            for (var child : group.groupPlacements()) {
                group(child.groupId());
                parents.computeIfAbsent(child.groupId(), ignored -> new ArrayList<>()).add(group);
            }
        }
        while (!ready.isEmpty()) {
            GroupSequence group = ready.removeFirst();
            var clips = group.clips().stream().sorted(Comparator.comparingInt(Clip::startTick)).toList();
            var children = new ArrayList<Child>();
            int depth = 1;
            for (var placement : group.groupPlacements()) {
                Resolved child = resolved.get(placement.groupId());
                depth = Math.max(depth, 1 + child.depth());
                if (child.firstTick() < placement.lengthTicks())
                    children.add(new Child(placement, placement.startTick() + child.firstTick()));
            }
            children.sort(Comparator.comparingInt(Child::firstTick));
            int first = Math.min(clips.isEmpty() ? Integer.MAX_VALUE : clips.getFirst().startTick(),
                    children.isEmpty() ? Integer.MAX_VALUE : children.getFirst().firstTick());
            resolved.put(group.id(), new Resolved(depth, first, clips, List.copyOf(children)));
            for (GroupSequence parent : parents.getOrDefault(group.id(), List.of()))
                if (remaining.merge(parent.id(), -1, Integer::sum) == 0) ready.add(parent);
        }
        if (resolved.size() != groups.size()) {
            // Every unresolved node has at least one unresolved child. Follow those edges to the cycle.
            String id = remaining.keySet().stream().filter(key -> !resolved.containsKey(key)).findFirst().orElseThrow();
            var path = new LinkedHashSet<String>();
            while (path.add(id)) {
                id = group(id).groupPlacements().stream().map(GroupPlacement::groupId)
                        .filter(child -> !resolved.containsKey(child)).findFirst().orElseThrow();
            }
            String repeated = id;
            throw new IllegalArgumentException("Group sequence cycle: " + String.join(" → ",
                    path.stream().dropWhile(key -> !key.equals(repeated)).map(GroupArrangement::brief).toList())
                    + " → " + brief(id));
        }
        for (String id : groups.keySet()) {
            if (resolved.get(id).depth() > MAX_DEPTH)
                throw new IllegalArgumentException("Group sequence depth exceeds " + MAX_DEPTH + " at '" + id + "'");
        }
    }

    private static String brief(String id) {
        return id.length() <= 100 ? id : id.substring(0, 97) + "…";
    }

    private List<Clip> expand(List<Clip> clips, List<GroupPlacement> placements, int limit) {
        if (placements.isEmpty()) return clips;
        var result = new ArrayList<>(clips);
        resolveGroups();
        for (GroupPlacement placement : placements) expand(placement, 0, limit, "", result);
        return List.copyOf(result);
    }

    private void expand(GroupPlacement placement, int origin, int limit, String prefix, List<Clip> result) {
        GroupSequence source = group(placement.groupId());
        long start = (long) origin + placement.startTick();
        long end = Math.min(limit, start + placement.lengthTicks());
        Resolved content = resolved.get(source.id());
        for (long repeat = start; repeat + content.firstTick() < end; repeat += source.lengthTicks()) {
            int repeatEnd = (int) Math.min(end, repeat + source.lengthTicks());
            String path = prefix + brief(placement.id()) + "[" + (1 + (repeat-start)/source.lengthTicks()) + "]/";
            for (Clip clip : content.clips()) {
                long tick = repeat + clip.startTick();
                if (tick >= repeatEnd) break;
                if (result.size() >= MAX_EXPANDED_PLACEMENTS)
                    throw new IllegalArgumentException("Group expansion exceeds " + MAX_EXPANDED_PLACEMENTS + " clips at " + path);
                result.add(new Clip(path + brief(clip.id()), clip.sequenceId(), clip.lane(), (int) tick,
                        Math.min(clip.lengthTicks(), repeatEnd - (int) tick)));
            }
            for (Child child : content.groups()) {
                if (repeat + child.firstTick() >= repeatEnd) break;
                expand(child.placement(), (int) repeat, repeatEnd, path, result);
            }
        }
    }

    private static void checkConflicts(List<Clip> clips, String scope) {
        var ordered = clips.stream().sorted(Comparator.comparing(Clip::lane).thenComparingInt(Clip::startTick)).toList();
        Clip previous = null;
        for (Clip clip : ordered) {
            if (previous != null && clip.lane().equals(previous.lane()) && clip.startTick() < previous.endTick())
                throw new IllegalArgumentException(scope + ": lane conflict on " + clip.lane() + " at tick "
                        + clip.startTick() + ": '" + clip.id() + "' overlaps another clip '" + previous.id() + "'");
            previous = clip;
        }
    }
}
