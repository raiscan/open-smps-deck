package com.opensmpsdeck.studio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.lang.reflect.Proxy;
import java.util.concurrent.locks.LockSupport;

/** Isolate display-driven tests from the host's audio device. */
final class StudioTestAudio {
    private StudioTestAudio() {}

    static SourceDataLine line(AudioFormat format) throws LineUnavailableException {
        if (Boolean.getBoolean("studio.test.realAudio")) return AudioSystem.getSourceDataLine(format);
        return new SilentLine(format).create();
    }

    private static final class SilentLine {
        private final AudioFormat format;
        private long frames;

        SilentLine(AudioFormat format) { this.format = format; }

        SourceDataLine create() {
            return (SourceDataLine) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{SourceDataLine.class}, (line, method, args) -> switch (method.getName()) {
                        case "open", "start", "stop", "flush", "drain", "close" -> null;
                        case "getLongFramePosition" -> frames;
                        case "write" -> write((int) args[2]);
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private int write(int bytes) {
            int count = bytes / format.getFrameSize();
            LockSupport.parkNanos((long) (count * 1_000_000_000.0 / format.getFrameRate()));
            frames += count;
            return bytes;
        }
    }
}
