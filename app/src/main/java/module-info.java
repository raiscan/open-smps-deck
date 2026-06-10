module com.opensmpsdeck {
    requires com.opensmps.synthcore;
    requires javafx.controls;
    requires javafx.graphics;
    requires com.google.gson;
    requires java.logging;
    requires java.xml;
    requires java.desktop;
    requires java.prefs;

    opens com.opensmpsdeck to javafx.graphics;

    exports com.opensmpsdeck;
}
