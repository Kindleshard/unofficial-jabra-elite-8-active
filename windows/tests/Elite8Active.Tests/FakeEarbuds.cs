using System.Collections.Concurrent;
using System.Text;
using Elite8Active.Bluetooth;
using Elite8Active.Protocol;

namespace Elite8Active.Tests;

/// <summary>
/// Earbuds for tests: answers GNP reads and writes the way the real ones were seen to (replies,
/// ACK/NACK, and the events that follow a write), from values the test can set and inspect.
/// </summary>
public sealed class FakeEarbuds : IByteChannel
{
    private readonly BlockingCollection<byte[]> _toApp = new();
    private readonly FrameReader _fromApp = new();

    public string Name = "Jabra Elite 8 Active";
    public string Firmware = "4.6.0";
    public int? ProductId = 11847;
    public SoundMode Mode = SoundMode.Anc;
    public int HearThroughValue = 1;
    public int VoiceAssistant = 0x11;
    public int[] EqGains = [.. EqPreset.BassBoost.Gains];
    public EarbudLink Link = new(Linked: true, Primary: Side.Right);
    public byte[] Battery = Hex.Bytes("80500000021500 5a05 0055");

    /// <summary>CONFIG values exactly as the earbuds return them.</summary>
    public readonly Dictionary<int, byte[]> Config = new()
    {
        [0xE4] = [4, 4, 1],
        [0x92] = [1],
        [0x8E] = [0, 1],
        [0x91] = [1, 0],
        [0x8F] = [0, 0],
        [0xF0] = [1],
        [0x7C] = [0, 0xFD],
        [0x1E] = [0],
        [0xF1] = [1],
        [0xEF] = [0, 0],
        [0xBF] = [3, 0, 1, 2],
        [0x76] = [1],
        [0x15] = [0],
        [0x90] = [6],
        [0x3A] = [1],
    };

    /// <summary>CONFIG subs whose writes the earbuds refuse.</summary>
    public readonly HashSet<int> Refused = [];

    /// <summary>Every write the app made: "cmd sub payload" in hex.</summary>
    public readonly ConcurrentQueue<string> Writes = new();

    public bool Disposed { get; private set; }

    public int Read(byte[] buffer)
    {
        try
        {
            var bytes = _toApp.Take();
            bytes.CopyTo(buffer, 0);
            return bytes.Length;
        }
        catch (InvalidOperationException)
        {
            return 0;
        }
    }

    public void Write(byte[] data)
    {
        if (Disposed) throw new IOException("closed");
        foreach (var frame in _fromApp.Feed(data)) Answer(frame);
    }

    /// <summary>The link drops, as when both earbuds go into the case.</summary>
    public void Drop() => _toApp.CompleteAdding();

    public void Dispose()
    {
        Disposed = true;
        _toApp.CompleteAdding();
    }

    public void SendEvent(int cmd, int sub, params byte[] payload) => Send(Gnp.Build(Gnp.App, Gnp.Buds, 0, FrameType.Event, cmd, sub, payload));

    private void Send(byte[] frame)
    {
        if (!_toApp.IsAddingCompleted) _toApp.Add(frame);
    }

    private void Reply(Frame request, byte[] payload) =>
        Send(Gnp.Build(Gnp.App, Gnp.Buds, request.Seq, FrameType.Reply, request.Cmd, request.Sub, payload));

    private void Ack(Frame request) =>
        Send(Gnp.Build(Gnp.App, Gnp.Buds, request.Seq, FrameType.Reply, Gnp.CmdAck, 0x04, [0x09, (byte)request.Seq, request.Raw[3], (byte)request.Cmd]));

    private void Nack(Frame request, int reason) =>
        Send(Gnp.Build(Gnp.App, Gnp.Buds, request.Seq, FrameType.Reply, Gnp.CmdNack, reason));

