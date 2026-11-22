"""Capture and verify a Mega Drive ROM using a software libretro core."""

import ctypes as c
import csv
import math
import os
import struct
import subprocess
import sys
import wave
from contextlib import ExitStack
from fractions import Fraction
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

# Isolated Python does not add the script directory to the import path.
spec = spec_from_file_location("rom_probe_run", Path(__file__).with_name("run.py"))
scenario = module_from_spec(spec)
spec.loader.exec_module(scenario)

# libretro ABI v1. See libretro-common/include/libretro.h.
u, size, ptr = c.c_uint, c.c_size_t, c.c_void_p
ENV = c.CFUNCTYPE(c.c_bool, u, ptr)
VIDEO = c.CFUNCTYPE(None, ptr, u, u, size)
SAMPLE = c.CFUNCTYPE(None, c.c_int16, c.c_int16)
BATCH = c.CFUNCTYPE(size, ptr, size)
POLL = c.CFUNCTYPE(None)
INPUT = c.CFUNCTYPE(c.c_int16, u, u, u, u)
LOG = c.CFUNCTYPE(None, c.c_int, c.c_char_p)
STRING = c.POINTER(c.c_char_p)
EXPERIMENTAL = 0x10000

GET_CAN_DUPE = 3
SET_MESSAGE = 6
SHUTDOWN = 7
GET_SYSTEM_DIRECTORY = 9
SET_PIXEL_FORMAT = 10
SET_INPUT_DESCRIPTORS = 11
GET_VARIABLE_UPDATE = 17
GET_LIBRETRO_PATH = 19
GET_LOG_INTERFACE = 27
GET_CORE_ASSETS_DIRECTORY = 30
GET_SAVE_DIRECTORY = 31
SET_MEMORY_MAPS = 36 | EXPERIMENTAL
SET_GEOMETRY = 37
GET_INPUT_BITMASKS = 51 | EXPERIMENTAL
GET_INPUT_MAX_USERS = 61

MEMORY_SYSTEM_RAM = 2
MEMDESC_BIGENDIAN = 1 << 1
JOYPAD = 1
JOYPAD_MASK = 256
# Mega Drive conventions used by Genesis Plus GX when descriptors are absent.
BUTTONS = {"B": 0, "A": 1, "Start": 3, "Up": 4, "Down": 5,
           "Left": 6, "Right": 7, "C": 8}


class SystemInfo(c.Structure):
    _fields_ = [(key, c.c_char_p) for key in ("name", "version", "extensions")] + [
        ("need_fullpath", c.c_bool), ("block_extract", c.c_bool)]


class GameInfo(c.Structure):
    _fields_ = [("path", c.c_char_p), ("data", ptr), ("size", size), ("meta", c.c_char_p)]


class Geometry(c.Structure):
    _fields_ = [(key, u) for key in ("width", "height", "max_width", "max_height")] + [
        ("aspect_ratio", c.c_float)]


class Timing(c.Structure):
    _fields_ = [("fps", c.c_double), ("sample_rate", c.c_double)]


class AVInfo(c.Structure):
    _fields_ = [("geometry", Geometry), ("timing", Timing)]


class InputDescriptor(c.Structure):
    _fields_ = [(key, u) for key in ("port", "device", "index", "id")] + [
        ("description", c.c_char_p)]


class MemoryDescriptor(c.Structure):
    _fields_ = [("flags", c.c_uint64), ("pointer", ptr)] + [
        (key, size) for key in ("offset", "start", "select", "disconnect", "length")] + [
        ("addrspace", c.c_char_p)]


class MemoryMap(c.Structure):
    _fields_ = [("descriptors", c.POINTER(MemoryDescriptor)), ("count", u)]


def read_at(pointer, kind):
    return c.cast(pointer, c.POINTER(kind))[0]


class WaveCapture:
    def __init__(self, stream, rate):
        self.wav = wave.open(stream, "wb")
        self.wav.setparams((2, 2, rate, 0, "NONE", "not compressed"))

    def sample(self, left, right):
        self.wav.writeframesraw(struct.pack("=hh", left, right))

    def batch(self, data, frames):
        # wave converts native-endian PCM to little-endian.
        self.wav.writeframesraw(c.string_at(data, frames * 4))
        return frames

    def close(self):
        self.wav.close()


