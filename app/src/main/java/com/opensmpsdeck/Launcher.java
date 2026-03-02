package com.opensmpsdeck;

/**
 * Non-JavaFX launcher that avoids the "JavaFX runtime components are missing" error
 * when running from IDEs or plain {@code java -jar} without module-path configuration.
 */
public class Launcher {

    public static void main(String[] args) {
        OpenSmpsDeck.main(args);
    }
}
