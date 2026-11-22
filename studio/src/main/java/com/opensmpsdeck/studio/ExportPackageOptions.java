package com.opensmpsdeck.studio;

import com.opensmpsdeck.headless.ProjectExport;
import com.opensmpsdeck.headless.ProjectExport.Format;
import com.opensmpsdeck.project.TargetGame;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Package-specific choices shared by the Studio export controls. */
record ExportPackageOptions(TargetGame target, boolean retailPatchAvailable) {
    static boolean supports(Format format) {
        return format == Format.PACKAGE || portable(format) || format == Format.DISASM;
    }

    static boolean portable(Format format) {
        return format == Format.PORTABLE || format == Format.MOD;
    }

    ProjectExport.Options options(Format format, Path rom, boolean includeSource, boolean retailPatch,
                                  String license, String replacements, boolean dpcmClosed, String replaceDac) {
        var roms = rom == null ? List.<Path>of() : List.of(rom);
        if (format == Format.DISASM) {
            var kit = new ProjectExport.DisasmSettings(roms, dpcmClosed, ids(replaceDac, true));
            return new ProjectExport.Options(format, null, null, null, null, false, false, null, kit);
        }

        boolean patch = portable(format) && retailPatchAvailable && retailPatch;
        var ids = format == Format.MOD ? ids(replacements, false) : List.<Integer>of();
        if (format == Format.MOD && ids.isEmpty()) throw new IllegalArgumentException("Enter at least one music ID to replace.");
        var settings = new ProjectExport.PortableSettings(target.id(), includeSource && !patch,
                portable(format) && !license.isBlank() ? license.strip() : null, null, null, List.of(), ids,
                null, false, patch, roms);
        return new ProjectExport.Options(format, null, null, null, null, false, false, settings);
    }

    private static List<Integer> ids(String text, boolean hex) {
        if (text.isBlank()) return List.of();
        try {
            return Arrays.stream(text.strip().split("[,\\s]+")).map(value -> {
                if (hex) {
                    if (!value.matches("(?:0[xX]|\\$)?[0-9A-Fa-f]{2}")) throw new NumberFormatException();
                    return Integer.parseInt(value.substring(value.length() - 2), 16);
                }
                if (!value.matches("0|[1-9][0-9]*")) throw new NumberFormatException();
                return Integer.valueOf(value);
            }).toList();
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(hex ? "Enter two-digit hexadecimal DAC notes."
                    : "Enter decimal music IDs.");
        }
    }
}
