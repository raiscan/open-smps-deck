package com.opensmpsdeck.project;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Atomic arrangement edits shared by Studio and the CLI. A null parent means the song. */
public final class GroupEdits {
    private GroupEdits() {}

    public static StudioProject create(StudioProject project, String parentId, String id, String name,
                                       Set<String> selection) {
        requireEditable(project);
        var scope = new Scope(project, parentId);
        var selected = Stream.concat(scope.clips.stream(), scope.placements.stream())
                .filter(item -> selection.contains(item.id())).toList();
        if (selection.isEmpty() || selected.size() != selection.size())
            throw new IllegalArgumentException("Select existing clips or groups in the same arrangement");
        int start = selected.stream().mapToInt(Placement::startTick).min().orElseThrow();
        int length = selected.stream().mapToInt(Placement::endTick).max().orElseThrow() - start;
        var children = new ArrayList<Clip>();
        var nested = new ArrayList<GroupPlacement>();
        for (Placement item : selected) {
            switch (item) {
                case Clip clip -> children.add(new Clip(clip.id(), clip.sequenceId(), clip.lane(),
                        clip.startTick() - start, clip.lengthTicks()));
                case GroupPlacement group -> nested.add(new GroupPlacement(group.id(), group.groupId(),
                        group.startTick() - start, group.lengthTicks()));
            }
        }
        scope.clips.removeIf(item -> selection.contains(item.id()));
        scope.placements.removeIf(item -> selection.contains(item.id()));
        var groups = new ArrayList<>(project.groupSequences());
        groups.add(new GroupSequence(id, name, length, children, nested));
        scope.placements.add(new GroupPlacement(uniqueId(id + "-placement", scope.ids()), id, start, length));
        return scope.commit(project, parentId, groups);
    }

    public static StudioProject place(StudioProject project, String parentId, Placement placement) {
        return change(project, parentId, null, placement);
    }

    public static StudioProject replace(StudioProject project, String parentId, Placement placement) {
        return change(project, parentId, placement.id(), placement);
    }

    public static StudioProject remove(StudioProject project, String parentId, String id) {
        return change(project, parentId, id, null);
    }

    private static StudioProject change(StudioProject project, String parentId, String id, Placement replacement) {
        requireEditable(project);
        var scope = new Scope(project, parentId);
        if (id != null) {
            int clip = indexOf(scope.clips, id);
            int placement = indexOf(scope.placements, id);
            if (clip >= 0 && replacement instanceof Clip updated) scope.clips.set(clip, updated);
            else if (placement >= 0 && replacement instanceof GroupPlacement updated) scope.placements.set(placement, updated);
            else if (clip >= 0 && replacement == null) scope.clips.remove(clip);
            else if (placement >= 0 && replacement == null) scope.placements.remove(placement);
            else throw new IllegalArgumentException("Unknown placement '" + id + "' of the requested type");
        } else {
            switch (replacement) {
                case Clip clip -> scope.clips.add(clip);
                case GroupPlacement placement -> scope.placements.add(placement);
            }
        }
        return scope.commit(project, parentId, project.groupSequences());
    }

    private static int indexOf(List<? extends Placement> items, String id) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).id().equals(id)) return i;
        return -1;
    }

    public static StudioProject ungroup(StudioProject project, String parentId, String placementId) {
        requireEditable(project);
        var scope = new Scope(project, parentId);
        var instance = scope.placements.stream().filter(item -> item.id().equals(placementId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown group placement '" + placementId + "'"));
        var source = GroupArrangement.source(project, instance.groupId());
        scope.placements.remove(instance);
        Set<String> used = scope.ids();
        var children = Stream.concat(source.clips().stream(), source.groupPlacements().stream()).toList();
        if (!children.isEmpty()) {
            for (long repeat = instance.startTick(); repeat < instance.endTick(); repeat += source.lengthTicks()) {
                for (Placement child : children) {
                    long start = repeat + child.startTick();
                    if (start >= instance.endTick()) continue;
                    if (used.size() >= GroupArrangement.MAX_EXPANDED_PLACEMENTS)
                        throw new IllegalArgumentException("Ungroup would create too many placements");
                    int length = Math.min(child.lengthTicks(), instance.endTick() - (int) start);
                    long occurrence = (repeat - instance.startTick()) / source.lengthTicks() + 1;
                    String id = used.add(child.id()) ? child.id()
                            : uniqueId(instance.id() + "-" + occurrence + "-" + child.id(), used);
                    switch (child) {
                        case Clip clip -> scope.clips.add(new Clip(id, clip.sequenceId(), clip.lane(), (int) start, length));
                        case GroupPlacement group -> scope.placements.add(new GroupPlacement(id, group.groupId(), (int) start, length));
                    }
                }
            }
        }
        return scope.commit(project, parentId, project.groupSequences());
    }

    public static StudioProject updateSource(StudioProject project, String id, String name, int length) {
        requireEditable(project);
        GroupArrangement.source(project, id);
        var groups = project.groupSequences().stream().map(group -> group.id().equals(id)
                ? new GroupSequence(id, name, length, group.clips(), group.groupPlacements()) : group).toList();
        return new Scope(project, null).commit(project, null, groups);
    }

    private static String uniqueId(String preferred, Set<String> ids) {
        String id = preferred;
        for (int attempt = 2; !ids.add(id); attempt++) id = preferred + "-" + attempt;
        return id;
    }

    private static void requireEditable(StudioProject project) {
        if (project.nativeProgram() != null) throw new IllegalArgumentException("Groups require an editable arrangement");
    }

    private static final class Scope {
        final ArrayList<Clip> clips;
        final ArrayList<GroupPlacement> placements;

        Scope(StudioProject project, String parentId) {
            clips = new ArrayList<>(GroupArrangement.clips(project, parentId));
            placements = new ArrayList<>(GroupArrangement.placements(project, parentId));
        }

        Set<String> ids() {
            Set<String> ids = new HashSet<>();
            clips.forEach(clip -> ids.add(clip.id()));
            placements.forEach(placement -> ids.add(placement.id()));
            return ids;
        }

        StudioProject commit(StudioProject project, String parentId, List<GroupSequence> groups) {
            List<Clip> songClips = clips;
            List<GroupPlacement> songGroups = placements;
            if (parentId != null) {
                groups = groups.stream().map(group -> group.id().equals(parentId)
                        ? new GroupSequence(group.id(), group.name(), group.lengthTicks(), clips, placements)
                        : group).toList();
                songClips = project.clips();
                songGroups = project.groupPlacements();
            }
            int version = groups.isEmpty() && songGroups.isEmpty() ? project.formatVersion() : 6;
            var updated = new StudioProject(version, project.name(), project.mode(), project.ticksPerBeat(),
                    project.tempo(), project.voices(), project.sequences(), songClips, project.envelopes(),
                    project.samples(), null, project.references(), project.notes(), project.arrangementVersion(), groups, songGroups,
                    project.detachedFromReconstruction(), project.derivedFrom(), project.target(), project.nativeTempo());
            ProjectFormat.validate(updated);
            return updated;
        }
    }
}