class VideoCapture:
    def __init__(self, ffmpeg, output, geometry, fps, pixel_format):
        self.width = (geometry.max_width + 1) & ~1
        self.height = (geometry.max_height + 1) & ~1
        if not self.width or not self.height:
            raise ValueError("core reported empty video geometry")
        endian = "le" if sys.byteorder == "little" else "be"
        formats = ("rgb555" + endian, "bgr0" if endian == "le" else "0rgb", "rgb565" + endian)
        self.bpp = 4 if pixel_format == 1 else 2
        self.frame = bytes(self.width * self.height * self.bpp)
        self.encoder = subprocess.Popen(
            [ffmpeg, "-nostdin", "-loglevel", "error", "-n", "-f", "rawvideo",
             "-pixel_format", formats[pixel_format], "-video_size", f"{self.width}x{self.height}",
             "-framerate", str(fps), "-i", "-", "-an", "-c:v", "libx264",
             "-pix_fmt", "yuv420p", str(output / "capture.mp4")], stdin=subprocess.PIPE)

    def update(self, data, width, height, pitch):
        if not data:
            return  # Duplicate the last frame.
        if width > self.width or height > self.height or width * self.bpp > pitch:
            raise ValueError("video frame exceeds the core's declared geometry")
        row_size = self.width * self.bpp
        frame = bytearray(row_size * self.height)
        first = (self.height - height) // 2 * row_size + (self.width - width) // 2 * self.bpp
        for row in range(height):
            start = first + row * row_size
            frame[start:start + width * self.bpp] = c.string_at(data + row * pitch, width * self.bpp)
        self.frame = frame

    def write(self):
        self.encoder.stdin.write(self.frame)

    def close(self):
        try:
            self.encoder.stdin.close()
        finally:
            status = self.encoder.wait()
        if status:
            raise ValueError(f"ffmpeg exited with status {status}")


