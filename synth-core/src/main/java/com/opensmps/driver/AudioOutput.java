package com.opensmps.driver;

import javax.sound.sampled.*;

/**
 * Streams audio from an SmpsDriver to the system audio device.
 * Runs on a dedicated daemon thread. Start/stop/pause control.
 */
public class AudioOutput {

    private static final int SAMPLE_RATE = 44100;
    private static final int BUFFER_SAMPLES = 1024;

    private final SmpsDriver driver;
    private SourceDataLine line;
    private Thread audioThread;
    private volatile boolean running;
    private volatile boolean paused;

    public AudioOutput(SmpsDriver driver) {
        this.driver = driver;
    }

    public void start() {
        if (running) return;
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format, BUFFER_SAMPLES * 4);
            line.start();
        } catch (LineUnavailableException e) {
            throw new RuntimeException("Audio device unavailable", e);
        }

        running = true;
        paused = false;
        audioThread = new Thread(this::audioLoop, "OpenSMPSDeck-Audio");
        audioThread.setDaemon(true);
        audioThread.start();
    }

    public void stop() {
        running = false;
        paused = false;
        if (audioThread != null) {
            try { audioThread.join(1000); } catch (InterruptedException ignored) {}
        }
        if (line != null) {
            line.stop();
            line.close();
            line = null;
        }
        driver.stopAll();
        driver.silenceAll();
    }

    public void pause() { paused = true; }
    public void resume() { paused = false; }
    public boolean isRunning() { return running; }
    public boolean isPaused() { return paused; }

    private void audioLoop() {
        short[] samples = new short[BUFFER_SAMPLES * 2];
        byte[] byteBuffer = new byte[BUFFER_SAMPLES * 4];

        try {
            while (running) {
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

                line.write(byteBuffer, 0, byteBuffer.length);
            }
        } finally {
            if (line != null) {
                line.stop();
                line.close();
            }
        }
    }
}
