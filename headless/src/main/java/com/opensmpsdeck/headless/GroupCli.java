package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.GroupEdits;
import com.opensmpsdeck.project.GroupPlacement;
import com.opensmpsdeck.project.ProjectFiles;
import com.opensmpsdeck.project.StudioProject;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;

final class GroupCli {
    private GroupCli() {}

    static void run(String[] args, PrintStream out) throws IOException {
        int end = args.length;
        String parent = null;
        if (end >= 5 && args[end - 2].equals("--in")) {
            parent = args[end - 1];
            end -= 2;
        }
        if (end < 4) throw usage();
        String action = args[1];
        Path file = Path.of(args[2]);
        var loaded = ProjectFiles.load(file);
        StudioProject project = loaded.project();
        StudioProject updated = switch (action) {
            case "create" -> {
                if (end < 6) throw usage();
                var selection = new LinkedHashSet<>(Arrays.asList(args).subList(5, end));
                if (selection.size() != end - 5) throw new IllegalArgumentException("Duplicate selected placement ID");
                yield GroupEdits.create(project, parent, args[3], args[4], selection);
            }
            case "place" -> {
                if (end != 7) throw usage();
                yield GroupEdits.place(project, parent, new GroupPlacement(args[4], args[3],
                        tick(args[5]), tick(args[6])));
            }
            case "ungroup" -> {
                if (end != 4) throw usage();
                yield GroupEdits.ungroup(project, parent, args[3]);
            }
            default -> throw usage();
        };
        ProjectFiles.save(file, updated, loaded.revision());
        out.println("Updated " + file + " (" + updated.groupSequences().size() + " group sequences)");
    }

    private static int tick(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException bad) {
            throw new IllegalArgumentException("Expected integer ticks, got '" + value + "'");
        }
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException("use group create FILE GROUP_ID NAME PLACEMENT_ID... [--in GROUP_ID], "
                + "group place FILE GROUP_ID PLACEMENT_ID START LENGTH [--in GROUP_ID], "
                + "or group ungroup FILE PLACEMENT_ID [--in GROUP_ID]");
    }
}
