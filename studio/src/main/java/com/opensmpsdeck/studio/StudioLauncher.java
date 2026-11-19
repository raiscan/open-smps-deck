package com.opensmpsdeck.studio;

/** Non-Application entry point lets the packaged JavaFX runtime start normally. */
public final class StudioLauncher {
    private StudioLauncher() {}
    public static void main(String[] args) { DesktopScale.configure(); javafx.application.Application.launch(StudioApplication.class,args); }
}