class Core:
    def __init__(self, path, output, log):
        self.path = os.fsencode(path)
        self.directory = os.fsencode(output)
        self.log = log
        self.audio = self.video = self.error = None
        self.shutdown = False
        self.pixel_format = self.buttons = 0
        self.button_ids = BUTTONS.copy()
        self.maps = []
        self.library = c.CDLL(path)
        for name, result, arguments in (
            ("api_version", u, ()), ("init", None, ()), ("deinit", None, ()),
            ("get_system_info", None, (c.POINTER(SystemInfo),)),
            ("get_system_av_info", None, (c.POINTER(AVInfo),)),
            ("load_game", c.c_bool, (c.POINTER(GameInfo),)), ("unload_game", None, ()),
            ("set_controller_port_device", None, (u, u)), ("run", None, ()),
            ("get_memory_data", ptr, (u,)), ("get_memory_size", size, (u,)),
        ):
            function = getattr(self.library, "retro_" + name)
            function.restype, function.argtypes = result, arguments
        if self.library.retro_api_version() != 1:
            raise ValueError("expected libretro API version 1")
        self.log_callback = LOG(self.log_message)
        # Keep callbacks alive until the core has been deinitialized.
        self.callbacks = []
        for name, kind, handler in (
            ("environment", ENV, self.environment), ("video_refresh", VIDEO, self.video_frame),
            ("audio_sample", SAMPLE, self.audio_sample), ("audio_sample_batch", BATCH, self.audio_batch),
            ("input_poll", POLL, lambda: None), ("input_state", INPUT, self.input_state),
        ):
            callback = kind(handler)
            self.callbacks.append(callback)
            setter = getattr(self.library, "retro_set_" + name)
            setter.restype, setter.argtypes = None, (kind,)
            setter(callback)
        self.info = SystemInfo()
        self.library.retro_get_system_info(c.byref(self.info))

    def log_message(self, level, message):
        # ctypes has no portable va_list bridge. Save the format string as supplied.
        self.log.write(f"[{level}] {message.decode(errors='replace').rstrip()}\n")

    def environment(self, command, data):
        if command in (GET_SYSTEM_DIRECTORY, GET_SAVE_DIRECTORY, GET_CORE_ASSETS_DIRECTORY,
                       GET_LIBRETRO_PATH):
            c.cast(data, STRING)[0] = self.path if command == GET_LIBRETRO_PATH else self.directory
        elif command in (GET_CAN_DUPE, GET_VARIABLE_UPDATE):
            c.cast(data, c.POINTER(c.c_bool))[0] = command == GET_CAN_DUPE
        elif command == GET_INPUT_MAX_USERS:
            c.cast(data, c.POINTER(u))[0] = 1
        elif command == GET_LOG_INTERFACE:
            c.cast(data, c.POINTER(LOG))[0] = self.log_callback
        elif command == SET_PIXEL_FORMAT:
            pixel_format = read_at(data, u)
            if pixel_format not in (0, 1, 2) or self.video and pixel_format != self.pixel_format:
                return False
            self.pixel_format = pixel_format
        elif command == SET_INPUT_DESCRIPTORS:
            descriptors = c.cast(data, c.POINTER(InputDescriptor))
            for item in descriptors:
                if item.description is None:
                    break
                label = item.description.decode(errors="replace").removeprefix("D-Pad ")
                if item.port == item.index == 0 and item.device == JOYPAD and label in BUTTONS:
                    self.button_ids[label] = item.id
        elif command == SET_MEMORY_MAPS:
            memory = read_at(data, MemoryMap)
            # The descriptor array may be on the core's stack. Copy it now.
            self.maps = [(d.pointer, d.offset, d.start, d.select, d.disconnect, d.length,
                          d.flags, (d.addrspace or b"").decode(errors="replace"))
                         for d in memory.descriptors[:memory.count]]
        elif command == SET_MESSAGE:
            self.log_message(1, read_at(data, c.c_char_p))
        elif command == SHUTDOWN:
            self.shutdown = True
        elif command not in (SET_GEOMETRY, GET_INPUT_BITMASKS):
            return False
        return True

    def load(self, rom):
        if self.info.need_fullpath:
            game = GameInfo(os.fsencode(rom), None, 0, None)
        else:
            self.rom_buffer = c.create_string_buffer(rom.read_bytes())
            game = GameInfo(os.fsencode(rom), c.addressof(self.rom_buffer),
                            len(self.rom_buffer) - 1, None)
        if not self.library.retro_load_game(c.byref(game)):
            raise ValueError("core rejected the ROM (see core.log)")
        self.library.retro_set_controller_port_device(0, JOYPAD)

    def input_state(self, port, device, index, button):
        if port or index or device != JOYPAD:
            return 0
        return self.buttons if button == JOYPAD_MASK else bool(self.buttons & (1 << button))

    def audio_sample(self, left, right):
        if self.audio and not self.error:
            try:
                self.audio.sample(left, right)
            except Exception as error:
                self.error = error

    def audio_batch(self, data, frames):
        if self.audio and not self.error:
            try:
                return self.audio.batch(data, frames)
            except Exception as error:
                self.error = error
        return frames

    def video_frame(self, data, width, height, pitch):
        if self.video and not self.error:
            try:
                self.video.update(data, width, height, pitch)
            except Exception as error:
                self.error = error

    def advance(self, buttons):
        self.buttons = sum(1 << index for index in {self.button_ids[b] for b in buttons})
        self.library.retro_run()
        if self.error:
            raise self.error
        if self.shutdown:
            raise ValueError("core requested shutdown (see core.log)")
        if self.video:
            self.video.write()

    def z80_ram(self):
        # A bounded flat map explicitly named for the Z80 or mapped at the MD
        # bus address. Avoid guessing from an anonymous 8 KiB buffer.
        for pointer, offset, start, select, disconnect, length, flags, space in self.maps:
            name = "".join(ch for ch in space.lower() if ch.isalnum())
            if (pointer and length == 0x2000 and not disconnect
                    and (start == 0xA00000 and not space
                         or name in ("z80", "z80ram") and start in (0, 0xA00000))):
                return pointer + offset, flags
        return None, 0


def profile_fields(profile):
    """Match the RAM watches and active-track pointer layout in GameProfile.cs."""
    fields = [("mode", "68k", 0xF600, 1, None), ("demo_timer", "68k", 0xF614, 2, None),
              ("demo_flag", "68k", 0xFFD0 if profile == "s3k" else 0xFFF0, 2, None)]
    tracks = ("dac", "fm1", "fm2", "fm3", "fm4", "fm5", "fm6", "psg1", "psg2", "psg3")
    if profile == "s1":
        fields.extend([("sound_request", "68k", 0xF009, 1, None),
                       ("voice_ptr", "68k", 0xF018, 4, None)])
        domain, base, stride, offset, width = "68k", 0xF040, 0x30, 4, 4
    elif profile == "s2":
        fields.extend([("bank", "z80", 0x1B96, 1, None), ("song", "z80", 0x1300, 1, None)])
        domain, base, stride, offset, width = "z80", 0x1B98, 0x2A, 3, 2
    else:
        fields.append(("bank", "z80", 0x1C3E, 1, None))
        tracks = ("fm6_dac", "fm1", "fm2", "fm3", "fm4", "fm5", "psg1", "psg2", "psg3")
        domain, base, stride, offset, width = "z80", 0x1C40, 0x30, 3, 2
    for index, track in enumerate(tracks):
        active = base + index * stride
        fields.append((track + "_ptr", domain, active + offset, width, active))
    return fields


