package com.opensmpsdeck.library.scan;

import java.nio.file.Path;

public record ScanFailure(Path path, String reason) {
}
