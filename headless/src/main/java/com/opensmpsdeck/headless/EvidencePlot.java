package com.opensmpsdeck.headless;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.opensmpsdeck.headless.ReconstructionTimeMap.TimeBase;
import static com.opensmpsdeck.headless.ReconstructionView.*;

final class EvidencePlot {
    private static final int LEFT = 94, RIGHT = 26, TOP = 92, ROW = 30;
    private static final int INK = 0xff18212a;
    private static final Color SUNO = new Color(0x75e1ff), EXTERNAL = new Color(0xf4e5ab);
    private static final Color EDIT = new Color(0xe2aaff), WARNING = new Color(0xff8377);
    private static final Color HARMONIC = new Color(0xffc064);
    private static final Color[] CHANNELS = {
            new Color(0x64e9a3), new Color(0x73a8ff), new Color(0xed90c3),
            new Color(0xf5bc6b), new Color(0xbca7ff), new Color(0x81d9d4),
            new Color(0xe1e883), new Color(0xffad92), new Color(0xc5ced8)
    };

    private final Span span;
    private final List<String> lanes;
    private final int minPitch, maxPitch;
    private final Plot geometry;
    private final BufferedImage image;
    private final Graphics2D g;

    /** Which lanes the chip panel plays: the full mix, or the solo set from --lanes. */
    String renderLabel = "Full mix";

    EvidencePlot(int width, Span span, int minPitch, int maxPitch, boolean render, List<String> lanes) {
        this.span = span;
        this.lanes = lanes;
        this.minPitch = minPitch;
        this.maxPitch = maxPitch;
        int pitchHeight = Math.max(186, (maxPitch - minPitch + 1) * 14);
        Integer renderTop = render ? TOP + pitchHeight + 39 : null;
        int laneTop = (render ? renderTop : TOP) + pitchHeight + 44;
        geometry = new Plot(LEFT, width - LEFT - RIGHT, TOP, renderTop, pitchHeight, laneTop);
        image = new BufferedImage(width, laneTop + lanes.size() * ROW + 66, BufferedImage.TYPE_INT_RGB);
        g = image.createGraphics();
    }

    BufferedImage image() { return image; }
    Plot geometry() { return geometry; }

    void draw(String source, EvidenceSpectrum.Spectrum audio, EvidenceSpectrum.Spectrum render,
              List<DrawnNote> notes, DrawnNote target, List<Harmonic> harmonics, List<HarmonicGuide> guides,
              boolean emphasize, Levels levels, List<EvidenceNotes.PatchChange> patchChanges, boolean projected) {
        try {
            g.setColor(new Color(0xf2f4f6));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            text("Reconstruction evidence", LEFT, 29, INK, Font.BOLD, 22);
            key();
            String analysis = emphasize ? "  ·  Harmonic sum" : "";
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            String scale = levels == null ? "Top 30 dB" + analysis : levelLabel(levels.sourcePeakDbfs(), levels.shared());
            int scaleWidth = g.getFontMetrics().stringWidth(scale);
            int reserve = levels == null ? 210 : scaleWidth + 18;
            String sourceLabel = fit("Source  ·  " + source + (levels == null ? "" : analysis), geometry.width() - reserve);
            text(sourceLabel, LEFT, TOP - 10, INK, Font.BOLD, 14);
            if (target != null) {
                String label = "Harmonics of " + target.pitchName() + " (" + target.label() + ")";
                int width = g.getFontMetrics().stringWidth(label);
                text(label, LEFT + geometry.width() - width, 28, INK, Font.BOLD, 14);
            }
            var metrics = g.getFontMetrics();
            text(scale, LEFT + geometry.width() - metrics.stringWidth(scale), TOP - 10, INK, Font.PLAIN, 14);

            var ticks = ticks();
            boolean shared = levels != null && levels.shared() && render != null;
            double ceiling = shared ? Math.max(audio.peak(), render.peak()) : audio.peak();
            spectrogram(audio.image(ceiling), TOP, ticks);
            drawHarmonics(TOP, target, harmonics, guides);
            drawOverlays(notes);
            if (render != null) {
                int top = geometry.renderTop();
                String label = "Chip render  ·  " + renderLabel + "  ·  "
                        + (levels == null ? "Top 30 dB" : levelLabel(levels.renderPeakDbfs(), levels.shared()));
                text(label, LEFT, top - 10, INK, Font.BOLD, 14);
                spectrogram(render.image(shared ? ceiling : render.peak()), top, ticks);
                drawHarmonics(top, target, harmonics, guides);
            }
            drawLanes(notes, ticks, patchChanges, projected);
            axis(ticks);
        } finally {
            g.dispose();
        }
    }

