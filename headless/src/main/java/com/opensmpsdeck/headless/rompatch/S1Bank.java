package com.opensmpsdeck.headless.rompatch;

import com.opensmpsdeck.headless.ClassicPackageBank;

import java.util.ArrayList;
import java.util.List;

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
        for (int i = 0; i < psg; i++) envelopes.add(6 + fm * 4 + i * 6 + 5);
        ClassicPackageBank.walkS1Assets(bank, voices, envelopes::add, dac::add);
        return new S1Bank(envelopes.stream().distinct().toList(), dac.stream().distinct().toList());
    }

    private static int word(byte[] bank, int at) { return (u8(bank, at) << 8) | u8(bank, at + 1); }

    static void require(boolean valid, int at, String message) {
        if (!valid) throw invalid(at, message);
    }

    private static IllegalArgumentException invalid(int at, String message) {
        return new IllegalArgumentException("Invalid Sonic 1 song at bank offset 0x%04X: %s".formatted(at, message));
    }
}
