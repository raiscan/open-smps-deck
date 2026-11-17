package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.*;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Linked native envelope assets. Form drafts are applied explicitly against their captured asset. */
final class EnvelopeEditorPane extends BorderPane {
    private final Consumer<UnaryOperator<StudioProject>> edit;
    private final Consumer<String> error;
    private final ListView<Envelope> list=new ListView<>();
    private final TextField name=new TextField();
    private final TextArea bytes=new TextArea();
    private final Label description=label("Choose or create an envelope","section"),notice=label("","muted"),usage=label("","muted");
    private final VBox form=new VBox(10);
    private StudioProject project;
    private Envelope baseline;
    private String selectedId;
    private boolean updating;

    EnvelopeEditorPane(Consumer<UnaryOperator<StudioProject>> edit,Consumer<String> error) {
        this.edit=Objects.requireNonNull(edit);this.error=Objects.requireNonNull(error);
        list.setId("envelope-list");list.setPrefHeight(150);list.setMinHeight(90);
        list.setCellFactory(v->new ListCell<>(){@Override protected void updateItem(Envelope value,boolean empty){super.updateItem(value,empty);setText(null);setGraphic(empty||value==null?null:new VBox(4,label(value.name(),"sequence-name"),label(value.type(),"muted")));}});
        list.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{if(!updating&&b!=null)load(b);});
        name.setId("envelope-name");bytes.setId("envelope-bytes");bytes.setPrefRowCount(3);bytes.setWrapText(true);
        name.textProperty().addListener((o,a,b)->{if(!updating)refreshNotice();});bytes.textProperty().addListener((o,a,b)->{if(!updating)refreshNotice();});
        FlowPane actions=new FlowPane(8,8,button("New PSG envelope","add-psg-envelope",()->create("PSG_VOLUME")),button("New modulation","add-mod-envelope",()->create("MODULATION")),button("Duplicate","duplicate-envelope",this::duplicate),button("Delete unused","delete-envelope",this::deleteSelected));
        form.getChildren().addAll(description,usage,label("Asset name","muted"),name,label("Native bytes (hex)","muted"),bytes,new FlowPane(8,8,button("Apply envelope","apply-envelope",this::apply),button("Reload envelope","reload-envelope",this::reload)),notice,
                label("PSG data: 00–0F attenuation. Modulation data: 00–7F frequency offsets. 80 resets; 81 and 83 rest/hold (PSG 83 silences). End explicitly in 80 / 81 / 83. Branches, multiplier changes and negative modulation offsets await driver support.","help"),
                label("Envelopes are linked project assets. Editing an asset changes every assigned source. Native envelope steps advance with the driver; these bytes are not piano-roll duration ticks.","muted"));
        VBox inventory=new VBox(10,label("NATIVE ENVELOPES","section"),list,actions);inventory.setMinWidth(150);inventory.setPrefWidth(200);inventory.setMaxWidth(200);
        form.setMinWidth(240);HBox.setHgrow(form,Priority.ALWAYS);
        HBox contents=new HBox(12,inventory,form);contents.setPadding(new Insets(12));contents.setMinWidth(0);
        ScrollPane scroll=new ScrollPane(contents);scroll.setFitToWidth(true);scroll.getStyleClass().add("editor-scroll");setCenter(scroll);setMinSize(0,0);
        setProject(null);
    }
    void setProject(StudioProject next) {
        boolean initial=project==null;
        boolean draft=draftChanged();
        updating=true;
        try {
            project=next;list.getSelectionModel().clearSelection();list.getItems().setAll(next==null?List.of():next.envelopes());
            if(next==null){selectedId=null;baseline=null;name.clear();bytes.clear();}
            else if(initial&&selectedId==null&&!next.envelopes().isEmpty()){fill(next.envelopes().getFirst());}
            Envelope current=find(selectedId);
            if(current!=null){list.getSelectionModel().select(current);if(!draft)fill(current);}
            form.setDisable(baseline==null);
            usage.setText(baseline==null?"":uses(selectedId)+" linked source(s) · "+selectedId);
            refreshNotice();
        } finally {updating=false;}
    }
    String selectedEnvelopeId(){return selectedId;}
    void selectEnvelope(String id){Envelope current=find(id);if(current!=null){updating=true;try{list.getSelectionModel().select(current);fill(current);}finally{updating=false;}refreshNotice();}}
    boolean ownsFocus(Node node){for(;node!=null;node=node.getParent())if(node==this)return true;return false;}
    void deleteSelected(){
        if(baseline==null)return;
        Envelope expected=baseline;
        submit(p->ProjectEdits.deleteEnvelope(guardEnvelope(p,expected),expected.id()));
    }
    private void load(Envelope envelope){updating=true;try{fill(envelope);}finally{updating=false;}refreshNotice();}
    private void fill(Envelope envelope){baseline=envelope;selectedId=envelope.id();name.setText(envelope.name());bytes.setText(CommandEditorPane.hexArguments(envelope.bytes()));description.setText(envelope.type()+" · NATIVE BYTES");form.setDisable(false);usage.setText(uses(selectedId)+" linked source(s) · "+selectedId);}
    private void create(String type){
        if(project==null)return;
        String id="envelope-"+UUID.randomUUID();
        Envelope value=new Envelope(id,type.equals("PSG_VOLUME")?"New PSG envelope":"New modulation envelope",type,type.equals("PSG_VOLUME")?List.of(0,2,4,6,8,10,12,15,0x83):List.of(0,1,2,3,2,1,0,0x81));
        submit(p->ProjectEdits.addEnvelope(p,value));selectEnvelope(id);
    }
    private void duplicate(){
        Envelope current=find(selectedId);if(current==null)return;
        Envelope copy=new Envelope("envelope-"+UUID.randomUUID(),current.name()+" copy",current.type(),current.bytes());
        submit(p->ProjectEdits.addEnvelope(p,copy));selectEnvelope(copy.id());
    }
    private void apply(){
        if(baseline==null)return;
        Envelope expected=baseline;
        try {
            Envelope replacement=new Envelope(expected.id(),name.getText(),expected.type(),CommandEditorPane.parseHexArguments(bytes.getText()));
            submit(p->ProjectEdits.updateEnvelope(guardEnvelope(p,expected),replacement));
            if(replacement.equals(find(expected.id())))selectEnvelope(expected.id());
        }catch(IllegalArgumentException failure){error.accept(failure.getMessage());}
    }
    private void reload(){Envelope current=find(selectedId);if(current==null)error.accept("That envelope was removed. Choose another asset or create a new one; your draft is retained.");else selectEnvelope(current.id());}
    private void submit(UnaryOperator<StudioProject> operation){try{edit.accept(operation);}catch(IllegalArgumentException failure){error.accept(failure.getMessage());}}
    private Envelope find(String id){return project==null||id==null?null:project.envelopes().stream().filter(e->e.id().equals(id)).findFirst().orElse(null);}
    private long uses(String id){return project==null?0:project.sequences().stream().filter(s->Objects.equals(s.psgEnvelopeId(),id)||Objects.equals(s.modulationEnvelopeId(),id)).count();}
    private boolean draftChanged(){return baseline!=null&&(!name.getText().equals(baseline.name())||!bytes.getText().trim().equalsIgnoreCase(CommandEditorPane.hexArguments(baseline.bytes())));}
    private void refreshNotice(){
        Envelope current=find(selectedId);
        notice.setText(baseline==null?"Choose an asset or create one.":current==null?"Asset removed. Your draft is retained and cannot recreate it; choose another asset or create a new one.":!current.equals(baseline)?"Asset changed externally. Your draft is retained; Reload envelope before applying.":draftChanged()?"Unapplied envelope draft · Apply envelope commits changes.":"Edits use the shared project’s undo, Save and live refresh.");
    }
    static StudioProject guardEnvelope(StudioProject project,Envelope expected){
        Envelope current=project.envelopes().stream().filter(e->e.id().equals(expected.id())).findFirst().orElse(null);
        if(!expected.equals(current))throw new IllegalArgumentException("Envelope changed or was removed while this form was open. Your draft is retained; reload before applying.");
        return project;
    }
    private static Button button(String text,String id,Runnable action){Button b=new Button(text);b.setId(id);b.setOnAction(e->action.run());return b;}
    private static Label label(String text,String style){Label l=new Label(text);l.getStyleClass().add(style);l.setWrapText(true);l.setMinWidth(0);return l;}
}
