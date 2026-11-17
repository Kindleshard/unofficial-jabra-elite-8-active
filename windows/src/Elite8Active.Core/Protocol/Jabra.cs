using System.Text;

namespace Elite8Active.Protocol;

/// <summary>
/// Features decoded for the Jabra Elite 8 Active (firmware 4.6.0). Every value here was confirmed
/// against Sound+ and written/read back from the earbuds; see jabra-protocol-notes.md. The Android
/// app's gnp/Jabra.kt holds the same table.
/// </summary>
public static class Jabra
{
    public const int IdentName = 0x00;
    public const int IdentFirmware = 0x03;

    /// <summary>
    /// IDENT 11: the product ID, little-endian. 47 2e = 11847, the number Sound+ files this model's
    /// settings under (11847_pelican.json). Every setting here was decoded on that model only, so
    /// the app won't connect to anything else.
    /// </summary>
    public const int IdentProductId = 0x11;
    public const int SupportedProductId = 11847;
    public const string SupportedName = "Jabra Elite 8 Active";

    public static int? ProductId(byte[] payload) => payload.Length >= 2 ? payload[0] | payload[1] << 8 : null;

    /// <summary>By product ID; by product name only if the earbuds don't report an ID.</summary>
    public static bool IsSupported(int? productId, string productName) =>
        productId is { } id ? id == SupportedProductId : productName.Contains("Elite 8 Active", StringComparison.OrdinalIgnoreCase);

    public static string UnsupportedMessage(string productName) =>
        $"This app only works with the {SupportedName}. " + (productName.Length > 0 ? $"\"{productName}\" isn't supported." : "These earbuds aren't supported.");

    public const int StatusBattery = 0x02;

    public const int ConfigSoundMode = 0xBE;
    public const int SoundModeSlot = 0x01;

    /// <summary>Sound mode slot 02: how much HearThrough lets in, stored reversed (00 = the most, 04 = the least).</summary>
    public const int HearThroughLevelSlot = 0x02;
    public const int HearThroughSteps = 5;
    public static int HearThroughStep(int value) => Math.Clamp(HearThroughSteps - 1 - value, 0, HearThroughSteps - 1);
    public static int HearThroughValue(int step) => HearThroughSteps - 1 - Math.Clamp(step, 0, HearThroughSteps - 1);

    /// <summary>
    /// Voice assistant for press and hold: DEVICE 4d with slot 03. 00 = the connected device's own
    /// assistant, 11 = Google Assistant built in.
    /// </summary>
    public const int DeviceVoiceAssistant = 0x4D;
    public const int VoiceAssistantSlot = 0x03;
    public static readonly IReadOnlyList<(string Label, int Code)> VoiceAssistants =
        [("Phone's assistant", 0x00), ("Google Assistant", 0x11)];

    /// <summary>Sidetone: [mute][level], mute 01 = off; the level is signed, −9..+6 in steps of 3 (0 = default).</summary>
    public const int ConfigSidetone = 0x7C;
    public static readonly IReadOnlyList<int> SidetoneLevels = [-9, -6, -3, 0, 3, 6];
    public static byte[] SidetonePayload(bool on, int level) => [(byte)(on ? 0 : 1), (byte)(sbyte)level];
    public static int? SidetoneLevel(byte[] payload) => payload.Length > 1 ? (sbyte)payload[1] : null;

    public const int ConfigEq = 0x7D;
    public const int ConfigEqEnabled = 0x7E;
    public const int ConfigName = 0x56;

    /// <summary>
    /// DEVICE 4c is both the event subscription (read and write a 32-bit mask) and the events
    /// themselves, whose first byte is the type: 04 &lt;sub&gt; = CONFIG &lt;sub&gt; changed, 07 OO PP = the
    /// earbuds' link (<see cref="ParseLink"/>), 09 01 xx = sound mode changed.
    /// </summary>
    public const int DeviceEvents = 0x4C;
    public const int EventSetting = 0x04;
    public const int EventLink = 0x07;
    public const int EventSoundMode = 0x09;

    // Mask bit n seems to subscribe to event type n: the earbuds offer 00 00 02 96 (types 1, 2, 4,
    // 7 and 9) and Sound+ always includes 4, 7 and 9, the ones this app uses.
    private const uint EventsUsed = (1u << EventSetting) | (1u << EventLink) | (1u << EventSoundMode);

