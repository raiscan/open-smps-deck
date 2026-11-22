package com.opensmpsdeck.headless;

import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.audio.synth.PsgChip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.opensmpsdeck.headless.ReconstructionTimeMap.TimeBase;
import static com.opensmpsdeck.headless.ReconstructionView.*;

final class EvidenceBends implements ChipWriteObserver {
    record Point(double time, double sourceTime, double projectTime, double pitch) {}
    record Curve(String noteId, String lane, String kind, List<Point> points) {}

    private final int[] high = new int[5];
    private final int[] packed = new int[5];
    private final int[] periods = new int[3];
    private final int[][] fmFrequencies;
    private final int[][] psgPeriods;
    private int latchedPsgRegister = -1;

    EvidenceBends(int end) {
        fmFrequencies = new int[5][end];
        psgPeriods = new int[3][end];
    }

    @Override public void onYm2612Write(int port, int register, int value) {
        int channel = port * 3 + (register & 3);
        if ((register & 3) == 3 || channel >= 5) return;
        if ((register & ~3) == 0xa4) high[channel] = value;
        if ((register & ~3) == 0xa0) packed[channel] = (high[channel] << 8) | value;
    }

    @Override public void onPsgWrite(int value) {
        boolean latch = (value & 0x80) != 0;
        if (latch) latchedPsgRegister = (value >> 4) & 7;
        if (latchedPsgRegister < 0 || latchedPsgRegister >= 6 || (latchedPsgRegister & 1) != 0) return;
        int channel = latchedPsgRegister / 2;
        if (latch) periods[channel] = (periods[channel] & 0x3f0) | (value & 0xf);
        else periods[channel] = (periods[channel] & 0xf) | ((value & 0x3f) << 4);
    }

    void frame(int tick) {
        for (int channel = 0; channel < packed.length; channel++) fmFrequencies[channel][tick] = packed[channel];
        for (int channel = 0; channel < periods.length; channel++) psgPeriods[channel][tick] = periods[channel];
    }

    static List<Curve> authored(Span span, Map<String, ReconstructionTrace.Row> rows, List<DrawnNote> visible, TimeBase clock) {
        var curves = new ArrayList<Curve>();
        var visibleIds = visible.stream().map(DrawnNote::id).collect(java.util.stream.Collectors.toSet());
        for (var row : rows.values()) {
            if (!visibleIds.contains(row.id())) continue;
            var curve = authoredCurve(span, row, clock);
            if (curve != null) curves.add(curve);
        }
        return curves;
    }

    static Curve authoredCurve(Span span, ReconstructionTrace.Row row, TimeBase clock) {
        var note = row.allocation() == null ? null : row.allocation().effective();
        if (note == null || note.note().bend() == null) return null;
        var bend = note.note().bend();
        double start = span.start(note.timing()), end = span.end(note.timing());
        if (end <= span.start() || start >= span.end()) return null;
        var points = new ArrayList<Point>();
        double from = Math.max(span.start(), start), to = Math.min(span.end(), end);
        points.add(point(span, from, note.note().pitch() + bend.cents(span.seconds(from, clock)) / 100));
        for (var step : bend.points()) {
            double time = span.base() == clock ? step.time()
                    : span.base() == TimeBase.source ? span.timeline().source(step.time()) : span.timeline().project(step.time());
            if (time > from && time < to) points.add(point(span, time, note.note().pitch() + step.cents() / 100.0));
        }
        points.add(point(span, to, note.note().pitch() + bend.cents(span.seconds(to, clock)) / 100));
        return new Curve(row.id(), row.allocation().lane(), bend.editReason() == null ? "stem" : "authored", points);
    }

    static List<ReconstructionTrace.Voiced> assembled(Map<String, ReconstructionTrace.Row> rows, List<DrawnNote> notes) {
        var visible = notes.stream().filter(note -> note.layer() == Layer.lane)
                .map(note -> List.of(note.id(), note.lane())).collect(java.util.stream.Collectors.toSet());
        return rows.values().stream().flatMap(row -> row.voiced().stream())
                .filter(gate -> gate.bend() != null && "assembled".equals(gate.evidence())
                        && visible.contains(List.of(gate.id(), gate.lane()))).toList();
    }

    List<Curve> curves(Span span, List<ReconstructionTrace.Voiced> gates) {
        var curves = new ArrayList<Curve>();
        for (var gate : gates) {
            int fmChannel = ReconstructionAllocation.FM.indexOf(gate.lane());
            int psgChannel = ReconstructionAllocation.PSG.indexOf(gate.lane());
            if (fmChannel < 0 && psgChannel < 0) continue;
            int[] lane = fmChannel >= 0 ? fmFrequencies[fmChannel] : psgPeriods[psgChannel];
            double from = Math.max(gate.gate().projectStart(), span.seconds(span.start(), TimeBase.project));
            double to = Math.min(gate.gate().projectEnd(), span.seconds(span.end(), TimeBase.project));
            var points = new ArrayList<Point>();
            double reference = fmChannel >= 0
                    ? Math.scalb(LeadEffectMatcher.fmFnum(gate.pitch()), (gate.pitch() - 12) / 12)
                    : ReconstructionLeadAudio.hz(gate.pitch());
            for (int tick = (int) Math.floor(from * 60 + 1e-8); tick < to * 60 - 1e-8 && tick < lane.length; tick++) {
                if ((lane[tick] & 0x7ff) == 0) continue;
                double frequency = fmChannel >= 0 ? Math.scalb(lane[tick] & 0x7ff, lane[tick] >> 11 & 7)
                        : PsgChip.TICK_RATE_HZ / (2.0 * lane[tick]);
                double hzRatio = frequency / reference;
                double pitch = gate.pitch() + 12 * Math.log(hzRatio) / Math.log(2);
                double time = Math.max(from, tick / 60.0);
                points.add(point(span, span.base() == TimeBase.project ? time : span.timeline().source(time), pitch));
            }
            if (!points.isEmpty()) {
                double time = span.base() == TimeBase.project ? to : span.timeline().source(to);
                points.add(point(span, time, points.getLast().pitch()));
                curves.add(new Curve(gate.id(), gate.lane(), "assembled", points));
            }
        }
        return curves;
    }

    static double pitchAt(Curve curve, double time) {
        var points = curve.points();
        if (time <= points.getFirst().time()) return points.getFirst().pitch();
        for (int i = 1; i < points.size(); i++) {
            var next = points.get(i);
            if (time <= next.time()) {
                var previous = points.get(i - 1);
                double fraction = (time - previous.time()) / (next.time() - previous.time());
                return previous.pitch() + fraction * (next.pitch() - previous.pitch());
            }
        }
        return points.getLast().pitch();
    }

    private static Point point(Span span, double time, double pitch) {
        return new Point(time, span.seconds(time, TimeBase.source), span.seconds(time, TimeBase.project), pitch);
    }
}