    private void key() {
        int x = LEFT;
        String[] labels = {"Suno MIDI", "External", "Inserted", "Dropped / merged", "Voiced lane"};
        Color[] colors = {SUNO, EXTERNAL, EDIT, WARNING, CHANNELS[0]};
        for (int i = 0; i < labels.length; i++) {
            g.setColor(new Color(0x252e38));
            g.fillRoundRect(x, 42, 27, 14, 4, 4);
            g.setColor(colors[i]);
            g.setStroke(stroke(i == 3, i == 1 ? new float[]{2, 3} : i == 2 ? new float[]{6, 3, 2, 3} : null));
            if (i == 4) g.fillRect(x + 4, 48, 19, 3);
            else g.drawRect(x + 4, 46, 19, 6);
            g.setStroke(new BasicStroke(1));
            text(labels[i], x + 33, 54, INK, Font.PLAIN, 12);
            x += 45 + g.getFontMetrics().stringWidth(labels[i]);
        }
    }

    private static String levelLabel(Double peakDbfs, boolean shared) {
        String peak = peakDbfs == null ? "silent" : String.format(Locale.ROOT, "%.1f dBFS", peakDbfs);
        return (shared ? "Shared 30 dB" : "Top 30 dB") + "  ·  Peak " + peak;
    }

    EvidenceSpectrum.Spectrum measure(EvidenceSpectrum.Audio audio, TimeBase base, boolean emphasize) {
        double hop = Math.clamp(span.length() / 1200, .003, .050);
        int columns = Math.min(geometry.width(), Math.max(2, 1 + (int) Math.ceil(span.length() / hop)));
        var times = new double[columns];
        for (int x = 0; x < columns; x++)
            times[x] = span.seconds(span.start() + (x + .5) * span.length() / columns, base);
        return EvidenceSpectrum.measure(audio, times, minPitch, maxPitch, geometry.pitchHeight(), span.length(), emphasize);
    }

