package com.opensmpsdeck.studio;

import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.NativeCommand;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.robot.Robot;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.*;

/** Real native pointer events against the packaged editor, on an isolated display. */
public final class DpiStudioSmoke {
    private static StudioWindow window;private static Stage stage;private static Robot robot;
    public static void main(String[] args) throws Exception {
        DesktopScale.configure();
        CountDownLatch ready=new CountDownLatch(1);Platform.startup(()->{Platform.setImplicitExit(false);ready.countDown();});
        if(!ready.await(10,TimeUnit.SECONDS))throw new AssertionError("toolkit startup timed out");
        try {
            fx(()->{
                stage=new Stage();window=new StudioWindow(stage,Path.of(args[1]));stage.show();stage.requestFocus();robot=new Robot();layout();
                double expected=Double.parseDouble(args[0]);
                check(Math.abs(stage.getOutputScaleX()-expected)<.03,"native scale "+stage.getOutputScaleX()+", expected "+expected);
                var area=Screen.getPrimary().getVisualBounds();
                check(stage.getWidth()<=area.getWidth()+1&&stage.getHeight()<=area.getHeight()+1,"window exceeds logical work area");
                Point2D a=window.timeline.canvas.localToScene(0,0),b=window.timeline.canvas.localToScene(100,0);
                check(Math.abs(b.getX()-a.getX()-100)<.01,"native DPI was applied a second time to scene geometry");
                return null;
            });
            pause();
            Point2D start=fx(()->window.timeline.canvas.localToScreen(TimelineView.GUTTER+20,TimelineView.RULER+20));
            click(start);check(fx(()->"clip-a1".equals(window.selectedClip())),"native clip selection failed");
            fx(()->{robot.mouseMove(start);robot.mousePress(MouseButton.PRIMARY);return null;});pause();
            Point2D end=fx(()->window.timeline.canvas.localToScreen(TimelineView.GUTTER+20+18*window.timeline.scale,TimelineView.RULER+TimelineView.ROW+20));
            fx(()->{robot.mouseMove(end);return null;});pause();fx(()->{robot.mouseRelease(MouseButton.PRIMARY);return null;});pause();
            check(fx(()->window.document().project().clips().getFirst().lane().equals("FM2")&&window.document().project().clips().getFirst().startTick()==18),"native scaled clip drag failed");
            // Add into the final six-tick rest after horizontally scrolling the source.
            reveal(80);Point2D notePoint=fx(()->notePoint(90,80,3));click(notePoint);click(notePoint);
            check(fx(()->window.document().project().sequences().getFirst().notes().size()==7),"native double-click note entry failed");
            // Extend the new note from three to six ticks using its physical handle.
            reveal(80);Point2D handle=fx(()->notePoint(93,80,-3));
            fx(()->{robot.mouseMove(handle);robot.mousePress(MouseButton.PRIMARY);return null;});pause();
            Point2D longer=fx(()->notePoint(96,80,-3));fx(()->{robot.mouseMove(longer);return null;});pause();fx(()->{robot.mouseRelease(MouseButton.PRIMARY);return null;});pause();
            check(fx(()->window.document().project().sequences().getFirst().notes().stream().anyMatch(n->n.tick()==90&&n.duration()==6)),"native scaled note resize failed");
            Button save=fx(()->(Button)window.scene().getRoot().lookupAll(".button").stream().filter(n->n instanceof Button b&&b.getText().equals("Save")).findFirst().orElseThrow());
            check(fx(()->save.getFont().getSize()>=14),"control text is undersized");click(fx(()->save.localToScreen(save.getWidth()/2,save.getHeight()/2)));
            check(!fx(()->window.document().dirty()),"native Save click failed");
            check(ProjectFiles.load(Path.of(args[1])).project().sequences().getFirst().notes().size()==7,"saved pointer edit not in shared file");
            // Commands must remain reachable through the same scaled native UI.
            fx(()->{
                window.showCommands();layout();
                @SuppressWarnings("unchecked") ComboBox<NativeCommand> type=(ComboBox<NativeCommand>)window.scene().lookup("#command-type");
                type.setValue(NativeCommand.PAN);
                ((TextField)window.scene().lookup("#command-tick")).setText("6");
                ((TextField)window.scene().lookup("#command-bytes")).setText("80");
                ScrollPane scroll=(ScrollPane)window.commands.getCenter();
                Node add=window.scene().lookup("#add-command");
                var bounds=scroll.getContent().sceneToLocal(add.localToScene(add.getBoundsInLocal()));
                scroll.setVvalue(Math.clamp((bounds.getMinY()-15)/Math.max(1,scroll.getContent().getLayoutBounds().getHeight()-scroll.getViewportBounds().getHeight()),0,1));
                layout();return null;
            });pause();
            Button add=fx(()->(Button)window.scene().lookup("#add-command"));
            click(fx(()->{
                Point2D point=add.localToScreen(add.getWidth()/2,add.getHeight()/2);
                ScrollPane scroll=(ScrollPane)window.commands.getCenter();
                Node viewport=scroll.lookup(".viewport");
                check(viewport.localToScreen(viewport.getBoundsInLocal()).contains(point),"command Add is outside its viewport");return point;
            }));
            check(fx(()->window.document().project().formatVersion()==2&&window.document().project().sequences().getFirst().commands().size()==1),"native scaled command Add failed");
            click(fx(()->save.localToScreen(save.getWidth()/2,save.getHeight()/2)));
            check(ProjectFiles.load(Path.of(args[1])).project().sequences().getFirst().commands().getFirst().arguments().equals(java.util.List.of(128)),"native command not saved");
            fx(()->{((ScrollPane)window.commands.getCenter()).setVvalue(0);layout();return null;});
            fx(()->{
                WritableImage image=stage.getScene().snapshot(null);BufferedImage png=new BufferedImage((int)image.getWidth(),(int)image.getHeight(),BufferedImage.TYPE_INT_ARGB);
                for(int y=0;y<png.getHeight();y++)for(int x=0;x<png.getWidth();x++)png.setRGB(x,y,image.getPixelReader().getArgb(x,y));
                ImageIO.write(png,"png",Path.of(args[2]).toFile());return null;
            });
            System.out.println("Packaged native DPI smoke PASS: "+args[0]+"x, clip drag, scrolled note entry/resize, command Add, Save and work-area bounds");
        } finally {fx(()->{if(window!=null)window.close();if(stage!=null)stage.close();Platform.exit();return null;});}
    }
    private static void reveal(int pitch) throws Exception {
        fx(()->{window.showNotes();window.piano.scroll.setHvalue(1);layout();double y=PianoRollView.HEADER+(window.piano.topPitch-pitch)*PianoRollView.NOTE_ROW+5;double h=window.piano.scroll.getViewportBounds().getHeight();window.piano.scroll.setVvalue(Math.clamp((y-h/2)/Math.max(1,window.piano.contentHeight-h),0,1));layout();return null;});pause();
    }
    private static Point2D notePoint(int tick,int pitch,double adjustment) {
        double x=PianoRollView.KEYBOARD+tick*window.piano.scale-window.piano.offset+adjustment;
        double y=PianoRollView.HEADER+(window.piano.topPitch-pitch)*PianoRollView.NOTE_ROW+5;
        Point2D point=window.piano.canvas.localToScreen(x,y);Node viewport=window.piano.scroll.lookup(".viewport");
        check(viewport.localToScreen(viewport.getBoundsInLocal()).contains(point),"note handle not visible in viewport");return point;
    }
    private static void layout(){window.scene().getRoot().applyCss();window.scene().getRoot().layout();}
    private static void click(Point2D point) throws Exception {fx(()->{robot.mouseMove(point);robot.mousePress(MouseButton.PRIMARY);robot.mouseRelease(MouseButton.PRIMARY);return null;});pause();}
    private static void pause() throws InterruptedException {Thread.sleep(120);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static <T>T fx(Callable<T> work) throws Exception {FutureTask<T> task=new FutureTask<>(work);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);}
}
