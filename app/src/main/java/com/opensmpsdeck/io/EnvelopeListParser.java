package com.opensmpsdeck.io;

import com.opensmpsdeck.model.PsgEnvelope;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class EnvelopeListParser {

    private EnvelopeListParser() {
    }

    /**
     * Parse a PSG.lst/Modulat.lst binary envelope list.
     * Format: "LST_ENV" + count + per-envelope name/data blobs.
     */
    public static List<PsgEnvelope> parse(byte[] data) {
        List<PsgEnvelope> envelopes = new ArrayList<>();
        if (data.length < 8) return envelopes;

        String header = new String(data, 0, 7, StandardCharsets.US_ASCII);
        if (!"LST_ENV".equals(header)) return envelopes;

        int count = data[7] & 0xFF;
        int pos = 8;
        for (int i = 0; i < count && pos < data.length; i++) {
            int nameLen = data[pos++] & 0xFF;
            if (pos + nameLen > data.length) break;
            String envName = new String(data, pos, nameLen, StandardCharsets.US_ASCII);
            pos += nameLen;
            if (pos >= data.length) break;
            int dataLen = data[pos++] & 0xFF;
            if (pos + dataLen > data.length) break;
            byte[] envData = new byte[dataLen];
            System.arraycopy(data, pos, envData, 0, dataLen);
            pos += dataLen;
            envelopes.add(new PsgEnvelope(envName, envData));
        }
        return envelopes;
    }
}
