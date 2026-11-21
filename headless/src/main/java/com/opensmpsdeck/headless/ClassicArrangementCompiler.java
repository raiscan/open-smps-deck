package com.opensmpsdeck.headless;

import com.opensmpsdeck.project.Clip;
import com.opensmpsdeck.project.DacSample;
import com.opensmpsdeck.project.Envelope;
import com.opensmpsdeck.project.GroupArrangement;
import com.opensmpsdeck.project.NativeCommand;
import com.opensmpsdeck.project.ProjectCompiler;
import com.opensmpsdeck.project.ProjectFormat;
import com.opensmpsdeck.project.Sequence;
import com.opensmpsdeck.project.StudioProject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

final class ClassicArrangementCompiler {
    private static final int STOP = 0xF2;
    private static final int REST = 0x80;
    private static final Comparator<Clip> CLIP_ORDER = Comparator.comparingInt(Clip::startTick);

    private final ClassicPackageDriver driver;
    private final StudioProject project;
    private final int tempo;
    private final byte[] bank = new byte[ProjectCompiler.MAX_BANK_BYTES];
    private final TreeSet<Integer> relocations = new TreeSet<>();
    private final Map<String, Sequence> sequences = new HashMap<>();
    private final Map<String, Integer> voices = new HashMap<>();
    private final Map<String, Integer> envelopes = new HashMap<>();
    private final Map<String, Long> sampleTails = new HashMap<>();
    private final Map<String, Integer> sampleNotes = new HashMap<>();
    private int size;

    ClassicArrangementCompiler(ClassicPackageDriver driver, StudioProject project, int tempo) {
        this.driver = driver;
        this.project = project;
        this.tempo = tempo;
    }

    ProjectCompiler.BankLayout compile() {
        ProjectFormat.requireResolved(project);
        ProjectFormat.validate(project);
        driver.validateTempo(tempo);
        project.sequences().forEach(sequence -> sequences.put(sequence.id(), sequence));
        project.voices().forEach(voice -> voices.put(voice.id(), voices.size()));
        for (var envelope : project.envelopes()) {
            driver.require(envelope.type().equals(Envelope.PSG_VOLUME),
                    "MODULATION envelope '" + envelope.id() + "' has no native equivalent");
            ClassicPackageBank.validateEnvelope(driver, envelope);
            envelopes.put(envelope.id(), envelopes.size() + 1);
        }
        for (var sample : project.samples()) {
            sampleTails.put(sample.id(), driver.dacTailFrames(sample));
            sampleNotes.put(sample.id(), DacSample.FIRST_NOTE + sampleNotes.size());
        }

        var clips = GroupArrangement.flatten(project);
        int fm = 1;
        int psg = 0;
        for (var clip : clips) {
            if (clip.lane().startsWith("FM")) fm = Math.max(fm, 1 + clip.lane().charAt(2) - '0');
            if (clip.lane().startsWith("PSG")) psg = Math.max(psg, clip.lane().charAt(3) - '0');
        }
        size = 6 + fm * 4 + psg * 6;
        bank[2] = (byte) fm;
        bank[3] = (byte) psg;
        bank[4] = 1;
        bank[5] = (byte) tempo;
        pointer(6);
        writeDac(onLane(clips, "DAC"));
        for (int lane = 1; lane < fm; lane++) {
            pointer(6 + lane * 4);
            writeLane(onLane(clips, "FM" + lane), "FM" + lane);
        }
        for (int lane = 1; lane <= psg; lane++) {
            pointer(6 + fm * 4 + (lane - 1) * 6);
            writeLane(onLane(clips, "PSG" + lane), "PSG" + lane);
        }
        if (!voices.isEmpty()) {
            pointer(0);
            for (var voice : project.voices())
                for (byte b : driver.voiceBytes(StockTable.bytes(voice.bytes()))) emit(b);
        }
        return new ProjectCompiler.BankLayout(Arrays.copyOf(bank, size), new ArrayList<>(relocations));
    }

    private static List<Clip> onLane(List<Clip> clips, String lane) {
        return clips.stream().filter(clip -> clip.lane().equals(lane)).sorted(CLIP_ORDER).toList();
    }

