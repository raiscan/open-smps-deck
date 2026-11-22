package com.opensmpsdeck.studio;

import javafx.scene.input.KeyEvent;

final class TransposeKeys {
    private TransposeKeys() {}

    static int semitones(KeyEvent event) {
        if (event.isControlDown() || event.isMetaDown() || event.isAltDown()) return 0;
        int step = event.isShiftDown() ? 12 : 1;
        return switch (event.getCode()) {
            case PLUS, EQUALS, ADD -> step;
            case MINUS, UNDERSCORE, SUBTRACT -> -step;
            default -> 0;
        };
    }
}
