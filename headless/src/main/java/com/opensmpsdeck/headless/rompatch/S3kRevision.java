package com.opensmpsdeck.headless.rompatch;

import com.openggf.data.compression.KosinskiReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.util.List;

import static com.opensmpsdeck.headless.rompatch.SongLayout.WINDOW;
import static com.opensmpsdeck.headless.rompatch.SongLayout.Span;

/** Layout of the supported locked-on cartridge, checked by RetailLayoutAudit. */
final class S3kRevision {
    static final int ROM_SIZE = 0x400000;
    static final int CARTRIDGE_SIZE = 0x200000;
    static final String SHA1 = "cfbf98c36c776677290a872547ac47c53d2761d6";

    // sonic3k.asm:1404–1438 and sonic3k.lst:271512,274976,276879,280615.
    static final int DRIVER = 0x0F6960, TABLES = 0x0F7760, TABLE_BASE = 0x1300;
    static final int MUSIC_BANKS = 0x0B65, MUSIC_POINTERS = 0x1618, MUSIC_COUNT = 0x33;
    static final int MOD_TABLE = 0x130E, MOD_COUNT = 8;
    static final int PSG_MASTER = 0x130A, PSG_TABLE = 0x1387, PSG_COUNT = 39;
    static final int PSG_FREE_START = 0x1B43, PSG_FREE_END = 0x1C00;

    // Sound/Z80 Sound Driver.asm:508–520,630,2865–2903,2997–3000,4269–4304,4360–4361.
    static final int DAC_BANKS = 0xD6, DAC_DELTAS = 0x1116, DAC_FIRST = 0x81, DAC_LAST = 0xC4;
    static final int DAC_BANK = 0x5B;
    static final List<Integer> FREE_DAC_NOTES = List.of(0x89, 0x96, 0x9A, 0xA5, 0xA6, 0xA7,
            0xA9, 0xB3, 0xB4, 0xB5, 0xB8, 0xBD, 0xBE, 0xC1, 0xC2, 0xC3, 0xC4);

    // sonic3k.asm:1404–1438; sonic3k.macros.asm:93–103.
    static final Site DRIVER_HOOK = new Site(0x1326, "33fc000000a112004e714e714e714e7133fc010000a11200");
    // sonic3k.asm:5529,6490; s3.lst:9030 (standalone half, shifted by 0x200000).
    static final List<Site> TITLE_REQUESTS = List.of(
            new Site(0x4052, "70256100d302"), new Site(0x5286, "70256100c0ce"), new Site(0x203758, "70256100de86"));
    // sonic3k.asm:5622,6573; s3.lst:9138.
    static final List<Site> DEMO_TIMEOUTS = List.of(
            new Site(0x41F4, "4a78f6146700007e1038"), new Site(0x53BA, "4a78f614670000ac1038"),
            new Site(0x2038F8, "4a78f6146700007a1038"));
    // Both retail headers declare a 2 MiB cartridge range. Each header covers its own half.
    static final List<Site> CHECKSUMS = List.of(new Site(0x18E, "dfb3"), new Site(0x20018E, "a8f2"));

    record Site(int offset, String expectedHex) {}
    record Fragment(int start, int end, String boundarySha256) {
        Span span() { return new Span(start, end); }
    }

    // s3.lst:145612–148240,162547–165949,170092–186565,186583–204101.
    // Lockon S3/LockOn Data.asm:1287–1333 names the includes the locked-on game uses.
    // The boundary digest covers the first and last eight bytes, without storing any song bytes.
    static final List<Fragment> FRAGMENTS = List.of(
            new Fragment(0x2C71A0, 0x2C7FF3, "3bf0418a2dfda0a1245ccf50b66d8eccf5321a975888899f0c271db828a26f6a"),
            new Fragment(0x2CEBF1, 0x2CFFD3, "dbd87f5db22c91f85df3b6ba504455c816a0f3d8cb7fc45be7dcd1a7b9ebdad5"),
            new Fragment(0x2D17A7, 0x2D7FC3, "a3e7ae859b20798a7a6b051bf082d54665619ae4711e1bacbd6a9de182aa0f62"),
            new Fragment(0x2D8000, 0x2D8AE8, "19b54f97ed224f2968412414e62142707e1406a7642703c53f5a43a6eef5cbf2"),
            new Fragment(0x2D97FD, 0x2D99F7, "d059def82ea0d84c846f4dd50c39fb396954a00ba1480c0dc23d787369f42107"),
            new Fragment(0x2DE18F, 0x2DE587, "3a68ee20c6274fcaa81754c682835492f29ef8626413ed0936ad97ada268f056"),
            new Fragment(0x2DEA20, 0x2DF5E4, "eee16af98f27f1555c354df462a4a961d210ad8d291cd7e5fdc962ef57da57f6"),
            new Fragment(0x2DFBFE, 0x2DFEAC, "640517244ee26ef118f253b2e804f4880b8f7ad4d726402e044e674a753bc879"));

    private S3kRevision() {}

    static List<Span> fragments() { return FRAGMENTS.stream().map(Fragment::span).toList(); }

    static byte[] driverRam(byte[] rom) throws IOException {
        byte[] driver = decompress(rom, DRIVER);
        byte[] tables = decompress(rom, TABLES);
        if (driver.length > TABLE_BASE || TABLE_BASE + tables.length != PSG_FREE_START)
            throw new IOException("Unexpected S3K driver and table lengths");
        byte[] ram = new byte[0x2000];
        System.arraycopy(driver, 0, ram, 0, driver.length);
        System.arraycopy(tables, 0, ram, TABLE_BASE, tables.length);
        return ram;
    }

    static byte[] decompress(byte[] rom, int offset) throws IOException {
        try (var input = Channels.newChannel(new ByteArrayInputStream(rom, offset, rom.length - offset))) {
            return KosinskiReader.decompress(input);
        }
    }

    static int dacPointer(int note) { return DAC_BANK * WINDOW + 2 * (note - DAC_FIRST); }
}
