package com.opensmpsdeck.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;

/**
 * Plays a short mono float PCM buffer through the system mixer — used to
 * audition the original WAV slice a matched voice was scored against.
 * One clip at a time; starting a new clip stops the previous one.
 */
public final class PcmClipPlayer {

    private Clip current;

    /** Plays 44.1 kHz mono float samples (range ±1). Safe to call repeatedly. */
    public synchronized void play(float[] samples, int sampleRate) {
        stop();
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int v = (int) (Math.max(-1f, Math.min(1f, samples[i])) * 32767);
            pcm[i * 2] = (byte) v;
            pcm[i * 2 + 1] = (byte) (v >> 8);
        }
        AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
        try {
            Clip clip = AudioSystem.getClip();
            clip.open(fmt, pcm, 0, pcm.length);
            clip.addLineListener(ev -> {
                if (ev.getType() == javax.sound.sampled.LineEvent.Type.STOP) {
                    ev.getLine().close();
                }
            });
            clip.start();
            current = clip;
        } catch (LineUnavailableException | IllegalArgumentException e) {
            // no audio device (headless) — auditioning is best-effort
        }
    }

    public synchronized void stop() {
        if (current != null) {
            current.stop();
            current.close();
            current = null;
        }
    }
}