    /// <summary>
    /// The mask to write back to the earbuds: the current one plus the events the app uses. Every
    /// write, even an unchanged one, makes the earbuds send a link event with the current state.
    /// </summary>
    public static byte[] Subscription(byte[] current)
    {
        if (current.Length != 4) return current;
        var mask = ((uint)current[0] << 24 | (uint)current[1] << 16 | (uint)current[2] << 8 | current[3]) | EventsUsed;
        return [(byte)(mask >> 24), (byte)(mask >> 16), (byte)(mask >> 8), (byte)mask];
    }

    /// <summary>
    /// Event 07 OO PP: OO = 01 while the earbuds are linked to each other, PP = the earbud holding
    /// the link to this device (01 right, 00 left). An earbud going into the case closes the link,
    /// and the battery report swaps order when PP changes.
    /// </summary>
    public static EarbudLink? ParseLink(byte[] payload)
    {
        if (payload.Length < 3 || payload[0] != EventLink) return null;
        return new EarbudLink(Linked: payload[1] == 1, Primary: payload[2] == 1 ? Side.Right : Side.Left);
    }

    /// <summary>Identity strings are length-prefixed.</summary>
    public static string LengthPrefixedString(byte[] payload)
    {
        if (payload.Length == 0) return "";
        var length = Math.Min(payload[0], payload.Length - 1);
        return Encoding.ASCII.GetString(payload, 1, length);
    }

    // The case's ID in the battery report (IDENT 28 lists the parts as 04 05 15).
    private const int CaseId = 0x15;

    /// <summary>
    /// STATUS 02: 80 PP 00 00 NN, then NN entries of [id][flags][%]. PP is the earbud that holds
    /// the link to this device; the entries are the case (15) and the other earbud. 0 % = not reported.
    /// </summary>
    public static BatteryReport ParseBattery(byte[] payload)
    {
        if (payload.Length < 5) throw new ArgumentException($"battery payload too short: {payload.ToHex()}");
        static int? Level(byte b) => b > 0 ? b : null;
        int? @case = null, other = null;
        for (var i = 0; i < payload[4]; i++)
        {
            var at = 5 + 3 * i;
            if (at + 2 >= payload.Length) break;
            if (payload[at] == CaseId) @case = Level(payload[at + 2]);
            else other = Level(payload[at + 2]);
        }
        return new BatteryReport(Level(payload[1]), other, @case);
    }

    // Fixed per-band (frequency field, Q field); only the gains change.
    private static readonly (int Frequency, int Q)[] EqBands = [(180, 1730), (750, 1654), (3000, 1577), (12000, 1597), (22800, 2088)];
    public static readonly IReadOnlyList<string> EqBandLabels = ["Bass", "Low mid", "Mid", "High mid", "Treble"];

    /// <summary>Per-band gain range Sound+'s custom equalizer writes (probably 0.01 dB units).</summary>
    public const int EqGainMin = -450;
    public const int EqGainMax = 450;

    /// <summary>Write payload: band count, three zero bytes, then (freq u16, gain s16, Q u16) per band, big-endian.</summary>
    public static byte[] EqPayload(IReadOnlyList<int> gains)
    {
        if (gains.Count != EqBands.Length) throw new ArgumentException($"expected {EqBands.Length} gains");
        var payload = new List<byte> { (byte)EqBands.Length, 0, 0, 0 };
        for (var i = 0; i < EqBands.Length; i++)
        {
            AddU16(payload, EqBands[i].Frequency);
            AddU16(payload, Math.Clamp(gains[i], EqGainMin, EqGainMax) & 0xFFFF);
            AddU16(payload, EqBands[i].Q);
        }
        return [.. payload];
    }

    /// <summary>Read-back payload: 4-byte header, then 7 bytes per band (the last band has no type byte).</summary>
    public static IReadOnlyList<int> ParseEqGains(byte[] payload)
    {
        var gains = new int[payload[0]];
        for (var i = 0; i < gains.Length; i++)
        {
            var offset = 4 + 7 * i + 2;
            gains[i] = (short)((payload[offset] << 8) | payload[offset + 1]);
        }
        return gains;
    }

    private static void AddU16(List<byte> bytes, int value)
    {
        bytes.Add((byte)(value >> 8));
        bytes.Add((byte)value);
    }
}

public enum SoundMode { Anc = 0x04, HearThrough = 0x02, Off = 0x01 }

