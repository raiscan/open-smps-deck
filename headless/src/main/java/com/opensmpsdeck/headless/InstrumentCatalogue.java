package com.opensmpsdeck.headless;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.opensmpsdeck.project.AssetOrigin;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.CommandEvent;
import com.opensmpsdeck.project.NoteEvent;
import com.opensmpsdeck.project.StockRef;
import com.opensmpsdeck.project.TargetGame;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Portable index of exact local instrument payloads and their playable variants. */
public final class InstrumentCatalogue {
    public static final int FORMAT_VERSION = 1;
    public static final int PAGE_LIMIT = 100;
    private static final int MAX_INDEX_BYTES = 32 * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();

    public enum Kind { FM_VOICE, PSG_VOLUME, MODULATION, DAC_SAMPLE, PSG_NOISE }

    public record Asset(String id, Kind kind, String name, String payload, Boolean excludeFromDrumFit) {
        public Asset(String id, Kind kind, String name, String payload) {
            this(id, kind, name, payload, null);
        }

        public Asset {
            Objects.requireNonNull(kind, "asset kind");
            requireText(name, "asset name");
            int maximum = switch (kind) {
                case FM_VOICE -> 25;
                case PSG_VOLUME, MODULATION -> 256;
                case DAC_SAMPLE -> 65535;
                case PSG_NOISE -> 1;
            };
            if (payload == null || payload.length() > (maximum + 2) / 3 * 4)
                throw new IllegalArgumentException("Invalid " + kind + " payload length");
            byte[] bytes = Base64.getDecoder().decode(payload);
            int minimum = kind == Kind.FM_VOICE ? 25 : 1;
            if (bytes.length < minimum || bytes.length > maximum
                    || !Base64.getEncoder().encodeToString(bytes).equals(payload))
                throw new IllegalArgumentException("Invalid " + kind + " payload");
            if (!assetId(kind, bytes).equals(id))
                throw new IllegalArgumentException("Asset ID does not match its payload");
        }

        public byte[] bytes() { return Base64.getDecoder().decode(payload); }
    }

    public record Recipe(Integer rate, Integer pan, int volume, int durationTicks, String volumeEnvelope,
                         String modulationEnvelope, List<NoteEvent> notes, List<CommandEvent> commands) {
        public Recipe {
            if (rate != null) checkRange(rate, 0, 255, "DAC rate");
            if (pan != null) checkRange(pan, 0, 255, "Pan");
            checkRange(volume, 0, 127, "Volume");
            checkRange(durationTicks, 1, S3kRenderer.MAX_PRESENTATIONS, "Audition duration");
            notes = List.copyOf(notes);
            commands = List.copyOf(commands);
        }

        public static Recipe plain() {
            return new Recipe(null, null, 127, 60, null, null, List.of(), List.of());
        }
    }

    public record Occurrence(String source, String driver, String revision, String song, Integer songId,
                             Integer slot, String lane, Integer tick, boolean played, String table) {
        public Occurrence(String source, String driver, String revision, String song, Integer songId,
                          Integer slot, String lane, Integer tick, boolean played) {
            this(source, driver, revision, song, songId, slot, lane, tick, played, null);
        }

        public Occurrence {
            requireText(source, "source");
            requireText(driver, "driver");
            requireText(revision, "source revision");
        }
    }

    public record Variant(String id, String assetId, String name, Recipe recipe,
                          List<String> roles, List<Occurrence> occurrences, String driver) {
        public Variant {
            if (driver == null) driver = TargetGame.S3K.id();
            TargetGame target = TargetGame.parse(driver);
            requireText(assetId, "asset ID");
            requireText(name, "variant name");
            Objects.requireNonNull(recipe, "recipe");
            if (!variantId(assetId, recipe, target).equals(id))
                throw new IllegalArgumentException("Variant ID does not match its recipe");
            roles = List.copyOf(roles);
            occurrences = List.copyOf(occurrences);
            if (occurrences.stream().anyMatch(usage -> !target.id().equalsIgnoreCase(usage.driver())))
                throw new IllegalArgumentException("Occurrence driver differs from its variant driver");
        }

        public TargetGame target() { return TargetGame.parse(driver); }
    }

    public record Issue(String source, String reason, String detail) {}

    public record Query(String text, Kind kind, String driver, String role, String source, String variant,
                        int offset, int limit) {
        public Query {
            checkRange(offset, 0, Integer.MAX_VALUE, "Offset");
            checkRange(limit, 1, PAGE_LIMIT, "Limit");
        }
    }

