package com.opensmpsdeck.headless;

import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsNoteListener;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencer.Track;
import com.openggf.audio.smps.SmpsSequencer.TrackType;
import com.openggf.data.Rom;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsData;
import com.opensmpsdeck.headless.InstrumentCatalogue.Builder;
import com.opensmpsdeck.headless.InstrumentCatalogue.Kind;
import com.opensmpsdeck.headless.InstrumentCatalogue.Occurrence;
import com.opensmpsdeck.headless.InstrumentCatalogue.Recipe;
import com.opensmpsdeck.project.CompiledProject;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.TargetGame;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads classic driver resources and observes them in the native sequencer. */
final class ClassicCatalogueSources {
    private static final int ROM_PREVIEW_FRAMES = 3600;
    private static final int VOICE_BYTES = 25;

    private ClassicCatalogueSources() {}

    static void scanRom(TargetGame game, Rom rom, byte[] image, String source, String revision, Builder catalogue)
            throws InterruptedException {
        var driver = TargetPlayback.classic(game);
        boolean s1 = game == TargetGame.S1;
        var stock = new ClassicStockAssets(s1, rom, image);
        var dac = stock.dac();
        var scan = new Scan(game, source, revision, catalogue);
        int psgCount = s1 ? 9 : Sonic2RomEnvelopes.COUNT;
        for (int slot = 1; slot <= psgCount; slot++)
            scan.envelopes.put(slot, scan.declare(Kind.PSG_VOLUME, "PSG %02X".formatted(slot),
                    stock.envelope(slot), Recipe.plain(), slot, "psg", null, null));

        // The loader retains all note-to-rate aliases, including S1's timpani pitches.
        for (int note = DacSample.FIRST_NOTE; note <= (s1 ? 0x8B : 0x91); note++)
            scan.declareDac(dac, note, "DAC %02X".formatted(note), "dac");

        Map<Integer, String> titles = s1 ? Sonic1Music.titleMap() : Sonic2Music.titleMap();
        for (int id : driver.musicIds().stream().sorted().toList()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String title = titles.get(id);
            try {
                var data = stock.song(id);
                if (data == null) {
                    catalogue.issue(source, "song-import", "%02X %s: missing music data".formatted(id, title));
                    continue;
                }
                if (data instanceof Sonic2SmpsData s2) s2.setPsgEnvelopes(stock.sonic2Envelopes());
                scan.song(driver, data, dac, ClassicStockAssets.voiceCount(data), ROM_PREVIEW_FRAMES, title, id, List.of());
            } catch (IllegalArgumentException failure) {
                catalogue.issue(source, "song-import", "%02X %s: %s".formatted(id, title, failure.getMessage()));
            }
        }
    }