    private void writeDac(List<Clip> clips) {
        int cursor = 0;
        long lastTail = 0;
        for (var clip : clips) {
            rest(clip.startTick() - cursor);
            var source = sequences.get(clip.sequenceId());
            var triggers = source.triggers().stream()
                    .sorted(Comparator.comparingInt(trigger -> trigger.tick())).toList();
            if (triggers.isEmpty()) {
                rest(clip.lengthTicks());
            } else {
                for (int repeated = 0; repeated < clip.lengthTicks();) {
                    int length = Math.min(clip.lengthTicks() - repeated, source.lengthTicks());
                    int at = 0;
                    for (int i = 0; i < triggers.size() && triggers.get(i).tick() < length; i++) {
                        var trigger = triggers.get(i);
                        rest(trigger.tick() - at);
                        int next = i + 1 < triggers.size() ? Math.min(length, triggers.get(i + 1).tick()) : length;
                        int duration = Math.min(127, next - trigger.tick());
                        emit(sampleNotes.get(trigger.sampleId()), duration);
                        at = trigger.tick() + duration;
                        lastTail = (long) clip.startTick() + repeated + trigger.tick() + sampleTails.get(trigger.sampleId());
                    }
                    rest(length - at);
                    repeated += length;
                }
            }
            cursor = clip.endTick();
        }
        rest(lastTail - cursor);
        emit(STOP);
    }

    private void writeLane(List<Clip> clips, String lane) {
        int cursor = 0;
        var state = new TrackState(lane);
        for (var clip : clips) {
            rest(clip.startTick() - cursor);
            if (clip.startTick() > cursor) state.toneActive = false;
            var source = sequences.get(clip.sequenceId());
            var notes = source.notes().stream().sorted(Comparator.comparingInt(note -> note.tick())).toList();
            var commands = source.commands().stream().sorted(Comparator.comparingInt(command -> command.tick())).toList();
            for (int repeated = 0; repeated < clip.lengthTicks();) {
                if (repeated == 0 || project.arrangementVersion() == 1 || source.repeatState().equals("RESET"))
                    state.reset(source);
                int length = Math.min(clip.lengthTicks() - repeated, source.lengthTicks());
                int at = 0, noteIndex = 0, commandIndex = 0;
                while (at < length) {
                    while (noteIndex < notes.size() && notes.get(noteIndex).tick() + notes.get(noteIndex).duration() <= at)
                        noteIndex++;
                    var note = noteIndex < notes.size() ? notes.get(noteIndex) : null;
                    boolean sounding = note != null && note.tick() <= at;
                    while (commandIndex < commands.size() && commands.get(commandIndex).tick() == at) {
                        var event = commands.get(commandIndex++);
                        state.command(event.command(), event.assetId(), event.arguments(), event.id());
                    }
                    int next = at + Math.min(127, length - at);
                    if (note != null) next = Math.min(next, sounding ? note.tick() + note.duration() : note.tick());
                    if (commandIndex < commands.size()) next = Math.min(next, commands.get(commandIndex).tick());
                    if (!sounding) {
                        emit(REST);
                        state.toneActive = false;
                    } else if (note.tick() == at) {
                        emit(state.pitch(note.pitch(), note.id()));
                        state.toneActive = !state.noise;
                    } else {
                        emit(0xE7);
                    }
                    emit(next - at);
                    at = next;
                }
                repeated += length;
            }
            cursor = clip.endTick();
        }
        emit(STOP);
    }

    private final class TrackState {
        private final String lane;
        private final boolean psg;
        private int attenuation, transpose, envelope, voice = -1;
        private boolean noise, toneActive;
        TrackState(String lane) {
            this.lane = lane;
            psg = lane.startsWith("PSG");
        }

        void reset(Sequence source) {
            driver.require(!noise, "PSG_NOISE cannot reset to tone on " + lane + " at sequence '" + source.id() + "'");
            if (!psg) {
                // Set the target volume before uploading a changed instrument.
                // The initially selected voice is slot zero on both drivers.
                volume(attenuationFor(source.volume()));
                selectVoice(source.voiceId());
                emit(0xE0, 0xC0);
            } else {
                volume(attenuationFor(source.volume()));
                setEnvelope(source.psgEnvelopeId());
            }
            transpose(0);
            if (project.arrangementVersion() >= 2) emit(0xE1, 0, 0xE8, 0, 0xF4);
        }

