namespace Elite8Active.Protocol;

public enum FrameType { Event, Read, Write, Reply }

/// <summary>
/// One GN protocol (GNP) frame, as spoken by Jabra Sound+ on RFCOMM channel 29.
/// Layout: dst, src, seq, (type &lt;&lt; 6 | total length), cmd, [sub], payload. Total length is 5..62.
/// See jabra-protocol-notes.md for how each field was confirmed.
/// </summary>
public sealed class Frame
{
    public Frame(byte[] raw)
    {
        if (raw.Length < 5) throw new ArgumentException($"GNP frame too short: {raw.Length} bytes");
        Raw = raw;
    }

    public byte[] Raw { get; }
    public int Dst => Raw[0];
    public int Src => Raw[1];
    public int Seq => Raw[2];
    public FrameType Type => (FrameType)(Raw[3] >> 6);
    public int Cmd => Raw[4];
    public int? Sub => Raw.Length > 5 ? Raw[5] : null;
    public byte[] Payload => Raw.Length > 6 ? Raw[6..] : [];

    public override string ToString() =>
        $"{Type} {Src:x2}->{Dst:x2} seq={Seq:x2} cmd={Cmd:x2} sub={(Sub is { } s ? s.ToString("x2") : "--")} {Payload.ToHex()}";
}

public static class Gnp
{
    public const int App = 0x09;
    public const int Buds = 0x04;

    public const int CmdIdent = 0x02;
    public const int CmdDevice = 0x0D;
    public const int CmdStatus = 0x12;
    public const int CmdConfig = 0x13;
    public const int CmdNack = 0xFE;
    public const int CmdAck = 0xFF;

    public static byte[] Build(int dst, int src, int seq, FrameType type, int cmd, int? sub = null, byte[]? payload = null)
    {
        payload ??= [];
        var header = sub is null ? 5 : 6;
        var length = header + payload.Length;
        if (length is < 5 or > 62) throw new ArgumentException($"GNP frame length {length} outside 5..62");
        var frame = new byte[length];
        frame[0] = (byte)dst;
        frame[1] = (byte)src;
        frame[2] = (byte)seq;
        frame[3] = (byte)(((int)type << 6) | length);
        frame[4] = (byte)cmd;
        if (sub is { } s) frame[5] = (byte)s;
        payload.CopyTo(frame, header);
        return frame;
    }
}

/// <summary>Splits an RFCOMM byte stream into frames using the length bits of byte 3.</summary>
public sealed class FrameReader
{
    private byte[] _buffer = [];

    public List<Frame> Feed(ReadOnlySpan<byte> bytes)
    {
        _buffer = [.. _buffer, .. bytes];
        var frames = new List<Frame>();
        while (_buffer.Length >= 4)
        {
            var length = _buffer[3] & 0x3F;
            if (length < 5)
            {
                // Lost sync; drop what we have and resynchronise on the next read.
                _buffer = [];
                break;
            }
            if (_buffer.Length < length) break;
            frames.Add(new Frame(_buffer[..length]));
            _buffer = _buffer[length..];
        }
        return frames;
    }
}

public static class Hex
{
    public static string ToHex(this byte[] bytes) => string.Join(' ', bytes.Select(b => b.ToString("x2")));

    /// <summary>"04 09 6f" or "04096f".</summary>
    public static byte[] Bytes(string text) => Convert.FromHexString(text.Replace(" ", ""));
}
