package com.opensmpsdeck.headless;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.DoubleUnaryOperator;

/** The reconstruction's own project-to-source transform, shared with benchmark diagnostics. */
public record BenchClock(double sourceDuration, DoubleUnaryOperator toSource, DoubleUnaryOperator toProject) {
    public static BenchClock read(Path work) throws IOException {
        var time = ReconstructionTimeMap.of(ReconstructionStages.readScore(work.resolve("score.json")));
        return new BenchClock(time.sourceDuration(), time::source, time::project);
    }
}