    private void spectrogram(BufferedImage spectrum, int top, List<Double> ticks) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(spectrum, LEFT, top, geometry.width(), geometry.pitchHeight(), null);
        grid(ticks, top, geometry.pitchHeight(), new Color(255, 255, 255, 43));
        for (int pitch = minPitch; pitch <= maxPitch; pitch++) {
            int y = y(pitch, top);
            g.setColor(new Color(255, 255, 255, pitch % 12 == 0 ? 64 : 29));
            g.drawLine(LEFT, y, LEFT + geometry.width() - 1, y);
            text(ReconstructionDescribe.pitch(pitch), LEFT - 41, y + 4, INK,
                    pitch % 12 == 0 ? Font.BOLD : Font.PLAIN, 11);
        }
    }

    private void drawOverlays(List<DrawnNote> notes) {
        g.setClip(LEFT, TOP, geometry.width(), geometry.pitchHeight());
        for (Layer layer : Layer.values()) {
            for (var note : notes) {
                if (note.layer() != layer || note.pitch() < minPitch || note.pitch() > maxPitch) continue;
                int from = x(span.start(note.timing())), to = x(span.end(note.timing()));
                int width = Math.max(1, to - from);
                int y = y(note.pitch(), TOP);
                if (layer == Layer.lane) {
                    g.setColor(laneColor(note.lane()));
                    g.fillRect(from, y - 1, width, 3);
                } else {
                    g.setColor(outline(note));
                    g.setStroke(stroke(note.warning(), layer == Layer.external ? new float[]{2, 3}
                            : layer == Layer.edit ? new float[]{6, 3, 2, 3} : null));
                    int radius = layer == Layer.suno ? 5 : layer == Layer.external ? 3 : 6;
                    g.drawLine(from, y - radius, from + width, y - radius);
                    g.drawLine(from, y + radius, from + width, y + radius);
                    if (span.start(note.timing()) >= span.start()) g.drawLine(from, y - radius, from, y + radius);
                    if (span.end(note.timing()) <= span.end()) g.drawLine(to, y - radius, to, y + radius);
                    g.setStroke(new BasicStroke(1));
                }
            }
        }
        var labeled = new java.util.HashSet<String>();
        var occupied = new java.util.HashMap<Integer, List<Rectangle>>();
        for (var note : notes) {
            if (note.pitch() < minPitch || note.pitch() > maxPitch || labeled.contains(note.id())) continue;
            int from = x(span.start(note.timing())), to = x(span.end(note.timing()));
            int y = y(note.pitch(), TOP);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 10));
            int labelWidth = g.getFontMetrics().stringWidth(note.label());
            if (to - from < labelWidth + 14) continue;
            var bounds = new Rectangle(from + 4, y - 6, labelWidth + 7, 12);
            var row = occupied.computeIfAbsent(note.pitch(), ignored -> new ArrayList<>());
            if (row.stream().anyMatch(bounds::intersects)) continue;
            g.setColor(new Color(17, 22, 27, 229));
            g.fillRoundRect(bounds.x, bounds.y, bounds.width - 2, bounds.height, 4, 4);
            g.setColor(note.layer() == Layer.lane ? laneColor(note.lane()) : outline(note));
            g.drawString(note.label(), from + 6, y + 4);
            row.add(bounds);
            labeled.add(note.id());
        }
        g.setClip(null);
    }

    private static Color outline(DrawnNote note) {
        if (note.warning()) return WARNING;
        return switch (note.layer()) {
            case suno -> SUNO;
            case external -> EXTERNAL;
            case edit -> EDIT;
            case trace -> Color.LIGHT_GRAY;
            case lane -> laneColor(note.lane());
        };
    }

    private void drawHarmonics(int top, DrawnNote target, List<Harmonic> harmonics, List<HarmonicGuide> guides) {
        if (target != null)
            harmonics(top, x(span.start(target.timing())), x(span.end(target.timing())), null, harmonics);
        for (var guide : guides)
            harmonics(top, x(guide.start()), x(guide.end()), guide.at() == null ? null : x(guide.at()), guide.partials());
    }

    private void harmonics(int top, int from, int to, Integer marker, List<Harmonic> harmonics) {
        g.setClip(LEFT, top, geometry.width(), geometry.pitchHeight());
        for (var harmonic : harmonics) {
            int y = y(harmonic.pitch(), top);
            g.setColor(HARMONIC);
            g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{5, 4}, 0));
            g.drawLine(from, y, to, y);
            g.setStroke(new BasicStroke(1));
            int tick = marker == null ? from : marker;
            g.drawLine(tick, y - 3, tick, y + 3);
            if (to - from > 64 && (harmonic.multiple() == 1 || harmonics.size() <= 12)) {
                String label = harmonic.multiple() + "×";
                g.setColor(new Color(0x2c2419));
                g.fillRect(to - 28, y - 15, 26, 13);
                text(label, to - 25, y - 5, HARMONIC.getRGB(), Font.BOLD, 10);
            }
        }
        g.setClip(null);
    }

    private void drawLanes(List<DrawnNote> notes, List<Double> ticks, List<EvidenceNotes.PatchChange> changes,
                           boolean projected) {
        int top = geometry.laneTop();
        text(projected ? "Lane gates  ·  Includes gates projected from stage artifacts" : "Lane gates",
                LEFT, top - 11, INK, Font.BOLD, 14);
        for (int i = 0; i < lanes.size(); i++) {
            String lane = lanes.get(i);
            int y = top + ROW * i;
            g.setColor(new Color(i % 2 == 0 ? 0x222c36 : 0x28333f));
            g.fillRect(LEFT, y, geometry.width(), ROW);
            text(lane, LEFT - 53, y + 19, INK, Font.BOLD, 12);
        }
        grid(ticks, top, lanes.size() * ROW, new Color(255, 255, 255, 53));
        g.setClip(LEFT, top, geometry.width(), lanes.size() * ROW);
        for (var note : notes) {
            if (note.layer() != Layer.lane) continue;
            int y = top + ROW * lanes.indexOf(note.lane());
            int from = x(span.start(note.timing())), to = x(span.end(note.timing()));
            g.setColor(laneColor(note.lane()));
            g.fillRect(from, y + 18, Math.max(1, to - from), 7);
        }
        // A one-pixel separator makes successive gates visible even when
        // their boundary is shared and the instrument never changes.
        g.setColor(new Color(0x24303a));
        for (var note : notes) {
            if (note.layer() != Layer.lane || span.start(note.timing()) <= span.start()) continue;
            int y = top + ROW * lanes.indexOf(note.lane());
            int x = x(span.start(note.timing()));
            g.drawLine(x, y + 18, x, y + 24);
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        for (var lane : lanes) {
            var events = changes.stream().filter(change -> change.lane().equals(lane)).toList();
            for (int i = 0; i < events.size(); i++) {
                var event = events.get(i);
                int from = x(event.time()), to = i + 1 == events.size() ? LEFT + geometry.width() : x(events.get(i + 1).time());
                int y = top + ROW * lanes.indexOf(lane);
                g.setColor(laneColor(lane));
                g.drawLine(from, y + 3, from, y + 13);
                String label = fit(event.name(), to - from - 6);
                text(label, from + 3, y + 13, laneColor(lane).getRGB(), Font.PLAIN, 11);
            }
        }
        g.setClip(null);
    }

    private static Color laneColor(String lane) { return CHANNELS[LANES.indexOf(lane)]; }

    private List<Double> ticks() {
        double ideal = span.length() / Math.max(4, geometry.width() / 115);
        double decade = Math.pow(10, Math.floor(Math.log10(ideal)));
        double ratio = ideal / decade;
        double step = decade * (ratio <= 1 ? 1 : ratio <= 2 ? 2 : ratio <= 2.5 ? 2.5 : ratio <= 5 ? 5 : 10);
        var ticks = new ArrayList<Double>();
        ticks.add(span.start());
        for (double time = Math.ceil(span.start() / step) * step; time < span.end(); time += step)
            if (x(time) - LEFT >= 65 && LEFT + geometry.width() - x(time) >= 65) ticks.add(time);
        ticks.add(span.end());
        return ticks;
    }

    private void grid(List<Double> ticks, int top, int height, Color color) {
        g.setColor(color);
        for (double time : ticks) {
            int x = Math.min(LEFT + geometry.width() - 1, x(time));
            g.drawLine(x, top, x, top + height - 1);
        }
    }

    private void axis(List<Double> ticks) {
        int y = geometry.laneTop() + lanes.size() * ROW + 19;
        if (span.timeline().warped()) {
            axisRow(ticks, TimeBase.source, y);
            axisRow(ticks, TimeBase.project, y + 18);
        } else {
            axisRow(ticks, span.base(), y);
        }
    }

    private void axisRow(List<Double> ticks, TimeBase base, int y) {
        text(base == TimeBase.source ? "Source s" : "Project s", LEFT - 76, y, INK, Font.BOLD, 11);
        int decimals = (int) Math.clamp(2 - Math.floor(Math.log10(span.length())), 2, 7);
        for (double time : ticks) {
            String label = String.format(Locale.ROOT, "%." + decimals + "f", span.seconds(time, base));
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            int size = g.getFontMetrics().stringWidth(label);
            text(label, Math.clamp(x(time) - size / 2, LEFT, LEFT + geometry.width() - size),
                    y, INK, Font.PLAIN, 11);
        }
    }

    private int x(double time) {
        return LEFT + (int) Math.round(Math.clamp((time - span.start()) / span.length(), 0, 1) * geometry.width());
    }

    private int y(double pitch, int top) {
        return top + (int) Math.round((maxPitch + .5 - pitch) * geometry.pitchHeight() / (maxPitch - minPitch + 1));
    }

    private static BasicStroke stroke(boolean warning, float[] pattern) {
        float[] dash = warning && pattern == null ? new float[]{5, 3} : pattern;
        return new BasicStroke(warning ? 1.6f : 1.25f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, dash, 0);
    }

    private String fit(String label, int width) {
        var metrics = g.getFontMetrics();
        if (metrics.stringWidth(label) <= width) return label;
        if (width < 21) return "";
        int length = label.length();
        while (length > 0 && metrics.stringWidth(label.substring(0, length) + "…") > width) length--;
        return label.substring(0, length) + "…";
    }

    private void text(String text, int x, int y, int rgb, int style, int size) {
        g.setFont(new Font(Font.SANS_SERIF, style, size));
        g.setColor(new Color(rgb));
        g.drawString(text, x, y);
    }
}
