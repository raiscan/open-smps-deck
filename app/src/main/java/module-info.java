module com.opensmps.deck {
    requires com.opensmps.synthcore;
    requires javafx.controls;
    requires javafx.graphics;
    requires com.google.gson;

    opens com.opensmps.deck to javafx.graphics;

    exports com.opensmps.deck;
}
