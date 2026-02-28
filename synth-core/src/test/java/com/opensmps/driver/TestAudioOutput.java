package com.opensmps.driver;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestAudioOutput {

    @Test
    void testConstructionDoesNotThrow() {
        SmpsDriver driver = new SmpsDriver(44100.0);
        AudioOutput output = new AudioOutput(driver);
        assertFalse(output.isRunning());
        assertFalse(output.isPaused());
    }
}
