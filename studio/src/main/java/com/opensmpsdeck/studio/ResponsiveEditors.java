package com.opensmpsdeck.studio;

import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;

/** Keep each editor usable when native DPI leaves a short logical work area. */
final class ResponsiveEditors extends StackPane {
    private final Node arrangement,notes;
    private final SplitPane split=new SplitPane();
    private final Tab arrangementTab=new Tab("Arrangement"),notesTab=new Tab("Notes");
    private final TabPane tabs=new TabPane(arrangementTab,notesTab);
    private boolean compact;
    ResponsiveEditors(Node arrangement,Node notes) {
        this.arrangement=arrangement;this.notes=notes;
        arrangementTab.setClosable(false);notesTab.setClosable(false);
        split.setOrientation(Orientation.VERTICAL);split.getItems().setAll(arrangement,notes);split.setDividerPositions(.59);
        getChildren().setAll(split);
    }
    void setCompact(boolean value) {
        if(value==compact)return;
        compact=value;
        if(value){split.getItems().clear();arrangementTab.setContent(arrangement);notesTab.setContent(notes);getChildren().setAll(tabs);}
        else{arrangementTab.setContent(null);notesTab.setContent(null);split.getItems().setAll(arrangement,notes);getChildren().setAll(split);}
    }
    void showNotes() {if(compact)tabs.getSelectionModel().select(notesTab);else split.setDividerPositions(.30);}
}
