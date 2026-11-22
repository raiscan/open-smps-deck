using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using BizHawk.Emulation.Common;
using BizHawk.Emulation.Cores;
using BizHawk.Emulation.Cores.Consoles.Sega.gpgx;

sealed class ProbeController : IController
{
    readonly HashSet<string> buttons = new HashSet<string>();
    readonly Dictionary<string, int> heldThrough = new Dictionary<string, int>();

    public ProbeController(ControllerDefinition definition)
    {
        Definition = definition;
        buttons.UnionWith(definition.BoolButtons);
    }

    public ControllerDefinition Definition { get; private set; }
    public bool IsPressed(string name) { return heldThrough.ContainsKey(name); }
    public int AxisValue(string name) { return 0; }
    public IReadOnlyCollection<(string Name, int Strength)> GetHapticsSnapshot()
    {
        return new List<(string Name, int Strength)>();
    }
    public void SetHapticChannelStrength(string name, int strength) {}

    public void Validate(string name)
    {
        if (!buttons.Contains("P1 " + name))
            throw new ArgumentException("Unknown controller button " + name);
    }

    public void Advance(int frame, List<string> pressed)
    {
        foreach (var release in heldThrough.Where(p => p.Value < frame).ToArray())
            heldThrough.Remove(release.Key);
        if (pressed != null)
            foreach (string button in pressed)
                heldThrough["P1 " + button] = frame + 1;
    }
}

sealed class NoFirmware : ICoreFileProvider
{
    public byte[] GetFirmware(FirmwareID id, string message = null) { return null; }
    public byte[] GetFirmwareOrThrow(FirmwareID id, string message = null)
    {
        throw new InvalidOperationException("Unexpected firmware request: " + id);
    }
    public (byte[] FW, GameInfo Game) GetFirmwareWithGameInfoOrThrow(FirmwareID id, string message = null)
    {
        throw new InvalidOperationException("Unexpected firmware request: " + id);
    }
    public string GetRetroSaveRAMDirectory(IGameInfo game) { throw new NotSupportedException(); }
    public string GetRetroSystemPath(IGameInfo game) { throw new NotSupportedException(); }
    public string GetUserPath(string system, bool temporary) { throw new NotSupportedException(); }
}

sealed class ProbeRom : IRomAsset
{
    public ProbeRom(string path, GameInfo game)
    {
        RomData = File.ReadAllBytes(path);
        RomPath = path;
        Extension = Path.GetExtension(path).TrimStart('.');
        Game = game;
    }

    public byte[] RomData { get; private set; }
    public byte[] FileData { get { return RomData; } }
    public string RomPath { get; private set; }
    public string Extension { get; private set; }
    public GameInfo Game { get; private set; }
}

sealed class WaveCapture : IDisposable
{
    readonly BinaryWriter writer;
    byte[] buffer = new byte[0];

    public WaveCapture(string path)
    {
        writer = new BinaryWriter(File.Open(path, FileMode.CreateNew));
        writer.Write("RIFF".ToCharArray());
        writer.Write(0u);
        writer.Write("WAVEfmt ".ToCharArray());
        writer.Write(16);
        writer.Write((short)1);
        writer.Write((short)2);
        writer.Write(44100);
        writer.Write(44100 * 4);
        writer.Write((short)4);
        writer.Write((short)16);
        writer.Write("data".ToCharArray());
        writer.Write(0u);
    }

    public void Write(short[] samples, int frames)
    {
        int bytes = frames * 4;
        if (writer.BaseStream.Position + bytes - 8 > uint.MaxValue)
            throw new IOException("Audio exceeded the RIFF size limit.");
        if (buffer.Length < bytes)
            buffer = new byte[bytes];
        Buffer.BlockCopy(samples, 0, buffer, 0, bytes);
        writer.Write(buffer, 0, bytes);
    }

    public void Dispose()
    {
        using (writer)
        {
            long length = writer.BaseStream.Position;
            writer.BaseStream.Position = 4;
            writer.Write((uint)(length - 8));
            writer.BaseStream.Position = 40;
            writer.Write((uint)(length - 44));
        }
    }
}

sealed class VideoCapture : IDisposable
{
    const int Width = 320;
    const int Height = 240;
    readonly Process encoder;
    readonly Stream input;
    readonly int[] frame = new int[Width * Height];
    readonly byte[] bytes = new byte[Width * Height * 4];

    public VideoCapture(string ffmpeg, string output, IVideoProvider video)
    {
        string fps = video.VsyncNumerator + "/" + video.VsyncDenominator;
        encoder = Process.Start(new ProcessStartInfo(ffmpeg,
            "-loglevel error -n -f rawvideo -pix_fmt bgra -s 320x240 -r " + fps +
            " -i - -vf scale=960:720:flags=neighbor -c:v libx264 -pix_fmt yuv420p -crf 18 capture.mp4")
        {
            WorkingDirectory = output, UseShellExecute = false, RedirectStandardInput = true
        });
        input = encoder.StandardInput.BaseStream;
    }

    public void Write(IVideoProvider video)
    {
        int[] pixels = video.GetVideoBuffer();
        int width = video.BufferWidth, height = video.BufferHeight;
        int rows = Math.Min(Height, height), columns = Math.Min(Width, width);
        int srcX = (width - columns) / 2, srcY = (height - rows) / 2;
        int dstX = (Width - columns) / 2, dstY = (Height - rows) / 2;
        Array.Clear(frame, 0, frame.Length);
        for (int y = 0; y < rows; y++)
            Array.Copy(pixels, (y + srcY) * width + srcX, frame, (y + dstY) * Width + dstX, columns);
        Buffer.BlockCopy(frame, 0, bytes, 0, bytes.Length);
        input.Write(bytes, 0, bytes.Length);
    }

