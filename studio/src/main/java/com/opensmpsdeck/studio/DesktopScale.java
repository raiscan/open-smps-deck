package com.opensmpsdeck.studio;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Bridge KDE/XWayland's GTK/Xft preference to JavaFX 21 before toolkit startup.
 * GTK's JavaFX backend can otherwise let a stale GNOME factor of 1 mask Xft DPI.
 * Scaling remains native: controls, canvases, pointer coordinates and dialogs
 * share one output scale; we never multiply it by a second scene transform.
 */
final class DesktopScale {
    private DesktopScale() {}
    static void configure() {
        if(!shouldConfigure(System.getProperty("os.name",""),System.getProperty("glass.gtk.uiScale",System.getenv("glass.gtk.uiScale")),
                System.getenv("GDK_SCALE"),System.getProperty("prism.allowhidpi")))return;
        String scale=firstAvailableScale(DesktopScale::liveScale,DesktopScale::gtkConfigScale,DesktopScale::xresourcesScale);
        // Valid normal DPI stops stale fallbacks, but leaves native GNOME/macOS
        // monitor handling alone instead of forcing the monitor down to 1x.
        if(scale!=null&&Double.parseDouble(scale)>1.05)System.setProperty("glass.gtk.uiScale",scale);
    }
    @SafeVarargs static String firstAvailableScale(java.util.function.Supplier<String>... sources) {
        for(var source:sources){String scale=source.get();if(scale!=null)return scale;}
        return null;
    }
    private static String liveScale() {
        try {
            Object dpi=java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("gnome.Xft/DPI");
            return dpi instanceof Number number?scaleFromGtkDpi(number.toString()):null;
        }catch(RuntimeException|java.awt.AWTError ignored){return null;}
    }
    private static String gtkConfigScale() {
        String config=System.getenv("XDG_CONFIG_HOME");
        Path root=config==null||config.isBlank()?Path.of(System.getProperty("user.home"),".config"):Path.of(config);
        for(String gtk:new String[]{"gtk-3.0","gtk-4.0"}) {
            try(var input=Files.newInputStream(root.resolve(gtk).resolve("settings.ini"))) {
                byte[] bytes=input.readNBytes(65_537);
                String scale=bytes.length<=65_536?scaleFromGtkDpi(iniValue(new String(bytes,StandardCharsets.UTF_8),"gtk-xft-dpi")):null;
                if(scale!=null)return scale;
            }catch(Exception ignored){}
        }
        return null;
    }
    static boolean shouldConfigure(String os,String explicit,String gdk,String hidpi) {
        return os.toLowerCase(Locale.ROOT).contains("linux")&&explicit==null&&gdk==null&&!"false".equalsIgnoreCase(hidpi);
    }
    static String scaleFromGtkDpi(String value) {
        if(value==null)return null;
        try {
            double factor=Double.parseDouble(value.trim())/(1024*96.0);
            if(!Double.isFinite(factor)||factor<.5||factor>4)return null;
            return Double.toString(Math.round(factor*100)/100.0);
        }catch(NumberFormatException ignored){return null;}
    }
    static String iniValue(String text,String key) {return value(text,key,'=');}
    static String resourceValue(String text,String key) {return value(text,key,':');}
    private static String value(String text,String key,char separator) {
        for(String line:text.split("\\R")) {
            int at=line.indexOf(separator);
            if(at>0&&line.substring(0,at).trim().equals(key))return line.substring(at+1).trim();
        }
        return null;
    }
    private static String xresourcesScale() {
        Process process=null;
        try {
            process=new ProcessBuilder("xrdb","-query").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            // Never leave application launch waiting on an unavailable X server.
            if(!process.waitFor(400,TimeUnit.MILLISECONDS))return null;
            byte[] bytes=process.getInputStream().readNBytes(65_537);
            if(bytes.length>65_536||process.exitValue()!=0)return null;
            String dpi=resourceValue(new String(bytes,StandardCharsets.UTF_8),"Xft.dpi");
            return dpi==null?null:scaleFromGtkDpi(Double.toString(Double.parseDouble(dpi)*1024));
        }catch(Exception ignored){return null;}
        finally{if(process!=null&&process.isAlive())process.destroyForcibly();}
    }
}
