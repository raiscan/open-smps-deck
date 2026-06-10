package com.opensmps.smps;

import java.util.logging.Logger;

/**
 * Sonic 3 &amp; Knuckles coordination flag handler.
 *
 * <p>S3K uses a modified SMPS Z80 Type 2 driver with significantly different
 * coordination flag assignments compared to S2. This handler intercepts flags
 * E0-FF and dispatches them according to the S3K DefCFlag.txt definitions.
 * Ported from the sonic-engine (OpenGGF) Sonic3kCoordFlagHandler with the
 * game-specific pieces (SFX coupling, previous-music restore) stubbed out —
 * the tracker plays music only.
 *
 * <p>Key differences from S2:
 * <ul>
 *   <li>E3 = TRK_END (mute), not Return</li>
 *   <li>E9 = SPINDASH_REV with 0 params (S2 has 1 param)</li>
 *   <li>F9 = RETURN (S2 has F9 = SND_OFF)</li>
 *   <li>FF = META_CF prefix for sub-commands 00-07</li>
 *   <li>Many new flags: E2, E4, E5, EA, EB, EE, F1, F4, FC, FD, FE</li>
 * </ul>
 *
 * <p>Divergence from sonic-engine: F0 (MOD_SETUP) falls through to the core
 * sequencer's implementation, which sets the live modulation state directly
 * instead of staging it in pending fields. The audible difference is limited
 * to modulation changes issued during tied notes.
 */
public class S3kCoordFlagHandler implements CoordFlagHandler {
    private static final Logger LOGGER = Logger.getLogger(S3kCoordFlagHandler.class.getName());

    private int spindashRevCounter = 0;

