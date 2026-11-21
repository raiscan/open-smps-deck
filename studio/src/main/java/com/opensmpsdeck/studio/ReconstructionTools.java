package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ReconstructionInspection;
import com.opensmpsdeck.headless.ReconstructionInspection.Interval;
import com.opensmpsdeck.headless.ReconstructionInspection.Overlay;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Tool and selection state shared by the viewport and inspector. */
final class ReconstructionTools {
    enum Mode {
        SELECT("Select", true), INSERT("Insert", true), CUT("Cut", false),
        BEND("Bend / slide", false), VIBRATO("Vibrato", false);

        final String label;
        final boolean available;
        Mode(String label, boolean available) { this.label = label; this.available = available; }
    }

    record Placement(double start, double end, int pitch) {
        static Placement of(Overlay note) {
            return new Placement(note.interval().sourceStart(), note.interval().sourceEnd(), note.pitch());
        }

        Interval interval(ReconstructionInspection model) {
            return new Interval(start, end, model.toProject(start), model.toProject(end));
        }
    }

    private final Set<String> selected = new LinkedHashSet<>();
    private String primary;

    void select(Collection<String> eventIds, String primary) {
        selected.clear();
        selected.addAll(eventIds);
        this.primary = selected.contains(primary) ? primary : selected.stream().findFirst().orElse(null);
    }

    boolean contains(Overlay note) { return selected.contains(note.eventId()); }
    boolean primary(Overlay note) { return Objects.equals(primary, note.eventId()); }
    Set<String> eventIds() { return Set.copyOf(selected); }
}