public static class SoundModes
{
    public static readonly IReadOnlyList<SoundMode> All = [SoundMode.Anc, SoundMode.HearThrough, SoundMode.Off];

    public static SoundMode? Of(int code) => Enum.IsDefined((SoundMode)code) ? (SoundMode)code : null;

    public static string Label(this SoundMode mode) => mode switch
    {
        SoundMode.Anc => "ANC",
        SoundMode.HearThrough => "HearThrough",
        _ => "Off",
    };
}

public enum Side { Left, Right }

/// <summary>Whether the earbuds are linked to each other, and which one talks to this device.</summary>
public sealed record EarbudLink(bool Linked, Side Primary)
{
    /// <summary>The other earbud is offline (in the case or out of range) while they aren't linked.</summary>
    public bool IsOnline(Side side) => Linked || side == Primary;
}

/// <summary>The battery report as the earbuds send it: by role, not by side.</summary>
public sealed record BatteryReport(int? Primary, int? Other, int? Case)
{
    /// <summary>
    /// Left and right, given which earbud holds the link (the right one until the earbuds say).
    /// An offline earbud keeps reporting its last level, so it's left out.
    /// </summary>
    public Battery ForSides(EarbudLink? link)
    {
        var other = link?.Linked != false ? Other : null;
        return (link?.Primary ?? Side.Right) == Side.Right
            ? new Battery(Left: other, Right: Primary, Case: Case)
            : new Battery(Left: Primary, Right: other, Case: Case);
    }
}

/// <summary>Percentages, null when that part is offline or didn't report.</summary>
public sealed record Battery(int? Left, int? Right, int? Case);

public sealed class EqPreset
{
    private EqPreset(string label, IReadOnlyList<int> gains)
    {
        Label = label;
        Gains = gains;
    }

    public string Label { get; }
    public IReadOnlyList<int> Gains { get; }

    public static readonly EqPreset Neutral = new("Neutral", [0, 0, 0, 0, 0]);
    public static readonly EqPreset BassBoost = new("Bass boost", [270, -118, 0, 0, 0]);
    public static readonly EqPreset Smooth = new("Smooth", [0, 118, 0, -118, 0]);
    public static readonly EqPreset Speech = new("Speech", [-270, 0, 0, 61, -120]);
    public static readonly EqPreset TrebleBoost = new("Treble boost", [-90, -118, 0, 118, 240]);
    public static readonly EqPreset Energize = new("Energize", [180, 0, -118, -61, 180]);

    public static readonly IReadOnlyList<EqPreset> All = [Neutral, BassBoost, Smooth, Speech, TrebleBoost, Energize];

    /// <summary>The earbuds store only the curve, so the preset is recognised by its gains.</summary>
    public static EqPreset? Matching(IReadOnlyList<int>? gains) =>
        gains is null ? null : All.FirstOrDefault(p => p.Gains.SequenceEqual(gains));
}

/// <summary>
/// An on/off setting. <c>compare</c> limits matching to the first N bytes of the value, for settings
/// that carry extra bytes (Spatial Sound). <see cref="NeedsWearing"/> mirrors Sound+: the earbuds
/// ignore writes to these unless wearing detection is on.
/// </summary>
public sealed class Toggle
{
    private readonly byte[] _prefix;
    private readonly byte[] _off;
    private readonly byte[] _on;
    private readonly int _compare;

    private Toggle(string key, string label, int sub, byte[] read, byte[] prefix, byte[] off, byte[] on, bool needsWearing = false, int compare = -1)
    {
        Key = key;
        Label = label;
        Sub = sub;
        ReadPayload = read;
        _prefix = prefix;
        _off = off;
        _on = on;
        NeedsWearing = needsWearing;
        _compare = compare;
    }

    /// <summary>A stable name, for files on disk.</summary>
    public string Key { get; }
    public string Label { get; }
    public int Sub { get; }
    public byte[] ReadPayload { get; }
    public bool NeedsWearing { get; }

    private static byte[] B(params byte[] values) => values;