    @Override
    public boolean handleFlag(CoordFlagContext ctx, SmpsSequencer.Track t, int cmd) {
        byte[] data = ctx.getData();
        switch (cmd) {
            case 0xE0: // PANAFMS - set pan/AMS/FMS
                if (t.pos < data.length) {
                    int val = data[t.pos++] & 0xFF;
                    t.pan = ((val & 0x80) != 0 ? 0x80 : 0) | ((val & 0x40) != 0 ? 0x40 : 0);
                    t.ams = (val >> 4) & 0x3;
                    t.fms = val & 0x7;
                    if (t.type == SmpsSequencer.TrackType.FM) {
                        int hwCh = t.channelId;
                        int port = (hwCh < 3) ? 0 : 1;
                        int ch = hwCh % 3;
                        int reg = 0xB4 + ch;
                        int regVal = (t.pan & 0xC0) | ((t.ams & 0x3) << 4) | (t.fms & 0x7);
                        ctx.writeFm(port, reg, regVal);
                    }
                }
                return true;

            case 0xE1: // DETUNE - set track detune
                if (t.pos < data.length) {
                    t.detune = data[t.pos++]; // signed byte
                }
                return true;

            case 0xE2: // FADE_TO_PREV - restore backed-up music (game-engine feature)
                // The tracker has no previous-music stack; consume the parameter.
                if (t.pos < data.length) {
                    t.pos++;
                }
                return true;

            case 0xE3: // TRK_END (TEND_MUTE) - stop/mute track
                t.active = false;
                ctx.stopNote(t);
                return true;

            case 0xE4: // VOL_ABS_S3K - set absolute volume
                if (t.pos < data.length) {
                    int raw = data[t.pos++] & 0xFF;
                    if (t.type == SmpsSequencer.TrackType.FM) {
                        // 00(min)..7F(max) -> 7F(min)..00(max)
                        t.volumeOffset = (~raw) & 0x7F;
                        ctx.refreshVolume(t);
                    } else if (t.type == SmpsSequencer.TrackType.PSG) {
                        // 00(min)..7F(max) -> 0F(min)..00(max)
                        t.volumeOffset = ((raw >> 3) & 0x0F) ^ 0x0F;
                        ctx.refreshVolume(t);
                    }
                }
                return true;

            case 0xE5: // VOL_CC_FMP2 - S3K broken: ignore first param, apply second as FM volume delta
                if (t.pos + 1 < data.length) {
                    t.pos++; // First parameter is ignored in S3K.
                    int volChange = (byte) data[t.pos++];
                    if (t.type == SmpsSequencer.TrackType.FM) {
                        applySignedFmVolumeDelta(t, volChange);
                        ctx.refreshVolume(t);
                    }
                }
                return true;

            case 0xE6: // VOL_CC_FM - add to volume offset
                if (t.pos < data.length) {
                    int delta = (byte) data[t.pos++];
                    if (t.type == SmpsSequencer.TrackType.FM) {
                        applySignedFmVolumeDelta(t, delta);
                        ctx.refreshVolume(t);
                    }
                }
                return true;

            case 0xE7: // HOLD - tie next note
                t.tieNext = true;
                return true;

            case 0xE8: // NOTE_STOP (NSTOP_MULT) - set fill
                if (t.pos < data.length) {
                    t.fill = data[t.pos++] & 0xFF;
                }
                return true;

            case 0xE9: // SPINDASH_REV (SDREV_INC) - no params in S3K!
                int updatedTranspose = (t.keyOffset + spindashRevCounter) & 0xFF;
                t.keyOffset = (byte) updatedTranspose;
                if (updatedTranspose != 0x10) {
                    spindashRevCounter = (spindashRevCounter + 1) & 0xFF;
                }
                return true;

            case 0xEA: // PLAY_DAC - play DAC sample
                if (t.pos < data.length) {
                    int dacId = data[t.pos++] & 0xFF;
                    ctx.playDac(dacId);
                }
                return true;

            case 0xEB: // LOOP_EXIT - counter, count, pointer (4 bytes total)
                handleLoopExit(ctx, t, data);
                return true;

            case 0xEC: // PSG_VOL (VOL_CC_PSG) - add to PSG volume
                if (t.pos < data.length) {
                    int delta = (byte) data[t.pos++];
                    if (t.type == SmpsSequencer.TrackType.PSG) {
                        // Z80 Type 2 behavior: unsigned add then clip upper bound to 0x0F.
                        int updated = (t.volumeOffset + delta) & 0xFF;
                        if (updated > 0x0F) {
                            updated = 0x0F;
                        }
                        t.volumeOffset = updated;
                        t.envAtRest = false;
                        if (t.envPos > 0) t.envPos--; // SMPSPlay smps_commands.c:1890 VolEnvIdx--
                        ctx.refreshVolume(t);
                    }
                }
                return true;

            case 0xED: // TRANSPOSE_SET (TRNSP_SET_S3K) - set absolute transposition
                if (t.pos < data.length) {
                    t.keyOffset = wrapSignedByte((data[t.pos++] & 0xFF) - 0x40);
                }
                return true;

            case 0xEE: // FM_COMMAND - direct FM register write
                if (t.pos + 1 < data.length) {
                    int fmReg = data[t.pos++] & 0xFF;
                    int fmVal = data[t.pos++] & 0xFF;
                    if (t.type == SmpsSequencer.TrackType.FM) {
                        int hwCh = t.channelId;
                        int port = (hwCh < 3) ? 0 : 1;
                        ctx.writeFm(port, fmReg, fmVal);
                    }
                }
                return true;

            case 0xF1: // MOD_ENV (MENV_FMP) - FM modulation envelope (2 params)
                if (t.pos + 1 < data.length) {
                    int psgEnvId = data[t.pos++] & 0xFF;
                    int fmEnvId = data[t.pos++] & 0xFF;
                    t.modEnvId = (t.type == SmpsSequencer.TrackType.PSG) ? psgEnvId : fmEnvId;
                    if (t.modEnvId == 0) {
                        ctx.clearModulation(t);
                    } else {
                        t.customModEnabled = false;
                        t.modEnvData = ctx.getSmpsData().getModEnvelope(t.modEnvId);
                        t.modEnvPos = 0;
                        t.modEnvMult = 0;
                        t.modEnvCache = 0;
                        t.modEnvHold = false;
                        t.modEnabled = t.modEnvData != null;
                    }
                }
                return true;

            case 0xF3: // PSG_NOISE (PNOIS_SRES) - set + reset
                if (t.pos < data.length) {
                    int noiseVal = data[t.pos++] & 0xFF;
                    if (t.type == SmpsSequencer.TrackType.PSG) {
                        ctx.writePsg(0xDF);
                        if (noiseVal == 0) {
                            t.noiseMode = false;
                            t.psgNoiseParam = 0;
                            ctx.writePsg(0xFF);
                        } else {
                            int noiseReg = ((noiseVal & 0xE0) == 0xE0)
                                    ? noiseVal
                                    : (0xE0 | (noiseVal & 0x0F));
                            t.noiseMode = true;
                            t.psgNoiseParam = noiseReg & 0x0F;
                            ctx.writePsg(noiseReg);
                        }
                    }
                }
                return true;

            case 0xF4: // MOD_ENV (MENV_GEN) - generic modulation envelope (1 param)
                if (t.pos < data.length) {
                    t.modEnvId = data[t.pos++] & 0xFF;
                    if (t.modEnvId == 0) {
                        ctx.clearModulation(t);
                    } else {
                        t.customModEnabled = false;
                        t.modEnvData = ctx.getSmpsData().getModEnvelope(t.modEnvId);
                        t.modEnvPos = 0;
                        t.modEnvMult = 0;
                        t.modEnvCache = 0;
                        t.modEnvHold = false;
                        t.modEnabled = t.modEnvData != null;
                    }
                }
                return true;

            case 0xF9: // RETURN - pop return stack (NOT SND_OFF like S2!)
                if (t.returnSp > 0) {
                    t.pos = t.returnStack[--t.returnSp];
                } else {
                    t.active = false;
                }
                return true;

            case 0xFA: // MODS_OFF - disable modulation
                ctx.clearModulation(t);
                return true;

            case 0xFB: // TRANSPOSE_ADD - add to transposition
                if (t.pos < data.length) {
                    t.keyOffset = wrapSignedByte(t.keyOffset + (byte) data[t.pos++]);
                }
                return true;

            case 0xFC: // CONT_SFX - continuous SFX loop; tracker never re-triggers,
                // so behave as "not re-triggered": consume the pointer and fall
                // through to the data after it (the fade-out section).
                ctx.readJumpPointer(t);
                return true;

            case 0xFD: // RAW_FREQ - set raw frequency mode
                if (t.pos < data.length) {
                    int rawFreqVal = data[t.pos++] & 0xFF;
                    t.rawFreqMode = (rawFreqVal == 0x01);
                }
                return true;

            case 0xFE: // SPC_FM3 - FM3 special mode (4 params, broken per DefCFlag.txt)
                if (t.pos + 3 < data.length) {
                    t.pos += 4;
                }
                return true;

            case 0xFF: // META_CF - meta command prefix
                handleMetaCommand(ctx, t, data);
                return true;

            // E3/E9/ED/EE etc. above are S3K-specific; flags whose S3K semantics
            // match the default S2 handler (EF, F0, F2, F5, F6, F7, F8) fall
            // through to the core implementation.
            default:
                return false;
        }
    }

