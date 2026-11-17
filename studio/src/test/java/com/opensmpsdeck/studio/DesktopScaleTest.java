package com.opensmpsdeck.studio;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DesktopScaleTest {
    @Test void kdeGtkDpiBecomesANativeJavafxScaleRatherThanASecondNodeTransform(){
        assertEquals("1.6",DesktopScale.scaleFromGtkDpi("157286"));
        assertEquals("1.5",DesktopScale.scaleFromGtkDpi("147456"));
        assertEquals("2.0",DesktopScale.scaleFromGtkDpi("196608"));
        assertEquals("1.0",DesktopScale.scaleFromGtkDpi("98304"));
    }
    @Test void explicitScaleChoicesAndOtherPlatformsKeepTheirNativeHandling(){
        assertFalse(DesktopScale.shouldConfigure("Linux","1.25",null,null));
        assertFalse(DesktopScale.shouldConfigure("Linux",null,"2",null));
        assertFalse(DesktopScale.shouldConfigure("Linux",null,null,"false"));
        assertFalse(DesktopScale.shouldConfigure("Mac OS X",null,null,null));
        assertFalse(DesktopScale.shouldConfigure("Windows 11",null,null,null));
        assertTrue(DesktopScale.shouldConfigure("Linux",null,null,null));
    }
    @Test void validLiveNormalDpiStopsStaleHigherScaleFallbacks(){
        assertEquals("1.0",DesktopScale.firstAvailableScale(()->DesktopScale.scaleFromGtkDpi("98304"),()->{fail("stale config must not override live normal scale");return "1.5";}));
        assertEquals("1.5",DesktopScale.firstAvailableScale(()->null,()->"1.5",()->{fail("lower priority source must not be read");return "2.0";}));
    }
    @Test void malformedOrUnconfiguredDpiDoesNotOverrideTheToolkit(){
        for(String value:new String[]{"-1","0","garbage","99999999999","NaN",""}) assertNull(DesktopScale.scaleFromGtkDpi(value));
        assertEquals("157286",DesktopScale.iniValue("[Settings]\n# ignored\ngtk-xft-dpi = 157286\ngtk-font-name=Noto Sans 10\n","gtk-xft-dpi"));
        assertEquals("153",DesktopScale.resourceValue("Xcursor.size:\t48\nXft.dpi:\t153\n","Xft.dpi"));
        assertNull(DesktopScale.iniValue("gtk-xft-dpi-ish=147456","gtk-xft-dpi"));
    }
}
