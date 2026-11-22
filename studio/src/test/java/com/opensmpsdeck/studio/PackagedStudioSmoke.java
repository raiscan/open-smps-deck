package com.opensmpsdeck.studio;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone verifier loaded against the finished desktop JAR, not reactor classes. */
public final class PackagedStudioSmoke {
    public static void main(String[] args) throws Exception {
        for(String resource:new String[]{"/stock/s1.json","/stock/s2.json","/stock/s3k.json","/stock/s1-dac-rates.txt",
                "/stock/s2-dac-rates.txt","/stock/s3k-dac-rates.txt","/spdx-identifiers.txt"})
            if(PackagedStudioSmoke.class.getResource(resource)==null)
                throw new AssertionError("Packaged Studio is missing headless resource "+resource);
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        Platform.startup(()->{
            try {
                Stage stage=new Stage();StudioWindow window=new StudioWindow(stage,Path.of(args[0]));stage.show();
                PauseTransition wait=new PauseTransition(Duration.millis(300));
                wait.setOnFinished(event->{
                    try {
                        WritableImage image=stage.getScene().snapshot(null);
                        BufferedImage png=new BufferedImage((int)image.getWidth(),(int)image.getHeight(),BufferedImage.TYPE_INT_ARGB);
                        for(int y=0;y<png.getHeight();y++)for(int x=0;x<png.getWidth();x++)png.setRGB(x,y,image.getPixelReader().getArgb(x,y));
                        ImageIO.write(png,"png",Path.of(args[1]).toFile());
                        System.out.println("Packaged Studio opened shared project: "+stage.getTitle());
                    }catch(Throwable error){failure.set(error);}
                    finally{window.close();stage.close();done.countDown();Platform.exit();}
                });wait.play();
            }catch(Throwable error){failure.set(error);done.countDown();Platform.exit();}
        });
        if(!done.await(15,TimeUnit.SECONDS)){Platform.exit();throw new AssertionError("Packaged UI did not finish");}
        if(failure.get()!=null)throw new AssertionError("Packaged UI failed",failure.get());
    }
}
