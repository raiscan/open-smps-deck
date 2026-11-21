using System;
using System.Collections.Generic;
using BizHawk.Emulation.Common;

sealed class Watch
{
    public readonly string Name;
    public readonly bool Z80;
    public readonly int Address;
    public readonly int Size;

    public Watch(string name, bool z80, int address, int size = 1)
    {
        Name = name;
        Z80 = z80;
        Address = address;
        Size = size;
    }

    public void Validate(MemoryDomain domain)
    {
        if (Address < 0 || Address > domain.Size - Size)
            throw new ArgumentException(Name + " is outside " + domain.Name);
    }

    public uint Read(MemoryDomain domain)
    {
        uint value = 0;
        for (int i = 0; i < Size; i++)
            value = (value << 8) | domain.PeekByte(Address + (Z80 ? Size - 1 - i : i));
        return value;
    }
}

sealed class GameProfile
{
    public readonly List<Watch> Watches;
    public readonly string[] Tracks;
    public readonly bool TracksOnZ80;
    public readonly int FirstTrack, TrackSize, PointerOffset, PointerSize;

    GameProfile(List<Watch> watches, bool z80, int first, int stride, int offset, int size,
        string[] tracks)
    {
        Watches = watches;
        Tracks = tracks;
        TracksOnZ80 = z80;
        FirstTrack = first;
        TrackSize = stride;
        PointerOffset = offset;
        PointerSize = size;
    }

    public static GameProfile Select(string name)
    {
        // Paths below are relative to the OpenGGF checkout. 68K addresses are RAM offsets.
        var watches = new List<Watch> { new Watch("mode", false, 0xF600) };
        watches.Add(new Watch("demo_timer", false, 0xF614, 2));
        string[] tenTracks = { "dac", "fm1", "fm2", "fm3", "fm4", "fm5", "fm6", "psg1", "psg2", "psg3" };
        switch (name)
        {
            case "s3k":
                // docs/skdisasm/sonic3k.constants.asm:525, 540, 961 and sonic3k.asm:5715-5718.
                watches.Add(new Watch("demo_flag", false, 0xFFD0, 2));
                // docs/skdisasm/Sound/Z80 Sound Driver.asm:41-42, 95, 110-115, 172-185.
                watches.Add(new Watch("bank", true, 0x1C3E));
                return new GameProfile(watches, true, 0x1C40, 0x30, 3, 2,
                    new[] { "fm6_dac", "fm1", "fm2", "fm3", "fm4", "fm5", "psg1", "psg2", "psg3" });
            case "s1":
                // docs/s1disasm/_Variables.asm:114-126, 471 and sonic.asm:2408-2409.
                watches.Add(new Watch("demo_flag", false, 0xFFF0, 2));
                // docs/s1disasm/s1.sounddriver.asm:678-681, 798-804 and
                // s1.sounddriver.ram.asm:1-31, 44, 53, 66-82. The ID is consumed, not latched.
                watches.Add(new Watch("sound_request", false, 0xF009));
                watches.Add(new Watch("voice_ptr", false, 0xF018, 4));
                return new GameProfile(watches, false, 0xF040, 0x30, 4, 4, tenTracks);
            case "s2":
                // docs/s2disasm/s2.constants.asm:1382-1396, 1891 and s2.asm:4599-4600.
                watches.Add(new Watch("demo_flag", false, 0xFFF0, 2));
                // docs/s2disasm/s2.sounddriver.asm:54-74, 98-99, 143-185, 1754, 4089.
                watches.Add(new Watch("bank", true, 0x1B96));
                watches.Add(new Watch("song", true, 0x1300));
                return new GameProfile(watches, true, 0x1B98, 0x2A, 3, 2, tenTracks);
            default:
                throw new ArgumentException("Unknown profile " + name);
        }
    }
}