def ram_values(fields, ram, zram, swap, z80_endian):
    for _, domain, address, width, active in fields:
        if domain == "z80":
            if zram is None:
                yield None
            else:
                yield (int.from_bytes(zram[address:address + width], z80_endian)
                       if active is None or zram[active] & 0x80 else 0)
        elif active is not None and not ram[active ^ swap] & 0x80:
            yield 0
        else:
            yield int.from_bytes(bytes(ram[i ^ swap] for i in range(address, address + width)), "big")


def capture(args, core, stack, output, ffmpeg, notes):
    av = AVInfo()
    core.library.retro_get_system_av_info(c.byref(av))
    if not all(math.isfinite(value) and value > 0 for value in
               (av.timing.fps, av.timing.sample_rate)):
        raise ValueError("core reported invalid AV timing")
    fps = Fraction(av.timing.fps).limit_denominator(1_000_000)
    rate = round(av.timing.sample_rate)
    name = core.info.name.decode(errors="replace")
    order = args.ram_order
    if order is None:
        order = "word-native" if name in ("BlastEm", "Genesis Plus GX") else "big"
    swap = int(order == "word-native" and sys.byteorder == "little")
    lib = core.library
    length = lib.retro_get_memory_size(MEMORY_SYSTEM_RAM)
    ram = lib.retro_get_memory_data(MEMORY_SYSTEM_RAM)
    if length != 0x10000 or not ram:
        raise ValueError(f"expected 64 KiB of Mega Drive system RAM, found {length} bytes")
    zram, flags = core.z80_ram()
    fields = profile_fields(args.profile) + [(*watch, None) for watch in args.watch]
    unavailable = {name for name, domain, *_ in fields if domain == "z80" and not zram}
    if not zram:
        notes.append("The core exposes no flat Z80 RAM map. Z80 fields are unavailable.")
    stream = stack.enter_context((output / "capture.wav").open("xb"))
    core.audio = WaveCapture(stream, rate)
    stack.callback(core.audio.close)
    if ffmpeg:
        core.video = VideoCapture(ffmpeg, output, av.geometry, fps, core.pixel_format)
        stack.callback(core.video.close)

    presses = {}
    for frame, button in args.press:
        for held in (frame, frame + 1):
            presses.setdefault(held, set()).add(button)
    with (output / "probe.log").open("x", newline="") as log:
        log.write(f"# fps={fps}\n")
        writer = csv.writer(log, lineterminator="\n")
        writer.writerow(["frame", "buttons", *(field[0] for field in fields)])
        for frame in range(args.frames):
            buttons = presses.get(frame, ())
            core.advance(buttons)
            values = ram_values(fields, c.string_at(ram, length),
                                c.string_at(zram, 0x2000) if zram else None,
                                swap, "big" if flags & MEMDESC_BIGENDIAN else "little")
            writer.writerow([frame, "+".join(sorted(buttons)),
                             *("" if value is None else f"{value:X}" for value in values)])
    return unavailable, {
        "sample_rate": rate, "ram_order": order,
        "memory": {"68k": length, "z80": 0x2000 if zram else None,
                   "save": lib.retro_get_memory_size(0), "rtc": lib.retro_get_memory_size(1),
                   "video": lib.retro_get_memory_size(3)},
    }


def main():
    try:
        args = scenario.parse_args(__doc__, libretro=True)
        core_path, rom = args.core.resolve(), args.rom.resolve()
        output, inputs, ffmpeg, notes = scenario.prepare_run(args)
        inputs["core"] = scenario.input_hashes(core_path)
        with (output / "core.log").open("x") as log, ExitStack() as stack:
            core = Core(core_path, output, log)
            core.library.retro_init()
            stack.callback(core.library.retro_deinit)
            core.load(rom)
            stack.callback(core.library.retro_unload_game)
            unavailable, details = capture(args, core, stack, output, ffmpeg, notes)
        name = core.info.name.decode(errors="replace")
        emulator = {"core": name, "version": core.info.version.decode(errors="replace"),
                    "path_sha256": inputs["core"]["sha256"]}
        return scenario.write_report(args, output, inputs, ffmpeg, notes, unavailable=unavailable,
                                     core=name, emulator=emulator, **details)
    except (OSError, ValueError, AttributeError, OverflowError, struct.error, wave.Error) as error:
        print(f"ROM probe: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
