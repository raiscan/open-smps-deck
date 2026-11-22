package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.*;
import javafx.application.Application;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import java.nio.file.*;
import java.util.List;

public final class StudioApplication extends Application {
    private StudioWindow window;
    @Override public void start(Stage stage) {
        try {
            window=open(stage,getParameters().getRaw());
        } catch(Exception e) {
            Alert alert=new Alert(Alert.AlertType.ERROR,"Could not open the project: "+e.getMessage());alert.setHeaderText("OpenSMPS Studio");alert.showAndWait();javafx.application.Platform.exit();
        }
    }
    static StudioWindow open(Stage stage,List<String> args) throws java.io.IOException {
        Path path;
        if(args.isEmpty()) {
            path=Path.of(System.getProperty("user.home"),".local","share","opensmpsdeck","studio-demo.smps.json");
            Files.createDirectories(path.getParent());
            if(!Files.exists(path))ProjectFiles.save(path,DemoProject.create(),null);
        } else path=Path.of(args.getFirst());
        var studio=new StudioWindow(stage,path);
        stage.show();
        return studio;
    }
    @Override public void stop(){if(window!=null)window.close();}
}
