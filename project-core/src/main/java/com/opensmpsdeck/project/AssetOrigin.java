package com.opensmpsdeck.project;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.Objects;

/** Provenance of the asset payload, independent of its project ID or playback settings. */
public record AssetOrigin(String type, StockRef ref, List<Transform> transforms,
                          String license, String attribution) {
    public AssetOrigin {
        if (!List.of("original", "source-audio", "stock", "third-party").contains(type))
            throw new IllegalArgumentException("Unknown asset origin: " + type);
        if (transforms != null) transforms = List.copyOf(transforms);
        if ((!type.equals("stock") && ref != null) || (ref == null && transforms != null))
            throw new IllegalArgumentException("Stock transforms require a stock reference");
        if (type.equals("third-party")) {
            if (license == null || license.isBlank() || attribution == null || attribution.isBlank())
                throw new IllegalArgumentException("Third-party origin requires license and attribution");
        } else if (license != null || attribution != null) {
            throw new IllegalArgumentException("Only third-party origin carries license and attribution");
        }
    }

    public static AssetOrigin stock(StockRef ref) {
        return new AssetOrigin("stock", ref, null, null, null);
    }

    public static AssetOrigin sourceAudio() {
        return new AssetOrigin("source-audio", null, null, null, null);
    }

    public AssetOrigin withGain(double gain) {
        if (ref == null || gain == 1) return this;
        return new AssetOrigin(type, ref, List.of(new Transform("gain", Double.toHexString(gain), null, null, null)),
                null, null);
    }

    public AssetOrigin withPatch(List<Integer> before, List<Integer> after) {
        return withPatch(toBytes(before), toBytes(after));
    }

    private static byte[] toBytes(List<Integer> values) {
        byte[] bytes = new byte[values.size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = values.get(i).byteValue();
        return bytes;
    }

    public AssetOrigin withPatch(byte[] before, byte[] after) {
        if (ref == null) return this;
        if (before.length != after.length) return stock(null);
        var changes = new TreeMap<Integer, PatchByte>();
        if (transforms != null)
            for (var transform : transforms)
                for (var value : transform.bytes()) changes.put(value.offset(), value);
        boolean changed = false;
        for (int i = 0; i < before.length; i++) {
            if (before[i] == after[i]) continue;
            changes.put(i, new PatchByte(i, Byte.toUnsignedInt(after[i])));
            changed = true;
        }
        if (!changed) return this;
        return new AssetOrigin(type, ref, List.of(new Transform("patch", null, null, null,
                new ArrayList<>(changes.values()))), null, null);
    }

    public void validateKind(String kind) {
        if (ref != null && !kind.equals(ref.kind()))
            throw new IllegalArgumentException("Stock reference for " + kind + " uses " + ref.table());
        if (transforms == null) return;
        for (Transform transform : transforms) {
            if (kind.equals("DAC_SAMPLE") == transform.op().equals("patch"))
                throw new IllegalArgumentException("Invalid " + transform.op() + " transform for " + kind);
        }
    }

    public record Transform(String op, String factor, Integer start, Integer end, List<PatchByte> bytes) {
        public Transform {
            Objects.requireNonNull(op, "transform op");
            switch (op) {
                case "gain" -> {
                    if (factor == null || start != null || end != null || bytes != null)
                        throw new IllegalArgumentException("Gain requires only a hexadecimal factor");
                    double parsed = Double.parseDouble(factor);
                    if (!Double.isFinite(parsed) || !Double.toHexString(parsed).equals(factor))
                        throw new IllegalArgumentException("Gain factor must use Double.toHexString");
                }
                case "trim" -> {
                    if (factor != null || bytes != null || start == null || end == null || start < 0 || end <= start)
                        throw new IllegalArgumentException("Trim requires 0 <= start < end");
                }
                case "patch" -> {
                    if (factor != null || start != null || end != null || bytes == null)
                        throw new IllegalArgumentException("Patch requires only a byte list");
                    bytes = List.copyOf(bytes);
                    int previous = -1;
                    for (PatchByte value : bytes) {
                        if (value.offset() <= previous)
                            throw new IllegalArgumentException("Patch offsets must be sorted and unique");
                        previous = value.offset();
                    }
                }
                default -> throw new IllegalArgumentException("Unknown stock transform: " + op);
            }
        }
    }

    public record PatchByte(int offset, int value) {
        public PatchByte {
            if (offset < 0 || value < 0 || value > 255)
                throw new IllegalArgumentException("Invalid patch offset or byte");
        }
    }
}