    public record Match(Asset asset, Variant variant) {}
    public record Page(int formatVersion, int total, int offset, List<Match> items) {
        public Page { items = List.copyOf(items); }
    }

    private record Index(int formatVersion, int indexedSongs, List<Asset> assets, List<Variant> variants,
                         List<Issue> issues) {}

    private final Index index;
    private final Map<String, Asset> assets;
    private final Map<String, Variant> variants;
    private final Map<String, List<Variant>> assetVariants;
    private final List<Match> sortedMatches;

    private final TargetGame scope;

    private InstrumentCatalogue(Index source) {
        this(source, null);
    }

    private InstrumentCatalogue(Index source, TargetGame scope) {
        this.scope = scope;
        if (source.formatVersion() != FORMAT_VERSION)
            throw new IllegalArgumentException("Unsupported catalogue version " + source.formatVersion());
        checkRange(source.indexedSongs(), 0, Integer.MAX_VALUE, "Indexed song count");
        index = new Index(FORMAT_VERSION, source.indexedSongs(), List.copyOf(source.assets()),
                List.copyOf(source.variants()), List.copyOf(source.issues()));
        assets = new LinkedHashMap<>();
        variants = new LinkedHashMap<>();
        assetVariants = new LinkedHashMap<>();
        for (Asset asset : index.assets()) {
            if (assets.putIfAbsent(asset.id(), asset) != null)
                throw new IllegalArgumentException("Duplicate asset " + asset.id());
        }
        var matches = new ArrayList<Match>();
        for (Variant variant : index.variants()) {
            if (variants.putIfAbsent(variant.id(), variant) != null)
                throw new IllegalArgumentException("Duplicate variant " + variant.id());
            Asset asset = asset(variant.assetId());
            if ((asset.kind() == Kind.DAC_SAMPLE) != (variant.recipe().rate() != null))
                throw new IllegalArgumentException("Only DAC variants carry a native rate");
            requireKind(variant.recipe().volumeEnvelope(), Kind.PSG_VOLUME);
            requireKind(variant.recipe().modulationEnvelope(), Kind.MODULATION);
            assetVariants.computeIfAbsent(asset.id(), ignored -> new ArrayList<>()).add(variant);
            matches.add(new Match(asset, variant));
        }
        // Keep pagination stable even when the scan added assets in a different order.
        matches.sort(Comparator.comparing((Match row) -> row.asset().kind())
                .thenComparing(row -> row.variant().name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(row -> row.variant().id()));
        sortedMatches = List.copyOf(matches);
    }

    public static InstrumentCatalogue open(Path path) throws IOException {
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(MAX_INDEX_BYTES + 1);
            if (bytes.length > MAX_INDEX_BYTES) throw new IOException("Catalogue exceeds the 32 MiB source limit");
            try {
                var text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                return new InstrumentCatalogue(Objects.requireNonNull(JSON.fromJson(text, Index.class), "catalogue"));
            } catch (JsonParseException | IllegalArgumentException | NullPointerException failure) {
                throw new IOException("Invalid instrument catalogue: " + failure.getMessage(), failure);
            }
        }
    }