        int pitch(int midi, String noteId) {
            int low = psg ? 48 : 12;
            int high = psg ? 116 : 106;
            driver.require(midi >= low && midi <= high && midi + transpose >= low && midi + transpose <= high,
                    lane + " note '" + noteId + "' pitch " + midi + " with transpose " + transpose
                            + " is outside " + low + ".." + high);
            return 0x81 + midi - low;
        }

        void command(NativeCommand command, String asset, List<Integer> arguments, String eventId) {
            int value = arguments.isEmpty() ? 0 : arguments.getFirst();
            String feature = command + " at command '" + eventId + "' on " + lane;
            switch (command) {
                case PAN -> emit(0xE0, value);
                case DETUNE -> emit(0xE1, value);
                case VOLUME -> volume(attenuationFor(value));
                case FM_VOLUME -> volume(Math.clamp(attenuation + (byte) value, 0, 127));
                case PSG_VOLUME -> {
                    driver.require(envelope == 0, feature + " cannot reproduce the S3K envelope retrigger");
                    volume(Math.min((attenuation + value) & 0xFF, 15));
                }
                case TRANSPOSE -> transpose((byte) (value - 0x40));
                case TRANSPOSE_ADD -> transpose((byte) (transpose + value));
                case HOLD -> emit(0xE7);
                case MODULATION -> emit(0xF0, value, arguments.get(1), arguments.get(2), arguments.get(3));
                case MODULATION_OFF -> emit(0xF4);
                case PSG_ENVELOPE -> setEnvelope(asset);
                case FM_VOICE -> selectVoice(asset);
                case PSG_NOISE -> {
                    driver.require(lane.equals("PSG3") && value != 0,
                            feature + " requires a nonzero noise command on PSG3");
                    driver.require(!toneActive, feature + " cannot silence a sounding tone when switching to noise");
                    emit(0xF3, value);
                    noise = true;
                }
                case MODULATION_ENVELOPE, FM_VOLUME_ENVELOPE, SSG_EG ->
                        driver.require(false, feature + " has no native equivalent");
            }
        }

        int attenuationFor(int value) {
            return psg ? 15 - ((value & 127) >>> 3) : 127 - (value & 127);
        }

        void volume(int next) {
            if (next != attenuation) {
                if (!psg && driver == ClassicPackageDriver.S1 && voice >= 0) {
                    var bytes = project.voices().get(voice).bytes();
                    for (int i = 21; i < 25; i++)
                        driver.require(bytes.get(i) + next <= 255,
                                "FM volume on " + lane + " overflows an S1 total-level update for voice '"
                                        + project.voices().get(voice).id() + "'");
                }
                emit(psg ? 0xEC : 0xE6, next - attenuation);
                attenuation = next;
            }
        }

        void transpose(int next) {
            if (next != transpose) emit(0xE9, next - transpose);
            transpose = next;
        }

        void setEnvelope(String asset) {
            envelope = asset == null ? 0 : envelopes.get(asset);
            emit(0xF5, envelope);
        }

        void selectVoice(String asset) {
            voice = voices.get(asset);
            emit(0xEF, voice);
        }
    }

    private void rest(long ticks) {
        while (ticks > 0) {
            int duration = (int) Math.min(127, ticks);
            emit(REST, duration);
            ticks -= duration;
        }
    }

    private void pointer(int offset) {
        relocations.add(offset);
        if (driver == ClassicPackageDriver.S1) {
            bank[offset] = (byte) (size >>> 8);
            bank[offset + 1] = (byte) size;
        } else {
            bank[offset] = (byte) size;
            bank[offset + 1] = (byte) (size >>> 8);
        }
    }

    private void emit(int... bytes) {
        driver.require(size + bytes.length <= bank.length, "compiled arrangement exceeds one 32 KiB bank");
        for (int value : bytes) bank[size++] = (byte) value;
    }
}
