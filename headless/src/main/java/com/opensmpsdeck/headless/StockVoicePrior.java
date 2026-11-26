package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.TargetGame;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.opensmpsdeck.headless.ReconstructionRoles.Role;

/** Tie-breaking preferences for stock FM sounds with isolated S3K source stems. */
final class StockVoicePrior {
    static final String REASON_PREFIX = "Chose ";
    private static final double NAME_WEIGHT = .025;
    private static final double USAGE_WEIGHT = .015;

    enum Family { bass, guitar, keyboard, synth, lead, strings, brass, organ, bells, drums }

    record Evidence(String voice, String nameMatch, double name, Double usage) {
        double preference() { return name + (usage == null ? 0 : usage); }
    }

    private final Family family;
    private final Role role;
    final int pitch;
    final boolean sustained;
    private final Map<String, Evidence> evidence = new HashMap<>();

    private StockVoicePrior(Family family, Role role, int pitch, boolean sustained) {
        this.family = family;
        this.role = role;
        this.pitch = pitch;
        this.sustained = sustained;
    }

    static StockVoicePrior forNotes(ReconstructionStages.Score score, List<ReconstructionAllocation.Entry> notes,
                                    Path mix, Map<Integer, Path> stems, Role role) {
        if (!score.inputs().targetGame().s3kRules() || role == Role.drums) return null;
        int part = notes.getFirst().note().part();
        Path stem = stems.get(part);
        if (stem == null || stem.equals(mix) || notes.stream().anyMatch(n -> n.note().part() != part)) return null;
        var provenance = score.inputs().audioOnly();
        var source = provenance == null ? null : provenance.stems().get(part);
        var label = score.source().parts().stream().filter(item -> item.id() == part)
                .map(MidiScore.Part::name).findFirst().orElse("");
        // An explicit instrument role takes precedence. A generic lead role
        // still allows labels such as synth or keyboard to identify the sound.
        Family family = source == null ? null : stemFamily(source.role());
        if (family == null || family == Family.lead) {
            var labelled = stemFamily(source == null ? label : source.label());
            if (labelled != null) family = labelled;
        }
        if (family == null && provenance == null) return null;
        var pitches = notes.stream().map(n -> n.note().pitch()).sorted().toList();
        int pitch = score.profile().fm().clamp(pitches.get(pitches.size() / 2));
        boolean sustained = notes.stream().anyMatch(n -> n.endTick() - n.startTick() >= 12);
        return new StockVoicePrior(family, role, pitch, sustained);
    }

    Evidence evidence(InstrumentCatalogue catalogue, InstrumentCatalogue.Variant variant) {
        return evidence.computeIfAbsent(variant.assetId(), ignored -> {
            var labels = InstrumentCatalogue.labels(catalogue.asset(variant.assetId()));
            String voice = labels.isEmpty() ? variant.name() : labels.getFirst().ref() + " “" + labels.getFirst().text() + "”";
            Family wanted = family == null ? role == Role.bass ? Family.bass : null : family;
            boolean compatible = wanted != null && labels.stream()
                    .anyMatch(label -> nameFamilies(label.text()).contains(wanted));
            double name = compatible ? NAME_WEIGHT : wanted != null && !labels.isEmpty() ? -NAME_WEIGHT / 2 : 0;
            var uses = catalogue.retailFmUsage(variant.assetId());
            Double usage = uses.isEmpty() ? null : usage(uses);
            String nameMatch = compatible ? "name match" : name < 0 ? "name mismatch" : "neutral name";
            return new Evidence(voice, nameMatch, name, usage);
        });
    }

    private double usage(List<InstrumentCatalogue.FmUsage> uses) {
        double score = 0, weight = 0;
        for (var use : uses) {
            if (use.notes() < 1) continue;
            double confidence = Math.min(1, use.notes() / 8.0);
            int distance = Math.max(use.lowMidi() - pitch, pitch - use.highMidi());
            double register = 1 - Math.clamp(Math.max(0, distance) / 6.0
                    + Math.abs(pitch - use.medianMidi()) / 24.0, 0, 2);
            boolean matchingRole = switch (role) {
                case bass -> "bass".equals(use.role());
                case melody, counter -> "lead".equals(use.role());
                case harmony -> "chord".equals(use.role());
                default -> false;
            };
            double roleFit = matchingRole ? 1 : "percussive".equals(use.role()) || role == Role.bass
                    || "bass".equals(use.role()) ? -1 : 0;
            if (sustained && use.medianFrames() <= 5) roleFit = Math.min(0, roleFit);
            score += confidence * (register + roleFit) / 2;
            weight += confidence;
        }
        return USAGE_WEIGHT * score / Math.max(1, weight);
    }

    String reason(InstrumentCatalogue catalogue, String variant, double acoustic, String method) {
        var chosen = evidence(catalogue, catalogue.match(variant).variant());
        String part = family == null ? role.name() : family.name();
        String retail = chosen.usage() == null ? "no retail usage recorded"
                : String.format(Locale.ROOT, "retail use %+.3f", chosen.usage());
        return REASON_PREFIX + String.format(Locale.ROOT,
                "%s for %s · %s (%+.3f) · %s · %s acoustic loss %.5g",
                chosen.voice(), part, chosen.nameMatch(), chosen.name(), retail, method, acoustic);
    }

    static Family stemFamily(String label) {
        var words = words(label);
        if (any(words, "vocals", "vocal", "voice", "lead")) return Family.lead;
        if (words.contains("bass")) return Family.bass;
        if (words.contains("guitar")) return Family.guitar;
        if (any(words, "organ", "organs")) return Family.organ;
        if (any(words, "piano", "keys", "keyboard", "keyboards")) return Family.keyboard;
        if (any(words, "strings", "string", "violin", "cello")) return Family.strings;
        if (any(words, "brass", "trumpet", "horn", "trombone")) return Family.brass;
        if (any(words, "bell", "bells", "mallets", "xylophone")) return Family.bells;
        if (any(words, "drum", "drums", "percussion")) return Family.drums;
        if (any(words, "synth", "synths", "synthesizer", "synthesizers")) return Family.synth;
        return null;
    }

    static Set<Family> nameFamilies(String name) {
        var words = words(name);
        var families = java.util.EnumSet.noneOf(Family.class);
        if (words.contains("bass")) families.add(Family.bass);
        if (any(words, "picked", "slap", "strike", "harpsichord")) families.add(Family.guitar);
        if (any(words, "piano", "harpsichord", "organ")) families.add(Family.keyboard);
        if (words.contains("organ")) families.add(Family.organ);
        if (any(words, "bell", "xylophone")) families.add(Family.bells);
        if (any(words, "brass", "trumpet", "horn")) families.add(Family.brass);
        if (any(words, "synth", "metallic")) families.add(Family.synth);
        if (any(words, "lead", "flute", "bassoon", "trumpet")) families.add(Family.lead);
        if (words.contains("harpsichord") || words.contains("picked")) families.add(Family.strings);
        if (words.contains("block")) families.add(Family.drums);
        return families;
    }

    private static Set<String> words(String value) {
        return Set.copyOf(List.of(value.toLowerCase(Locale.ROOT).split("[^a-z]+")));
    }

    private static boolean any(Set<String> words, String... values) {
        for (String value : values) if (words.contains(value)) return true;
        return false;
    }
}