    @Override
    public int flagParamLength(int cmd) {
        if (cmd == 0xFF) return 1; // Meta prefix + at least sub-command byte
        return switch (cmd) {
            case 0xE0 -> 1; // PANAFMS
            case 0xE1 -> 1; // DETUNE
            case 0xE2 -> 1; // FADE_IN_SONG
            case 0xE3 -> 0; // TRK_END (mute)
            case 0xE4 -> 1; // VOL_ABS
            case 0xE5 -> 2; // VOL_CC_FMP2
            case 0xE6 -> 1; // VOL_CC_FM
            case 0xE7 -> 0; // HOLD
            case 0xE8 -> 1; // NOTE_STOP
            case 0xE9 -> 0; // SPINDASH_REV (no param in S3K!)
            case 0xEA -> 1; // PLAY_DAC
            case 0xEB -> 3; // LOOP_EXIT (counter, count, ptr)
            case 0xEC -> 1; // PSG_VOL
            case 0xED -> 1; // TRANSPOSE_SET
            case 0xEE -> 2; // FM_COMMAND
            case 0xEF -> 1; // INSTRUMENT (basic)
            case 0xF0 -> 4; // MOD_SETUP
            case 0xF1 -> 2; // MOD_ENV (FMP)
            case 0xF2 -> 0; // TRK_END
            case 0xF3 -> 1; // PSG_NOISE
            case 0xF4 -> 1; // MOD_ENV (generic)
            case 0xF5 -> 1; // PSG_INSTRUMENT
            case 0xF6 -> 2; // GOTO
            case 0xF7 -> 4; // LOOP
            case 0xF8 -> 2; // GOSUB
            case 0xF9 -> 0; // RETURN
            case 0xFA -> 0; // MODS_OFF
            case 0xFB -> 1; // TRANSPOSE_ADD
            case 0xFC -> 2; // CONT_SFX
            case 0xFD -> 1; // RAW_FREQ
            case 0xFE -> 4; // SPC_FM3
            default -> -1;
        };
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void handleLoopExit(CoordFlagContext ctx, SmpsSequencer.Track t, byte[] data) {
        // EB: counter index, target count, pointer.
        // If the loop counter has reached the target count, continue past the
        // pointer; otherwise jump to the pointer address.
        if (t.pos + 1 < data.length) {
            int index = data[t.pos++] & 0xFF;
            int targetCount = data[t.pos++] & 0xFF;
            int jumpTarget = ctx.readJumpPointer(t);
            if (jumpTarget == -1) {
                return;
            }
            if (index >= t.loopCounters.length) {
                // Counter doesn't exist yet - not at target, jump
                t.pos = jumpTarget;
                return;
            }
            if (t.loopCounters[index] == targetCount) {
                // Exit: continue past the pointer (already advanced by readJumpPointer)
            } else {
                t.pos = jumpTarget;
            }
        }
    }

    private void handleMetaCommand(CoordFlagContext ctx, SmpsSequencer.Track t, byte[] data) {
        if (t.pos >= data.length) return;
        int sub = data[t.pos++] & 0xFF;

        switch (sub) {
            case 0x00: // TEMPO_SET - set tempo
                if (t.pos < data.length) {
                    int tempo = data[t.pos++] & 0xFF;
                    ctx.setNormalTempo(tempo);
                    ctx.recalculateTempo();
                }
                break;

            case 0x01: // SND_CMD - sound command (game engine feature, skip)
                if (t.pos < data.length) {
                    t.pos++;
                }
                break;

            case 0x02: // MUS_PAUSE (MUSP_Z80) - music pause (game engine feature, skip)
                if (t.pos < data.length) {
                    t.pos++;
                }
                break;

            case 0x03: // COPY_MEM - copy memory (3 params after sub, not modeled)
                if (t.pos + 2 < data.length) {
                    t.pos += 3;
                }
                break;

            case 0x04: // TICK_MULT (TMULT_ALL) - set tick multiplier
                if (t.pos < data.length) {
                    int tickMult = data[t.pos++] & 0xFF;
                    ctx.updateDividingTiming(tickMult);
                }
                break;

            case 0x05: // SSG_EG (SEG_NORMAL) - write SSG-EG registers for all 4 operators
                if (t.pos + 3 < data.length) {
                    if (t.type == SmpsSequencer.TrackType.FM) {
                        int hwCh = t.channelId;
                        int port = (hwCh < 3) ? 0 : 1;
                        int ch = hwCh % 3;
                        // Store SSG-EG values so refreshInstrument() can restore
                        // them after setInstrument() clears registers 0x90-0x9C.
                        t.ssgEg[0] = data[t.pos++] & 0xFF;
                        t.ssgEg[1] = data[t.pos++] & 0xFF;
                        t.ssgEg[2] = data[t.pos++] & 0xFF;
                        t.ssgEg[3] = data[t.pos++] & 0xFF;
                        ctx.writeFm(port, 0x90 + ch, t.ssgEg[0]);
                        ctx.writeFm(port, 0x94 + ch, t.ssgEg[1]);
                        ctx.writeFm(port, 0x98 + ch, t.ssgEg[2]);
                        ctx.writeFm(port, 0x9C + ch, t.ssgEg[3]);
                    } else {
                        t.pos += 4;
                    }
                }
                break;

            case 0x06: // FM_VOLENV - FM volume envelope (2 params)
                if (t.pos + 1 < data.length) {
                    int envId = data[t.pos++] & 0xFF;
                    int opMask = data[t.pos++] & 0x0F;
                    if (t.type == SmpsSequencer.TrackType.FM && envId != 0 && opMask != 0) {
                        t.fmVolEnvData = ctx.getSmpsData().getPsgEnvelope(envId);
                        t.fmVolEnvPos = 0;
                        t.fmVolEnvValue = 0;
                        t.fmVolEnvHold = false;
                        t.fmVolEnvOpMask = opMask;
                    } else {
                        t.fmVolEnvData = null;
                        t.fmVolEnvPos = 0;
                        t.fmVolEnvValue = 0;
                        t.fmVolEnvHold = true;
                        t.fmVolEnvOpMask = 0;
                    }
                    ctx.refreshVolume(t);
                }
                break;

            case 0x07: // SPINDASH_REV_RESET (SDREV_RESET) - reset spindash counter
                spindashRevCounter = 0;
                break;

            default:
                LOGGER.warning("S3K unknown meta command: FF " + String.format("%02X", sub));
                break;
        }
    }

    private static void applySignedFmVolumeDelta(SmpsSequencer.Track t, int delta) {
        int updated = t.volumeOffset + delta;
        if (updated < 0) {
            updated = 0;
        } else if (updated > 0x7F) {
            updated = 0x7F;
        }
        t.volumeOffset = updated;
    }

    private static int wrapSignedByte(int value) {
        return (byte) value;
    }
}
