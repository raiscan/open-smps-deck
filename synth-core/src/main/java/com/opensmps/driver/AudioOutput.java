package com.opensmps.driver;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sound.sampled.*;

/**
 * Streams audio from an SmpsDriver to the system audio device.
 * Runs on a dedicated daemon thread. Start/stop/pause control.
 *
 * <p>Each {@link #start()} creates a fresh thread/line generation. The loop
 * captures its own line and checks its thread identity so a previous
 * generation that outlived a timed-out {@link #stop()} can neither write to
 * nor close the new generation's line.
 */
public class AudioOutput {

    private static final Logger LOGGER = Logger.getLogger(AudioOutput.class.getName());

    private static final int SAMPLE_RATE = 44100;
    private static final int BUFFER_SAMPLES = 1024;

    private final SmpsDriver driver;
    private volatile Thread audioThread;
    private volatile boolean running;
    private volatile boolean paused;

    public AudioOutput(SmpsDriver driver) {
        this.driver = driver;
    }

    public void start() {
        if (running && audioThread != null && audioThread.isAlive()) return;

        SourceDataLine newLine;
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            newLine = (SourceDataLine) AudioSystem.getLine(info);
            newLine.open(format, BUFFER_SAMPLES * 4);
            newLine.start();
        } catch (LineUnavailableException e) {
            throw new RuntimeException("Audio device unavailable", e);
        }

        running = true;
        paused = false;
        Thread t = new Thread(() -> audioLoop(newLine), "OpenSMPSDeck-Audio");
        t.setDaemon(true);
        audioThread = t;
        t.start();
    }

    public void stop() {
        running = false;
        paused = false;
        Thread t = audioThread;
        if (t != null) {
            try { t.join(1000); } catch (InterruptedException ignored) {}
            if (t.isAlive()) {
                // Zombie generation (audio device stall): abandon it; its own
                // finally block closes its line when it eventually unblocks.
                LOGGER.warning("Audio thread did not stop within 1s; abandoning generation");
            }
        }
        audioThread = null;
        driver.stopAll();
        driver.silenceAll();
    }

    public void pause() { paused = true; }
    public void resume() { paused = false; }
    public boolean isRunning() { return running; }
    public boolean isPaused() { return paused; }

    private void audioLoop(SourceDataLine myLine) {
        short[] samples = new short[BUFFER_SAMPLES * 2];
        byte[] byteBuffer = new byte[BUFFER_SAMPLES * 4];

        try {
            while (running && audioThread == Thread.currentThread()) {
                if (paused) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }

                driver.read(samples);

                for (int i = 0; i < samples.length; i++) {
                    byteBuffer[i * 2] = (byte) (samples[i] & 0xFF);
                    byteBuffer[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
                }

                myLine.write(byteBuffer, 0, byteBuffer.length);
            }
        } catch (Throwable t) {
            // A dying audio thread must not brick playback: log and fall
            // through so the running flag is reset below.
            LOGGER.log(Level.SEVERE, "Audio thread terminated by exception", t);
        } finally {
            if (audioThread == Thread.currentThread()) {
                running = false;
            }
            myLine.stop();
            myLine.close();
        }
    }
}