    static void scanBank(TargetGame game, CompiledProject bank, int voiceCount, int frames,
                         String source, String revision, String title, List<String> voiceNames, Builder catalogue)
            throws InterruptedException {
        var driver = TargetPlayback.classic(game);
        var data = driver.playbackData(bank);
        var dac = driver.dac(bank);
        var scan = new Scan(game, source, revision, catalogue);
        var envelopes = new HashMap<Integer, Envelope>();
        if (bank.nativeProgram() == null) {
            for (var envelope : bank.envelopes()) envelopes.put(envelopes.size() + 1, envelope);
        } else {
            var byId = new HashMap<String, Envelope>();
            for (var envelope : bank.envelopes()) byId.put(envelope.id(), envelope);
            for (var slot : bank.nativeProgram().envelopeSlots()) envelopes.put(slot.slot(), byId.get(slot.assetId()));
        }
        envelopes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            int slot = entry.getKey();
            var envelope = entry.getValue();
            scan.envelopes.put(slot, scan.declare(Kind.PSG_VOLUME, envelope.name(), StockTable.bytes(envelope.bytes()),
                    Recipe.plain(), slot, "psg-envelope", title, null));
        });
        if (bank.nativeProgram() == null) {
            for (int i = 0; i < bank.samples().size(); i++)
                scan.declareDac(dac, DacSample.FIRST_NOTE + i, bank.samples().get(i).name(), "project-dac");
        } else {
            var names = new HashMap<String, String>();
            for (var sample : bank.samples()) names.put(sample.id(), sample.name());
            for (var binding : bank.nativeProgram().dacSlots())
                scan.declareDac(dac, binding.note(), names.get(binding.sampleId()), "package-dac");
        }
        scan.song(driver, data, dac, voiceCount, frames, title, null, voiceNames);
    }

    private record Reference(String asset, String name, int slot, String table, Recipe recipe) {}
    private record Heard(Reference reference, String lane) {}

    private record Scan(TargetGame game, String source, String revision, Builder catalogue,
                        Map<Integer, Reference> envelopes, Map<Integer, Reference> samples) {
        Scan(TargetGame game, String source, String revision, Builder catalogue) {
            this(game, source, revision, catalogue, new HashMap<>(), new HashMap<>());
        }

        Reference declare(Kind kind, String name, byte[] bytes, Recipe recipe, int slot, String table,
                          String title, Integer songId) {
            var ref = new Reference(catalogue.asset(kind, name, bytes), name, slot, table, recipe);
            record(ref, title, songId, null, null);
            return ref;
        }

        void declareDac(DacData dac, int note, String name, String table) {
            byte[] pcm = S3kPackageDriver.dacPayload(dac, note);
            if (pcm == null) return;
            var recipe = new Recipe(dac.mappingForNote(note).rate(), null, 127, 60, null, null, List.of(), List.of());
            samples.put(note, declare(Kind.DAC_SAMPLE, name, pcm, recipe, note, table, null, null));
        }

        void record(Reference ref, String title, Integer songId, String lane, Integer frame) {
            catalogue.variant(ref.asset(), ref.name(), ref.recipe(), new Occurrence(
                    source, game.id(), revision, title, songId, ref.slot(), lane, frame, lane != null, ref.table()));
        }

        void song(ClassicPackageDriver driver, AbstractSmpsData data, DacData dac, int voiceCount,
                  int frames, String title, Integer songId, List<String> voiceNames) throws InterruptedException {
            var voices = new HashMap<Integer, Reference>();
            int start = ClassicStockAssets.voiceOffset(data);
            byte[] bank = data.getData();
            for (int slot = 0; slot < voiceCount; slot++) {
                int offset = start + slot * VOICE_BYTES;
                // S1's loader swaps on getVoice(). Read the bank directly and
                // swap only S2, whose stored order differs from editable order.
                byte[] voice = driver.voiceBytes(Arrays.copyOfRange(bank, offset, offset + VOICE_BYTES));
                String name = slot < voiceNames.size() ? voiceNames.get(slot) : "%s FM %02X".formatted(title, slot);
                voices.put(slot, declare(Kind.FM_VOICE, name, voice, Recipe.plain(), slot, "local-fm", title, songId));
            }
            catalogue.song();

            var observer = new Observer(this, title, songId);
            var sequencer = new SmpsSequencer(data, dac, () -> observer.restored = true, driver.config);
            sequencer.setFallbackVoiceData(data);
            sequencer.setNoteListener(observer);
            while (observer.frame < frames && !sequencer.isComplete() && !observer.restored) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                sequencer.serviceOuterFrame();
                for (Track track : sequencer.getTracks()) {
                    if (!track.active || track.resting || track.overridden || track.note < 0x81) continue;
                    String lane = track.type.name() + (track.channelId + 1);
                    if (track.type == TrackType.FM && track.voiceData != null)
                        observer.heard(voices.get(track.voiceId), lane);
                    if (track.type == TrackType.PSG && track.envData != null)
                        observer.heard(envelopes.get(track.instrumentId), lane);
                }
                observer.frame++;
            }
            if (observer.restored) catalogue.issue(source, "restore-context",
                    title + ": scan ended at the request to restore prior music");
        }
    }

    private static final class Observer implements SmpsNoteListener {
        final Scan scan;
        final String title;
        final Integer songId;
        final Set<Heard> seen = new HashSet<>();
        int frame;
        boolean restored;

        Observer(Scan scan, String title, Integer songId) {
            this.scan = scan;
            this.title = title;
            this.songId = songId;
        }

        void heard(Reference ref, String lane) {
            if (ref != null && seen.add(new Heard(ref, lane))) scan.record(ref, title, songId, lane, frame);
        }

        @Override public void attack(Track track) {
            if (track.type == TrackType.DAC) heard(scan.samples().get(track.note), "DAC");
        }

        @Override public void release(Track track) {}
    }
}