    private void Answer(Frame f)
    {
        var sub = f.Sub ?? 0;
        var p = f.Payload;
        if (f.Type == FrameType.Read)
        {
            switch (f.Cmd, sub)
            {
                case (Gnp.CmdIdent, Jabra.IdentName): Reply(f, [(byte)Name.Length, .. Encoding.ASCII.GetBytes(Name)]); break;
                case (Gnp.CmdIdent, Jabra.IdentFirmware): Reply(f, [(byte)Firmware.Length, .. Encoding.ASCII.GetBytes(Firmware)]); break;
                case (Gnp.CmdIdent, Jabra.IdentProductId) when ProductId is { } id: Reply(f, [(byte)id, (byte)(id >> 8)]); break;
                case (Gnp.CmdStatus, Jabra.StatusBattery): Reply(f, Battery); break;
                case (Gnp.CmdDevice, Jabra.DeviceEvents): Reply(f, [0, 0, 2, 0x96]); break;
                case (Gnp.CmdDevice, Jabra.DeviceVoiceAssistant): Reply(f, [(byte)VoiceAssistant]); break;
                case (Gnp.CmdConfig, Jabra.ConfigName): Reply(f, Encoding.ASCII.GetBytes(Name)); break;
                case (Gnp.CmdConfig, Jabra.ConfigSoundMode):
                    Reply(f, [p[0] == Jabra.SoundModeSlot ? (byte)Mode : (byte)HearThroughValue]);
                    break;
                case (Gnp.CmdConfig, Jabra.ConfigEq): Reply(f, EqReadBack()); break;
                case (Gnp.CmdConfig, _) when Config.TryGetValue(sub, out var value): Reply(f, value); break;
                default: Nack(f, 0xFF); break;
            }
            return;
        }

        Writes.Enqueue($"{f.Cmd:x2} {sub:x2} {p.ToHex()}".TrimEnd());
        if (f.Cmd == Gnp.CmdConfig && Refused.Contains(sub))
        {
            Nack(f, 0xFA);
            return;
        }
        switch (f.Cmd, sub)
        {
            case (Gnp.CmdDevice, Jabra.DeviceEvents):
                Ack(f);
                // Every subscription write is followed by the current link state.
                SendEvent(Gnp.CmdDevice, Jabra.DeviceEvents, Jabra.EventLink, (byte)(Link.Linked ? 1 : 0), (byte)(Link.Primary == Side.Right ? 1 : 0));
                break;
            case (Gnp.CmdDevice, Jabra.DeviceVoiceAssistant):
                VoiceAssistant = p[1];
                Ack(f);
                break;
            case (Gnp.CmdConfig, Jabra.ConfigSoundMode):
                if (p[0] == Jabra.SoundModeSlot) Mode = (SoundMode)p[1];
                else HearThroughValue = p[1];
                Ack(f);
                SendEvent(Gnp.CmdDevice, Jabra.DeviceEvents, Jabra.EventSoundMode, 1, (byte)Mode);
                SendEvent(Gnp.CmdDevice, Jabra.DeviceEvents, Jabra.EventSetting, Jabra.ConfigSoundMode);
                break;
            case (Gnp.CmdConfig, Jabra.ConfigEq):
                EqGains = Enumerable.Range(0, p[0]).Select(i => (int)(short)((p[4 + 6 * i + 2] << 8) | p[4 + 6 * i + 3])).ToArray();
                Ack(f);
                SendEvent(Gnp.CmdDevice, Jabra.DeviceEvents, Jabra.EventSetting, Jabra.ConfigEq);
                break;
            case (Gnp.CmdConfig, Jabra.ConfigEqEnabled):
                Ack(f);
                break;
            case (Gnp.CmdConfig, _):
                Config[sub] = p;
                Ack(f);
                SendEvent(Gnp.CmdDevice, Jabra.DeviceEvents, Jabra.EventSetting, (byte)sub);
                break;
            default:
                Nack(f, 0xF6);
                break;
        }
    }

    // 4-byte header, then (freq, gain, Q, type) per band; the last band has no type byte.
    private byte[] EqReadBack()
    {
        var written = Jabra.EqPayload(EqGains);
        var bytes = new List<byte> { 5, 0, 0, 0x1E };
        for (var band = 0; band < 5; band++)
        {
            bytes.AddRange(written.AsSpan(4 + 6 * band, 6));
            if (band < 4) bytes.Add(0x18);
        }
        return [.. bytes];
    }
}

/// <summary>A PC for tests: which earbuds are paired, whether Windows has them connected, and what opening the channel does.</summary>
public sealed class FakeHost : IEarbudsHost
{
    public List<PairedDevice> Paired = [new PairedDevice(0x0011223344AB, "Jabra Elite 8 Active")];
    public bool BluetoothOn { get; set; } = true;
    public bool InUse = true;
    public Func<IByteChannel> OnOpen = () => new FakeEarbuds();
    public int Opens;

    public IReadOnlyList<PairedDevice> PairedDevices() => Paired;

    public bool IsInUse(PairedDevice device) => InUse;

    public IByteChannel Open(PairedDevice device)
    {
        Opens++;
        return OnOpen();
    }
}
