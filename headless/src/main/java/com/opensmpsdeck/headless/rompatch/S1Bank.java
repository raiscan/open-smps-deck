package com.opensmpsdeck.headless.rompatch;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static com.opensmpsdeck.headless.rompatch.S1Revision.u8;

/** Walk the finite S1 portable stream profile to find only actual asset operands. */
record S1Bank(List<Integer> envelopes, List<Integer> dac) {
    static S1Bank read(byte[] bank, int voices) {
        require(bank.length >= 10, 0, "truncated music header");
        int fm = u8(bank, 2), psg = u8(bank, 3);
        int headerEnd = 6 + 4 * fm + 6 * psg;
        require(fm >= 1 && fm <= 6 && psg <= 3 && headerEnd < bank.length, 2, "invalid track counts");
        require(u8(bank, 4) != 0 && u8(bank, 5) >= 2, 4, "invalid tempo or timing divider");
        int end = voices == 0 ? bank.length : word(bank, 0);
        require(voices >= 0 && voices <= 128 && (voices == 0 ? word(bank, 0) == 0 :
                end > headerEnd && bank.length - end == voices * 25), 0, "invalid local voice table");
        var envelopes = new ArrayList<Integer>();
        var dac = new ArrayList<Integer>();
        var boundaries = new TreeSet<Integer>();
        boundaries.add(end);
        int[] starts = new int[fm + psg];
        for (int i = 0; i < starts.length; i++) {
            int at = i < fm ? 6 + 4 * i : 6 + 4 * fm + 6 * (i - fm);
            starts[i] = word(bank, at);
            require(starts[i] >= headerEnd && starts[i] < end, at, "track pointer outside stream region");
            boundaries.add(starts[i]);
            if (i >= fm) {
                require(bank[at + 4] == 0, at + 4, "PSG modulation envelopes are unsupported");
                envelopes.add(at + 5);
            }
        }
        for (int i = 0; i < starts.length; i++)
            scan(bank, starts[i], boundaries.higher(starts[i]), i, fm, voices, envelopes, dac);
        return new S1Bank(envelopes.stream().distinct().toList(), dac.stream().distinct().toList());
    }

    private static void scan(byte[] bank, int at, int end, int channel, int fm, int voices,
                             List<Integer> envelopes, List<Integer> dac) {
        boolean isDac = channel == 0, isPsg = channel >= fm;
        boolean haveNote = false;
        while (at < end) {
            int command = u8(bank, at++);
            if (command == 0xF2) return;
            if (command < 0x80) {
                require(!isDac && haveNote && command != 0, at - 1, "invalid duration-only unit");
                continue;
            }
            if (command < 0xE0) {
                if (command != 0x80) {
                    if (isDac) dac.add(at - 1);
                    else if (!isPsg) require(voices > 0, at - 1, "FM notes require a local voice table");
                }
                require(at < end && u8(bank, at) > 0 && u8(bank, at) < 0x80, at, "expected explicit note duration");
                at++;
                haveNote = true;
                continue;
            }
            int length = switch (command) {
                case 0xE7, 0xF4 -> 0;
                case 0xE1, 0xE8, 0xE9 -> 1;
                case 0xE0, 0xE6, 0xEF -> isPsg ? -1 : 1;
                case 0xEC, 0xF5 -> isPsg ? 1 : -1;
                case 0xF3 -> channel == fm + 2 ? 1 : -1;
                case 0xF0 -> 4;
                default -> -1;
            };
            require(!isDac && length >= 0 && at + length <= end, at - 1,
                    "unsupported or truncated command 0x%02X".formatted(command));
            if (command == 0xEF) require(u8(bank, at) < voices, at, "undefined FM voice");
            if (command == 0xF5) envelopes.add(at);
            at += length;
        }
        throw invalid(at, "track has no F2 stop");
    }

    private static int word(byte[] bank, int at) { return (u8(bank, at) << 8) | u8(bank, at + 1); }

    static void require(boolean valid, int at, String message) {
        if (!valid) throw invalid(at, message);
    }

    private static IllegalArgumentException invalid(int at, String message) {
        return new IllegalArgumentException("Invalid Sonic 1 song at bank offset 0x%04X: %s".formatted(at, message));
    }
}
