package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.*;
import javafx.application.Application;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import java.nio.file.*;

public final class StudioApplication extends Application {
    private StudioWindow window;
    @Override public void start(Stage stage) {
        try {
            Path path;
            if(getParameters().getRaw().isEmpty()) {
                path=Path.of(System.getProperty("user.home"),".local","share","opensmpsdeck","studio-demo.smps.json");
                Files.createDirectories(path.getParent());
                if(!Files.exists(path))ProjectFiles.save(path,DemoProject.create(),null);
            } else path=Path.of(getParameters().getRaw().getFirst());
            window=new StudioWindow(stage,path);stage.show();
        } catch(Exception e) {
            Alert alert=new Alert(Alert.AlertType.ERROR,"Could not open the project: "+e.getMessage());alert.setHeaderText("OpenSMPS Studio");alert.showAndWait();javafx.application.Platform.exit();
        }
    }
    @Override public void stop(){if(window!=null)window.close();}
}