    public static readonly Toggle SpatialSound = new("spatialSound", "Spatial Sound", 0xE4, B(), B(), B(4, 4, 1), B(5, 5, 1), compare: 2);
    public static readonly Toggle WearingDetection = new("wearingDetection", "Wearing detection", 0x92, B(), B(), B(0), B(1));
    public static readonly Toggle AutoPause = new("autoPause", "Auto-pause music", 0x8E, B(), B(0), B(0), B(1), needsWearing: true);
    public static readonly Toggle AutoAnswer = new("autoAnswer", "Auto-answer calls", 0x91, B(), B(1), B(0), B(1), needsWearing: true);
    public static readonly Toggle AutoMute = new("autoMute", "Auto-mute microphones", 0x8F, B(), B(0), B(0), B(1), needsWearing: true);
    public static readonly Toggle AutoHearThrough = new("autoHearThrough", "Automatic HearThrough in mono mode", 0xF0, B(), B(), B(0), B(1), needsWearing: true);
    // Only the first byte is on/off; the second is the sidetone level (see Jabra.SidetonePayload).
    public static readonly Toggle Sidetone = new("sidetone", "Sidetone", 0x7C, B(), B(), B(1, 0), B(0, 0), compare: 1);
    public static readonly Toggle MuteReminder = new("muteReminder", "Mute reminder", 0x1E, B(), B(), B(0), B(0x14));
    public static readonly Toggle AutoVolume = new("autoVolume", "Automatic call volume", 0xF1, B(), B(), B(0), B(1));
    public static readonly Toggle ReduceWind = new("reduceWind", "Reduce wind", 0xEF, B(0), B(0), B(0), B(1));

    public static readonly IReadOnlyList<Toggle> All =
        [SpatialSound, WearingDetection, AutoPause, AutoAnswer, AutoMute, AutoHearThrough, Sidetone, MuteReminder, AutoVolume, ReduceWind];

    public bool? Parse(byte[] payload)
    {
        if (payload.Length < _prefix.Length) return null;
        var value = payload[_prefix.Length..];
        if (Same(value, _on)) return true;
        if (Same(value, _off)) return false;
        return null;
    }

    private bool Same(byte[] value, byte[] other) => _compare > 0
        ? value.Length >= _compare && value.AsSpan(0, _compare).SequenceEqual(other.AsSpan(0, _compare))
        : value.AsSpan().SequenceEqual(other);

    public byte[] WritePayload(bool enabled) => [.. _prefix, .. enabled ? _on : _off];

    public static Toggle? BySub(int sub) => All.FirstOrDefault(t => t.Sub == sub);

    public override string ToString() => Key;
}

/// <summary>A multiple-choice setting: each option's value is written and read back as-is.</summary>
public sealed class Choice
{
    private Choice(string key, string label, int sub, params (string Label, byte[] Value)[] options)
    {
        Key = key;
        Label = label;
        Sub = sub;
        Options = options;
    }

    public string Key { get; }
    public string Label { get; }
    public int Sub { get; }
    public IReadOnlyList<(string Label, byte[] Value)> Options { get; }

    private static byte[] B(params byte[] values) => values;

    public static readonly Choice SoundModeCycle = new("soundModeCycle", "Mode button cycles", 0xBF,
        ("HearThrough/ANC/Off", B(3, 0, 1, 2)), ("HearThrough/Off", B(2, 0, 1)), ("HearThrough/ANC", B(2, 1, 2)));
    public static readonly Choice HearThroughAudio = new("hearThroughAudio", "When HearThrough turns on", 0x76,
        ("Keep playing", B(1)), ("Mute audio", B(2)));
    public static readonly Choice InCallEq = new("inCallEq", "In-call audio", 0x15,
        ("Neutral", B(0)), ("Bass", B(1)), ("Treble", B(2)));
    public static readonly Choice SleepMode = new("sleepMode", "Sleep after", 0x90,
        ("15 min", B(0x03)), ("30 min", B(0x06)), ("1 hour", B(0x0C)), ("2 hours", B(0x18)), ("Never", B(0)));
    public static readonly Choice Guidance = new("guidance", "Headset guidance", 0x3A,
        ("Voice", B(1)), ("Tone", B(0)), ("None", B(2)));

    public static readonly IReadOnlyList<Choice> All = [SoundModeCycle, HearThroughAudio, InCallEq, SleepMode, Guidance];

    /// <summary>Index into <see cref="Options"/>, or null for a value Sound+ doesn't offer.</summary>
    public int? Parse(byte[] payload)
    {
        for (var i = 0; i < Options.Count; i++)
        {
            if (Options[i].Value.AsSpan().SequenceEqual(payload)) return i;
        }
        return null;
    }

    public static Choice? BySub(int sub) => All.FirstOrDefault(c => c.Sub == sub);

    public override string ToString() => Key;
}
