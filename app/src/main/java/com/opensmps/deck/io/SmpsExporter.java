package com.opensmps.deck.io;

import com.opensmps.deck.codec.PatternCompiler;
import com.opensmps.deck.model.Song;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Exports a {@link Song} as a raw SMPS binary file.
 *
 * <p>Thin wrapper around {@link PatternCompiler} that compiles the song
 * and writes the resulting bytes to disk.
 */
public class SmpsExporter {

    private final PatternCompiler compiler = new PatternCompiler();

    /**
     * Export song as raw SMPS binary to the given file.
     *
     * @param song the song to export
     * @param file the destination file
     * @throws IOException if writing fails
     */
    public void export(Song song, File file) throws IOException {
        byte[] smps = compiler.compile(song);
        Files.write(file.toPath(), smps);
    }

    /**
     * Compile song to SMPS binary without writing to file.
     *
     * @param song the song to compile
     * @return the compiled SMPS binary data
     */
    public byte[] compile(Song song) {
        return compiler.compile(song);
    }
}
