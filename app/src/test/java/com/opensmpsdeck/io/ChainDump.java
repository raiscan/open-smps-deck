package com.opensmpsdeck.io;

import com.opensmpsdeck.model.Phrase;
import com.opensmpsdeck.model.Song;
import java.io.File;

public class ChainDump {
    public static void main(String[] args) throws Exception {
        File f = new File("../docs/SMPS-rips/" + args[0]);
        if (!f.exists()) f = new File("docs/SMPS-rips/" + args[0]);
        int ch = Integer.parseInt(args[1]);
        Song song = new SmpsImporter().importFile(f);
        var hier = song.getHierarchicalArrangement();
        var chain = hier.getChain(ch);
        System.out.println("channel " + ch + " entries=" + chain.getEntries().size()
                + " loopIndex=" + chain.getLoopEntryIndex());
        for (int i = 0; i < chain.getEntries().size(); i++) {
            var e = chain.getEntries().get(i);
            Phrase p = hier.getPhraseLibrary().getPhrase(e.getPhraseId());
            byte[] d = p.getDataDirect();
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < Math.min(d.length, 40); j++) sb.append(String.format("%02X ", d[j]));
            System.out.printf("[%2d] id=%d rep=%d trans=%d len=%d  %s%s%n",
                    i, e.getPhraseId(), e.getRepeatCount(), e.getTransposeSemitones(),
                    d.length, sb, d.length > 40 ? "..." : "");
        }
    }
}
