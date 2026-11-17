package com.opensmpsdeck.project;

import java.util.List;

/** A native command at a source tick; equal-tick ordering follows the source list. */
public record CommandEvent(String id, int tick, NativeCommand command, List<Integer> arguments) {
    public CommandEvent { arguments=List.copyOf(arguments); }
}
