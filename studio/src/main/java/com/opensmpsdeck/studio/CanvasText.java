package com.opensmpsdeck.studio;

import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.util.LinkedHashMap;
import java.util.Map;

/** Measures and shortens canvas labels, so they fit without a canvas clip. */
final class CanvasText {
    private static final Text MEASURE = new Text();
    private static final Map<Font, Map<String, Double>> WIDTHS = new LinkedHashMap<>();

    private CanvasText() {}

    static double width(String text, Font font) {
        var widths = WIDTHS.computeIfAbsent(font, ignored -> new LinkedHashMap<>(256, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Double> eldest) { return size() > 2048; }
        });
        var known = widths.get(text);
        if (known != null) return known;
        MEASURE.setFont(font);
        MEASURE.setText(text);
        double measured = MEASURE.getLayoutBounds().getWidth();
        widths.put(text, measured);
        return measured;
    }

    /** The text, or its longest prefix with an ellipsis, that fits in the width. */
    static String fit(String text, Font font, double width) {
        if (width <= 0 || text.isEmpty()) return "";
        if (width(text, font) <= width) return text;
        int low = 0, high = text.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (width(text.substring(0, middle).stripTrailing() + "…", font) <= width) low = middle;
            else high = middle - 1;
        }
        return low == 0 ? "" : text.substring(0, low).stripTrailing() + "…";
    }
}
