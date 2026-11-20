package com.opensmpsdeck.headless;

import java.util.List;

/** Project zero is source zero, including when the first downbeat follows a pickup. */
record ReconstructionTimeMap(ReconstructionBeatMap beats, Double bpm, double sourceDuration) {
    static final double DRIFT_THRESHOLD = .0085;
    static final double MIN_CONFIDENCE = .40;

    enum TimeBase { source, project }

    static void validateOption(String option) {
        if (option == null || option.equals("auto") || option.equals("off")) return;
        try {
            double bpm = Double.parseDouble(option);
            if (bpm >= 20 && bpm <= 400) return;
        } catch (NumberFormatException ignored) {
            // Use the same diagnostic for nonnumeric and out-of-range values.
        }
        throw new IllegalArgumentException("--steady-tempo expects off, auto, or BPM between 20 and 400");
    }

    static ReconstructionTimeMap of(ReconstructionStages.Score score) {
        var beats = score.beatMap();
        var grid = score.inputs() == null ? null : score.inputs().grid();
        String option = grid == null ? null : grid.steady();
        Double bpm = null;
        if (beats != null && !"off".equals(option)) {
            if (option != null && !option.equals("auto")) bpm = Double.parseDouble(option);
            else if ((grid == null || grid.bpm() == null) && beats.confidence() >= MIN_CONFIDENCE
                    && beats.deviation() >= DRIFT_THRESHOLD) bpm = beats.openingBpm();
        }
        return new ReconstructionTimeMap(beats, bpm, score.durationUs() / 1e6);
    }

    boolean warped() { return bpm != null; }

    double project(double source) {
        return warped() ? (beats.index(source) - beats.index(0)) * (60 / bpm) : source;
    }

    double source(double project) {
        // Avoid roundoff below the WAV boundary when inverting the anchor itself.
        if (!warped() || project == 0) return project;
        return beats.seconds(beats.index(0) + project * (bpm / 60));
    }

    TimeBase timeBase() { return warped() ? TimeBase.project : TimeBase.source; }

    double projectDuration() { return project(sourceDuration); }

    int frames() {
        return (int) Math.ceil(projectDuration() * 60 - 1e-8);
    }

    ReconstructionStages.Note projectNote(ReconstructionStages.Note note) {
        return warped() ? note.mapTime(this::project) : note;
    }

    ReconstructionStages.Note sourceNote(ReconstructionStages.Note note) {
        return warped() ? note.mapTime(this::source) : note;
    }

    List<ReconstructionStructure.Bar> projectBars(List<ReconstructionStructure.Bar> bars) {
        if (!warped() || bars == null) return bars;
        return bars.stream().map(bar -> new ReconstructionStructure.Bar(bar.id(),
                project(bar.start()), project(bar.end()), bar.complete())).toList();
    }

    ReconstructionBeatMap.Summary summary() {
        return new ReconstructionBeatMap.Summary(beats == null ? null : beats.openingBpm(),
                beats == null ? null : beats.closingBpm(), beats == null ? null : beats.deviation(),
                beats == null ? 0 : beats.confidence(), warped(), bpm, frames() / 60.0);
    }
}
