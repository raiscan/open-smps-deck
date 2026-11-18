package com.opensmpsdeck.project;

import java.util.List;

/** A native command at a source tick; equal-tick ordering follows the source list. */
public record CommandEvent(String id, int tick, NativeCommand command, List<Integer> arguments, String assetId) {
    public CommandEvent(String id, int tick, NativeCommand command, List<Integer> arguments) {
        this(id, tick, command, arguments, null);
    }

    public CommandEvent { arguments=List.copyOf(arguments); }
}
