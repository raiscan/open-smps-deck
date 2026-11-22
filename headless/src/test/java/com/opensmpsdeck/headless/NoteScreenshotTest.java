package com.opensmpsdeck.headless;

import com.google.gson.JsonParser;
import com.opensmpsdeck.project.DemoProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NoteScreenshotTest {
    @TempDir Path scratch;

    @Test void preservesThePayloadThroughMetadataAndLosslessPixelReencoding() throws Exception {
        var fixture = fixture();
        Path image = Files.write(scratch.resolve("attached.png"), fixture.encoded.png());
        var attached = NoteScreenshot.decode(image, fixture.registry);
        assertEquals("metadata", attached.method());
        assertEquals(fixture.capture.payload(), attached.payload());
        assertEquals(2, attached.block().notes().size());
        assertEquals(63, attached.block().lasso().minMidi());

        var roundTrip = ImageIO.read(image.toFile());
        assertImageEquals(fixture.encoded.image(), roundTrip);
        Path pasted = scratch.resolve("pasted.png");
        assertTrue(ImageIO.write(roundTrip, "PNG", pasted.toFile()));
        var stripped = NoteScreenshot.decode(pasted, fixture.registry);
        assertEquals("lsb", stripped.method());
        assertEquals(fixture.capture.payload(), stripped.payload());

        var out = new ByteArrayOutputStream();
        var stream = new PrintStream(out);
        assertEquals(0, Main.run(new String[]{"reconstruct", "describe", "--from-png", pasted.toString(), "--json"},
                stream, stream), out::toString);
        var result = JsonParser.parseString(out.toString()).getAsJsonObject();
        assertEquals("lsb", result.get("method").getAsString());
        assertEquals(fixture.capture.reference(), result.get("reference").getAsString());
    }

    @Test void referenceSurvivesDownscalingAndJpegAndCrcRejectsAlteredPayload() throws Exception {
        var fixture = fixture();
        var original = fixture.encoded.image();
        var smaller = new BufferedImage(original.getWidth() / 2, original.getHeight() / 2, BufferedImage.TYPE_INT_RGB);
        var g = smaller.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(original, 0, 0, smaller.getWidth(), smaller.getHeight(), null);
        g.dispose();
        Path scaled = scratch.resolve("scaled.png");
        assertTrue(ImageIO.write(smaller, "PNG", scaled.toFile()));
        var scaledResult = NoteScreenshot.decode(scaled, fixture.registry);
        assertEquals("registry", scaledResult.method());
        assertEquals(fixture.capture.payload(), scaledResult.payload());
        Path jpeg = scratch.resolve("scaled.jpg");
        assertTrue(ImageIO.write(smaller, "JPEG", jpeg.toFile()));
        var lossy = NoteScreenshot.decode(jpeg, fixture.registry);
        assertEquals("registry", lossy.method());
        assertEquals(fixture.capture.payload(), lossy.payload());

        // Flip the first compressed bit after the 12-byte header. The magic and length remain valid.
        int pixel = 12 * 8 / 3;
        original.setRGB(pixel, 0, original.getRGB(pixel, 0) ^ (1 << 16));
        assertNull(NoteScreenshot.pixels(original));
        Path corrupted = scratch.resolve("damaged.png");
        assertTrue(ImageIO.write(original, "PNG", corrupted.toFile()));
        assertEquals("registry", NoteScreenshot.decode(corrupted, fixture.registry).method());
        var unknown = assertThrows(java.io.IOException.class,
                () -> NoteScreenshot.decode(corrupted, new NoteClipRegistry(scratch.resolve("empty"))));
        assertTrue(unknown.getMessage().contains(fixture.capture.reference()), unknown::getMessage);
    }

    private record Fixture(NoteHandoff.Capture capture, NoteScreenshot.Encoded encoded, NoteClipRegistry registry) {}

    private Fixture fixture() throws Exception {
        var project = DemoProject.create();
        var sequence = project.sequences().getFirst();
        var notes = sequence.notes().stream().limit(2).toList();
        var capture = ArrangementNoteContext.capture(scratch.resolve("夏の曲.smps.json"), project, false, sequence.id(),
                List.of(), notes, new NoteHandoff.ViewRange(0, 24, 63, 74, Map.of("ruler", "sequence")));
        var registry = new NoteClipRegistry(scratch.resolve("xdg-state").resolve("clips"));
        var crop = new BufferedImage(400, 160, BufferedImage.TYPE_INT_RGB);
        var g = crop.createGraphics();
        g.setColor(java.awt.Color.BLUE);
        g.fillRect(0, 0, crop.getWidth(), crop.getHeight());
        g.dispose();
        var encoded = NoteScreenshot.capture(crop, capture, registry);
        assertEquals(crop.getWidth(), encoded.image().getWidth());
        assertEquals(capture.payload(), Files.readString(scratch.resolve(".clips").resolve(capture.reference() + ".txt")));
        return new Fixture(capture, encoded, registry);
    }

    private static void assertImageEquals(BufferedImage first, BufferedImage second) {
        int w = first.getWidth(), h = first.getHeight();
        assertEquals(w, second.getWidth());
        assertEquals(h, second.getHeight());
        assertArrayEquals(first.getRGB(0, 0, w, h, null, 0, w), second.getRGB(0, 0, w, h, null, 0, w));
    }
}