    public void Dispose()
    {
        using (encoder)
        {
            try { input.Dispose(); }
            finally { encoder.WaitForExit(); }
            if (encoder.ExitCode != 0)
                throw new IOException("ffmpeg exited with status " + encoder.ExitCode);
        }
    }
}

static class RomProbe
{
    static int Main(string[] args)
    {
        try
        {
            var version = typeof(GPGX).Assembly.GetName().Version;
            if (version != new Version(2, 11, 0, 0))
                throw new InvalidOperationException("BizHawk 2.11 is required, found " + version);
            if (args.Length == 1 && args[0] == "--check-version")
                return 0;
            Capture(args);
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine("ROM probe: " + error);
            return 2;
        }
    }

    static void Capture(string[] args)
    {
        string rom = args[0], output = args[1], ffmpeg = args[5];
        var profile = GameProfile.Select(args[2]);
        int total = int.Parse(args[3]);
        var presses = args[4].Split(new[] { ',' }, StringSplitOptions.RemoveEmptyEntries)
            .Select(s => s.Split(':')).GroupBy(p => int.Parse(p[0]))
            .ToDictionary(g => g.Key, g => g.Select(p => p[1]).ToList());
        foreach (string spec in args.Skip(6))
        {
            string[] parts = spec.Split(':');
            profile.Watches.Add(new Watch(parts[0], parts[1] == "z80",
                int.Parse(parts[2]), int.Parse(parts[3])));
        }

        var game = new GameInfo { Name = args[2], System = VSystemID.Raw.GEN, Hash = "probe" };
        var settings = new GPGX.GPGXSyncSettings
        {
            UseSixButton = false,
            ControlTypeLeft = GPGX.ControlType.Normal,
            ControlTypeRight = GPGX.ControlType.Normal,
            Region = LibGPGX.Region.Autodetect,
            LoadBIOS = false,
            GenesisFMSoundChip = LibGPGX.InitSettings.GenesisFMSoundChipType.MAME_YM2612,
            Filter = LibGPGX.InitSettings.FilterType.None
        };
        var comm = new CoreComm(_ => {}, (_, __) => {}, new NoFirmware(),
            CoreComm.CorePreferencesFlags.None, null);
        using (var core = new GPGX(new CoreLoadParameters<GPGX.GPGXSettings, GPGX.GPGXSyncSettings>
        {
            Comm = comm, Game = game, Settings = new GPGX.GPGXSettings(), SyncSettings = settings,
            Roms = { new ProbeRom(rom, game) }, DeterministicEmulationRequested = true
        }))
        {
            var domains = core.ServiceProvider.GetService<IMemoryDomains>();
            var ram = domains["68K RAM"];
            var z80 = domains["Z80 RAM"];
            var trackRam = profile.TracksOnZ80 ? z80 : ram;
            foreach (Watch watch in profile.Watches)
                watch.Validate(watch.Z80 ? z80 : ram);
            var pointers = profile.Tracks.Select((name, index) => new Watch(name, profile.TracksOnZ80,
                profile.FirstTrack + index * profile.TrackSize + profile.PointerOffset,
                profile.PointerSize)).ToArray();

            var controller = new ProbeController(core.ControllerDefinition);
            foreach (string button in presses.Values.SelectMany(b => b))
                controller.Validate(button);
            var sound = core.ServiceProvider.GetService<ISoundProvider>();
            var video = core.ServiceProvider.GetService<IVideoProvider>();

            using (var log = new StreamWriter(File.Open(Path.Combine(output, "probe.log"), FileMode.CreateNew)))
            using (var audio = new WaveCapture(Path.Combine(output, "capture.wav")))
            using (var movie = ffmpeg.Length > 0 ? new VideoCapture(ffmpeg, output, video) : null)
            {
                log.WriteLine("# fps={0}/{1}", video.VsyncNumerator, video.VsyncDenominator);
                log.WriteLine("frame,buttons," + string.Join(",", profile.Watches.Select(w => w.Name))
                    + "," + string.Join(",", profile.Tracks.Select(t => t + "_ptr")));
                for (int frame = 0; frame < total; frame++)
                {
                    List<string> buttons;
                    presses.TryGetValue(frame, out buttons);
                    controller.Advance(frame, buttons);
                    core.FrameAdvance(controller, movie != null, true);
                    if (movie != null)
                        movie.Write(video);
                    short[] samples;
                    int sampleFrames;
                    sound.GetSamplesSync(out samples, out sampleFrames);
                    audio.Write(samples, sampleFrames);

                    log.Write("{0},{1}", frame, buttons == null ? "" : string.Join("+", buttons));
                    foreach (Watch watch in profile.Watches)
                        log.Write(",{0:X}", watch.Read(watch.Z80 ? z80 : ram));
                    foreach (Watch pointer in pointers)
                    {
                        bool active = (trackRam.PeekByte(pointer.Address - profile.PointerOffset) & 0x80) != 0;
                        log.Write(",{0:X}", active ? pointer.Read(trackRam) : 0);
                    }
                    log.WriteLine();
                }
            }
        }
    }
}
