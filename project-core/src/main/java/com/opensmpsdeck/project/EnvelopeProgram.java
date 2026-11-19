package com.opensmpsdeck.project;

import java.util.Arrays;
import java.util.List;

/** Bounded envelope subset whose native and pinned-driver behavior is defined. */
public final class EnvelopeProgram {
    private EnvelopeProgram() {}

    public static void validate(String type,List<Integer> bytes) {
        int maximum;
        if(Envelope.PSG_VOLUME.equals(type)) maximum=0x0F;
        else if(Envelope.MODULATION.equals(type)) maximum=0x7F;
        else throw new IllegalArgumentException("Envelope type must be PSG_VOLUME or MODULATION");
        if(bytes==null || bytes.isEmpty() || bytes.size()>256) throw new IllegalArgumentException("Envelope program must contain 1..256 raw bytes");
        for(Integer value:bytes) {
            if(value==null || value<0 || value>255) throw new IllegalArgumentException("Envelope bytes must be in 0..255");
            if(value<=maximum || value==0x80 || value==0x81 || value==0x83) continue;
            if(value==0x82 || value==0x84 || value>=0x85)
                throw new IllegalArgumentException(String.format("Unsupported envelope control %02X; native/driver behavior is outside the safe profile",value));
            throw new IllegalArgumentException("PSG volume envelope data must be in 00..0F");
        }
        if(bytes.getFirst()==0x80) throw new IllegalArgumentException("Envelope cannot start with reset 80: zero-time reset cycle");
        int last=bytes.getLast();
        if(last!=0x80 && last!=0x81 && last!=0x83) throw new IllegalArgumentException("Envelope must end with reset 80, rest 81 or hold 83");
    }

    /**
     * Validate the S3K playback view. For modulation 82 and 84, the byte
     * after the command is the resolved driver lookup operand.
     */
    public static void validateNativePlayback(String type, List<Integer> bytes) {
        boolean modulation = Envelope.MODULATION.equals(type);
        if (!modulation && !Envelope.PSG_VOLUME.equals(type))
            throw new IllegalArgumentException("Envelope type must be PSG_VOLUME or MODULATION");
        if (bytes == null || bytes.isEmpty() || bytes.size() > 256)
            throw new IllegalArgumentException("Envelope program must contain 1..256 raw bytes");
        for (Integer value : bytes) {
            if (value == null || value < 0 || value > 255)
                throw new IllegalArgumentException("Envelope bytes must be in 0..255");
            // EF and F5 can switch envelopes while retaining the PSG cursor.
            // Unsupported code-memory controls are unsafe at any entry.
            if (!modulation && value > 0x81 && value != 0x83)
                throw new IllegalArgumentException("Unsupported PSG envelope command %02X".formatted(value));
        }

        int[] visited = new int[bytes.size()];
        Arrays.fill(visited, -1);
        int cursor = 0;
        int writes = 0;
        while (cursor < bytes.size()) {
            if (visited[cursor] != -1) {
                if (visited[cursor] == writes) throw new IllegalArgumentException("Envelope has a zero-time control cycle");
                return;
            }
            visited[cursor] = writes;
            int command = bytes.get(cursor);
            if (command == 0x81 || command == 0x83) return;
            if (command < 0x80 || (modulation && command >= 0x85)) {
                cursor = modulation ? (cursor + 1) & 0xFF : cursor + 1;
                writes++;
            } else if (command == 0x80) {
                cursor = 0;
            } else if (modulation && (command == 0x82 || command == 0x84) && cursor + 1 < bytes.size()) {
                cursor = command == 0x82 ? bytes.get(cursor + 1) : (cursor + 2) & 0xFF;
            } else {
                throw new IllegalArgumentException("Unsupported or truncated envelope command %02X at byte %d"
                        .formatted(command, cursor));
            }
        }
        throw new IllegalArgumentException("Envelope runs outside the declared playback bytes");
    }
}
