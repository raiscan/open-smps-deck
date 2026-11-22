package com.opensmpsdeck.headless;

import com.openggf.game.sonic1.audio.Sonic1SmpsConstants;
import com.opensmpsdeck.project.AssetOrigin;
import com.opensmpsdeck.project.DemoProject;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.StockRef;
import com.opensmpsdeck.project.StudioProject;
import com.opensmpsdeck.project.TargetGame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.nio.charset.StandardCharsets.US_ASCII;

/** A cartridge-shaped fixture with two synthetic envelope slots. */
public record ExportRomFixture(Path rom, StudioProject project) {
    public static ExportRomFixture create(Path directory) throws IOException {
        var image = ByteBuffer.allocate(0x80000);
        image.put(0x100, "SEGA".getBytes(US_ASCII));
        image.put(0x120, "SONIC THE HEDGEHOG".getBytes(US_ASCII));
        image.put(0x150, "SONIC THE HEDGEHOG".getBytes(US_ASCII));
        byte[] first = {9, 4, (byte) 0x80};
        byte[] second = {2, 1, (byte) 0x80};
        int table = Sonic1SmpsConstants.PSG_ENV_PTR_TABLE_ADDR;
        image.putInt(table, 0x300);
        image.putInt(table + 4, 0x310);
        image.put(0x300, first);
        image.put(0x310, second);
        byte[] transformed = {6, 4, (byte) 0x80};
        var ref = new StockRef("s1", "psg", null, null, 1, null, StockTable.hash(first));
        var other = new StockRef("s1", "psg", null, null, 2, null, StockTable.hash(second));
        var edited = new Envelope("edited", "Edited", Envelope.PSG_VOLUME, StockAssets.unsigned(transformed),
                AssetOrigin.stock(ref).withPatch(first, transformed));
        var plain = new Envelope("plain", "Plain", Envelope.PSG_VOLUME, StockAssets.unsigned(second), AssetOrigin.stock(other));
        var demo = DemoProject.create();
        var project = new StudioProject(4, demo.name(), demo.mode(), demo.ticksPerBeat(), demo.tempo(), demo.voices(),
                demo.sequences(), demo.clips(), List.of(edited, plain), List.of(), TargetGame.S1, null);
        return new ExportRomFixture(Files.write(directory.resolve("synthetic-s1.gen"), image.array()), project);
    }

    public Path writeProject(Path path, boolean brokenTransform) throws IOException {
        var input = project;
        if (brokenTransform) {
            var edited = project.envelopes().getFirst();
            var stale = new Envelope(edited.id(), edited.name(), edited.type(), List.of(5, 4, 0x80), edited.origin());
            input = project.withAssets(project.voices(), List.of(stale, project.envelopes().getLast()), project.samples());
        }
        ProjectFiles.save(path, input, null);
        return path;
    }
}