    public void save(Path path) throws IOException {
        byte[] bytes = (JSON.toJson(index) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_INDEX_BYTES) throw new IOException("Catalogue exceeds the 32 MiB source limit");
        var output = Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE);
        boolean complete = false;
        try {
            try (output) { output.write(bytes); }
            complete = true;
        } finally {
            if (!complete) Files.deleteIfExists(path);
        }
    }

    private record OriginKey(String id, TargetGame target) {}

    private final Map<OriginKey, java.util.Optional<AssetOrigin>> origins =
            new java.util.concurrent.ConcurrentHashMap<>();

    AssetOrigin origin(Asset asset) {
        return origin(asset, scope);
    }

    AssetOrigin origin(Asset asset, TargetGame target) {
        return origins.computeIfAbsent(new OriginKey(asset.id(), target),
                ignored -> java.util.Optional.ofNullable(findOrigin(asset, target))).orElse(null);
    }

    private AssetOrigin findOrigin(Asset asset, TargetGame target) {
        var choices = assetVariants.getOrDefault(asset.id(), List.of());
        byte[] bytes = asset.bytes();
        String hash = digest(bytes);
        String s2VoiceHash = asset.kind() == Kind.FM_VOICE
                ? digest(ClassicPackageDriver.S2.voiceBytes(bytes.clone())) : hash;
        StockRef best = null;
        boolean stock = false;
        for (var variant : choices) {
            if (target != null && target != variant.target()) continue;
            for (var usage : variant.occurrences()) {
                String game = variant.driver();
                boolean retailSong = usage.songId() != null;
                if (retailSong) stock = true;
                if (usage.table() == null || usage.slot() == null) continue;
                String table = switch (usage.table()) {
                    case "global-fm", "local-fm" -> retailSong ? usage.table() : null;
                    case "dac" -> retailSong || variant.target() != TargetGame.S3K ? "dac" : null;
                    case "psg-envelope" -> retailSong ? "psg" : null;
                    case "modulation" -> retailSong ? "mod" : null;
                    case "psg" -> "psg";
                    default -> null;
                };
                if (table == null) continue;
                try {
                    var candidate = StockTable.ref(game, table, table.equals("local-fm") ? usage.songId() : null,
                            usage.slot(), game.equals("s2") ? s2VoiceHash : hash);
                    if (candidate.kind().equals(asset.kind().name()))
                        best = best == null ? candidate : StockTable.prefer(best, candidate);
                } catch (IllegalArgumentException invalidLocator) {
                    // Older catalogues may only identify the source song.
                }
            }
        }
        if (best == null) {
            best = target == null || target == TargetGame.S3K ? StockTable.matchProjectAsset(asset.kind().name(), bytes)
                    : StockTable.match(asset.kind().name(), target == TargetGame.S2 && asset.kind() == Kind.FM_VOICE
                            ? ClassicPackageDriver.S2.voiceBytes(bytes) : bytes, target.id());
        }
        return stock || best != null ? AssetOrigin.stock(best) : null;
    }

    static InstrumentCatalogue open(Path path, TargetGame target) throws IOException {
        return open(path).forTarget(target);
    }

    public InstrumentCatalogue forTarget(TargetGame target) {
        if (scope == target) return this;
        var filtered = index.variants().stream().filter(variant -> variant.target() == target).toList();
        // Preserve unbound assets from legacy S3K scans. They are available for
        // inspection or use as dependencies even when no audition was indexed.
        if (target == TargetGame.S3K && filtered.size() == index.variants().size()) return this;
        var selected = new java.util.HashSet<String>();
        for (var variant : filtered) {
            selected.add(variant.assetId());
            var recipe = variant.recipe();
            if (recipe.volumeEnvelope() != null) selected.add(recipe.volumeEnvelope());
            if (recipe.modulationEnvelope() != null) selected.add(recipe.modulationEnvelope());
            for (var command : recipe.commands())
                if (command.assetId() != null) selected.add(command.assetId());
        }
        return new InstrumentCatalogue(new Index(FORMAT_VERSION, indexedSongs(), assets().stream()
                .filter(asset -> selected.contains(asset.id())).toList(), filtered, issues()), target);
    }

    static InstrumentCatalogue openReconstruction(Path path, TargetGame target) throws IOException {
        var catalogue = open(path, target);
        var variants = catalogue.variants().stream().filter(variant -> {
            var asset = catalogue.asset(variant.assetId());
            return switch (asset.kind()) {
                case PSG_VOLUME -> S3kReconstruction.safePsg(asset.bytes(), target)
                        && catalogue.origin(asset, target) != null;
                case FM_VOICE, MODULATION -> catalogue.origin(asset, target) != null;
                default -> true;
            };
        }).toList();
        if (variants.size() == catalogue.variants().size()) return catalogue;
        // Also remove the unavailable instrument payloads from asset-ID lookups.
        var rejected = catalogue.variants().stream().map(Variant::assetId)
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
        for (var variant : variants) {
            rejected.remove(variant.assetId());
            rejected.remove(variant.recipe().volumeEnvelope());
            rejected.remove(variant.recipe().modulationEnvelope());
            for (var command : variant.recipe().commands()) rejected.remove(command.assetId());
        }
        return new InstrumentCatalogue(new Index(FORMAT_VERSION, catalogue.indexedSongs(),
                catalogue.assets().stream().filter(asset -> !rejected.contains(asset.id())).toList(),
                variants, catalogue.issues()), target);
    }

    private record ContourKey(TargetGame target, List<Integer> contour) {}
    private final Map<ContourKey, java.util.Optional<Envelope>> stockContours =
            new java.util.concurrent.ConcurrentHashMap<>();

    Envelope stockContour(TargetGame target, Envelope shape) {
        var key = new ContourKey(target, shape.bytes());
        return stockContours.computeIfAbsent(key, ignored -> {
            var desired = contour(shape.bytes(), false);
            return variants().stream().filter(variant -> variant.target() == target)
                    .map(variant -> asset(variant.assetId())).distinct()
                    .filter(asset -> asset.kind() == Kind.PSG_VOLUME && S3kReconstruction.safePsg(asset.bytes(), target))
                    .filter(asset -> origin(asset, target) != null)
                    .min(Comparator.comparingDouble((Asset asset) -> {
                        var levels = contour(StockAssets.unsigned(asset.bytes()), target != TargetGame.S3K);
                        double error = 0;
                        for (int i = 0; i < desired.length; i++) error += Math.pow(desired[i] - levels[i], 2);
                        return error;
                    }).thenComparing(Asset::id))
                    .map(asset -> new Envelope(asset.id(), asset.name(), Envelope.PSG_VOLUME,
                            StockAssets.unsigned(asset.bytes()), origin(asset, target)));
        }).orElse(null);
    }

    private static double[] contour(List<Integer> program, boolean classic) {
        double[] levels = new double[16];
        int cursor = 0, attenuation = 0;
        for (int i = 0; i < levels.length; i++) {
            if (cursor < program.size()) {
                int value = program.get(cursor);
                if (value == 0x80 && !classic && cursor > 0) value = program.get(cursor = 0);
                if (value < 0x80) {
                    attenuation = value;
                    cursor++;
                } else if (value == 0x83) {
                    attenuation = 15;
                }
            }
            levels[i] = attenuation >= 15 ? 0 : Math.pow(10, -attenuation / 10.0);
        }
        return levels;
    }

    public List<Asset> assets() { return index.assets(); }
    public List<Variant> variants() { return index.variants(); }
    public List<Issue> issues() { return index.issues(); }
    public int indexedSongs() { return index.indexedSongs(); }

    public Asset asset(String id) {
        Asset asset = assets.get(id);
        if (asset == null) throw new IllegalArgumentException("Unknown asset " + id);
        return asset;
    }

    public Match match(String id) {
        return match(id, null);
    }

    Match match(String id, TargetGame target) {
        Variant exact = variants.get(id);
        if (exact != null) return new Match(asset(exact.assetId()), exact);
        var choices = assetVariants.get(id);
        if (choices == null) throw new IllegalArgumentException("Unknown catalogue asset or variant " + id);
        if (target != null && choices.size() > 1) {
            var preferred = choices.stream().filter(variant -> variant.target() == target).toList();
            if (!preferred.isEmpty()) choices = preferred;
        }
        if (choices.size() != 1)
            throw new IllegalArgumentException("Asset has " + choices.size() + " playback variants. Choose a variant ID.");
        return new Match(asset(id), choices.getFirst());
    }

    public Page search(Query query) {
        return page(query, matching(query));
    }

    List<Match> matching(Query query) {
        return sortedMatches.stream().filter(row -> matches(row, query)).toList();
    }

    static Page page(Query query, List<Match> matches) {
        int start = Math.min(query.offset(), matches.size());
        int end = start + Math.min(query.limit(), matches.size() - start);
        return new Page(FORMAT_VERSION, matches.size(), query.offset(), matches.subList(start, end));
    }

    private void requireKind(String id, Kind kind) {
        if (id != null && asset(id).kind() != kind)
            throw new IllegalArgumentException("Expected " + kind + " for " + id);
    }

    private static boolean matches(Match row, Query query) {
        Variant variant = row.variant();
        if (query.kind() != null && row.asset().kind() != query.kind()) return false;
        if (!contains(variant.id(), query.variant())) return false;
        if (!contains(variant.driver(), query.driver())) return false;
        if (!empty(query.role()) && variant.roles().stream().noneMatch(role -> contains(role, query.role()))
                && !observedFmRoles(row).contains(query.role().strip().toLowerCase(Locale.ROOT))) return false;
        if (!empty(query.source()) && variant.occurrences().stream().noneMatch(o -> matchesSource(o, query.source())))
            return false;
        if (empty(query.text())) return true;
        return contains(variant.name(), query.text()) || contains(row.asset().name(), query.text())
                || contains(variant.id(), query.text()) || contains(row.asset().id(), query.text())
                || variant.roles().stream().anyMatch(role -> contains(role, query.text()))
                || variant.occurrences().stream().anyMatch(o -> matchesSource(o, query.text()));
    }

    /** Musical role hints from observed FM lane usage, including in existing indices. */
    static List<String> observedFmRoles(Match row) {
        if (row.asset().kind() != Kind.FM_VOICE) return List.of();
        return row.variant().occurrences().stream().filter(Occurrence::played)
                .map(Occurrence::lane).filter(Objects::nonNull)
                .map(lane -> switch (lane.toUpperCase(Locale.ROOT)) {
                    case "FM1" -> "lead";
                    case "FM2" -> "bass";
                    case "FM3", "FM4", "FM5" -> "backing";
                    default -> "";
                }).filter(role -> !role.isEmpty()).distinct().sorted().toList();
    }

    private static boolean matchesSource(Occurrence occurrence, String query) {
        return contains(occurrence.source(), query) || contains(occurrence.song(), query);
    }

    private static boolean contains(String value, String query) {
        return empty(query) || (value != null && value.toLowerCase(Locale.ROOT)
                .contains(query.strip().toLowerCase(Locale.ROOT)));
    }

    private static boolean empty(String text) { return text == null || text.isBlank(); }

    public static final class Builder {
        private record PendingVariant(String assetId, String name, Recipe recipe, TargetGame target,
                                      LinkedHashSet<Occurrence> usages) {}

        private final Map<String, Asset> assets = new LinkedHashMap<>();
        private final Map<String, PendingVariant> variants = new LinkedHashMap<>();
        private final LinkedHashSet<Issue> issues = new LinkedHashSet<>();
        private int indexedSongs;

        public String asset(Kind kind, String name, byte[] bytes) {
            String id = assetId(kind, bytes);
            assets.computeIfAbsent(id, ignored -> new Asset(id, kind, name, Base64.getEncoder().encodeToString(bytes)));
            return id;
        }

        public String variant(String assetId, String name, Recipe recipe, Occurrence occurrence) {
            TargetGame target = occurrence == null ? TargetGame.S3K
                    : TargetGame.parse(occurrence.driver().toLowerCase(Locale.ROOT));
            return variant(assetId, name, recipe, target, occurrence);
        }

        public String variant(String assetId, String name, Recipe recipe, TargetGame target, Occurrence occurrence) {
            if (!assets.containsKey(assetId)) throw new IllegalArgumentException("Unknown asset " + assetId);
            String id = variantId(assetId, recipe, target);
            var variant = variants.computeIfAbsent(id,
                    ignored -> new PendingVariant(assetId, name, recipe, target, new LinkedHashSet<>()));
            if (occurrence != null) variant.usages().add(occurrence);
            return id;
        }

        public void issue(String source, String reason, String detail) { issues.add(new Issue(source, reason, detail)); }
        public void song() { indexedSongs++; }

        public InstrumentCatalogue build() {
            var playable = variants.entrySet().stream().map(entry -> {
                var pending = entry.getValue();
                return new Variant(entry.getKey(), pending.assetId(), pending.name(), pending.recipe(),
                        roles(assets.get(pending.assetId())), List.copyOf(pending.usages()), pending.target().id());
            }).toList();
            return new InstrumentCatalogue(new Index(FORMAT_VERSION, indexedSongs,
                    List.copyOf(assets.values()), playable, List.copyOf(issues)));
        }
    }

    private static List<String> roles(Asset asset) {
        return switch (asset.kind()) {
            case FM_VOICE -> List.of("tone", "algorithm-" + (asset.bytes()[0] & 7));
            case PSG_VOLUME -> List.of("volume-envelope");
            case MODULATION -> List.of("pitch-modulation");
            case DAC_SAMPLE -> List.of("one-shot");
            case PSG_NOISE -> List.of("noise", (asset.bytes()[0] & 4) != 0 ? "white-noise" : "periodic-noise",
                    (asset.bytes()[0] & 3) == 3 ? "tone-linked" : "fixed-clock");
        };
    }

    static String assetId(Kind kind, byte[] bytes) {
        return kind.name().toLowerCase(Locale.ROOT).replace('_', '-') + "-" + digest(bytes);
    }

    private static String variantId(String assetId, Recipe recipe, TargetGame target) {
        String identity = assetId + "\n" + JSON.toJson(recipe);
        if (target != TargetGame.S3K) identity = target.id() + "\n" + identity;
        return "variant-" + digest(identity.getBytes(StandardCharsets.UTF_8));
    }

    static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void requireText(String value, String what) {
        if (empty(value)) throw new IllegalArgumentException(what + " is required");
    }

    private static void checkRange(int value, int minimum, int maximum, String what) {
        if (value < minimum || value > maximum)
            throw new IllegalArgumentException(what + " must be in " + minimum + ".." + maximum);
    }

    static String json(Object value) { return JSON.toJson(value); }
}
