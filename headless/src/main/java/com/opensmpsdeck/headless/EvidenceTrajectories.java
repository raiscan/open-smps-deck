package com.opensmpsdeck.headless;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

import static com.opensmpsdeck.headless.ReconstructionTimeMap.TimeBase;
import static com.opensmpsdeck.headless.ReconstructionView.Span;

final class EvidenceTrajectories {
    record Point(double time, double sourceTime, double projectTime, double pitch, double strength) {}
    record Sample(double time, double sourceTime, double projectTime,
                  EvidenceSpectrum.Peak fundamental, EvidenceSpectrum.Peak second, EvidenceSpectrum.Peak third) {}
    record Target(String noteId, int pitch, double start, double end, DoubleUnaryOperator expectedPitch) {}
    record Track(String noteId, int pitch, List<Sample> points) {}

    private EvidenceTrajectories() {}

    static List<Point> ridges(EvidenceSpectrum.Spectrum spectrum, Span span, int count) {
        var points = new ArrayList<Point>();
        for (int column = 0; column < spectrum.columns(); column++) {
            double time = center(span, spectrum, column);
            for (var peak : spectrum.peaks(column, count))
                points.add(new Point(time, span.seconds(time, TimeBase.source), span.seconds(time, TimeBase.project),
                        peak.pitch(), peak.strength()));
        }
        return points;
    }

    static List<Track> tracks(EvidenceSpectrum.Spectrum spectrum, Span span, List<Target> targets) {
        return targets.stream().map(target -> track(spectrum, span, target)).toList();
    }

    private static Track track(EvidenceSpectrum.Spectrum spectrum, Span span, Target target) {
        var points = new ArrayList<Sample>();
        for (int column = 0; column < spectrum.columns(); column++) {
            double time = center(span, spectrum, column);
            if (time < target.start || time >= target.end) continue;
            double expected = target.expectedPitch.applyAsDouble(time);
            // The note supplies the fundamental register even if its second or third
            // harmonic is the strongest peak in the column.
            var fundamental = read(spectrum, column, expected, 1.5);
            double pitch = fundamental == null ? expected : fundamental.pitch();
            var second = read(spectrum, column, pitch + EvidenceSpectrum.harmonicPitch(0, 2), .6);
            var third = read(spectrum, column, pitch + EvidenceSpectrum.harmonicPitch(0, 3), .6);
            points.add(new Sample(time, span.seconds(time, TimeBase.source), span.seconds(time, TimeBase.project),
                    fundamental, second, third));
        }
        return new Track(target.noteId, target.pitch, points);
    }

    private static EvidenceSpectrum.Peak read(EvidenceSpectrum.Spectrum spectrum, int column, double pitch, double tolerance) {
        var peak = spectrum.near(column, pitch, tolerance);
        return peak == null ? spectrum.at(column, pitch) : peak;
    }

    private static double center(Span span, EvidenceSpectrum.Spectrum spectrum, int column) {
        return span.start() + (column + .5) * span.length() / spectrum.columns();
    }
}
