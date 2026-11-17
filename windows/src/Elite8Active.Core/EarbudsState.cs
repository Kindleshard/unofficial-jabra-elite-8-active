using System.Collections.Immutable;
using Elite8Active.Protocol;

namespace Elite8Active;

public enum ConnectionStatus
{
    /// <summary>Bluetooth is switched off on this PC (or it has none).</summary>
    BluetoothOff,

    /// <summary>No Jabra earbuds are paired with this PC.</summary>
    NotPaired,

    /// <summary>Paired, but not connected to this PC right now (in the case, or with the phone only).</summary>
    Away,

    Connecting,

    Connected,

    /// <summary>Connected to this PC, but the control channel couldn't be opened.</summary>
    Failed,
}

/// <summary>Everything the panel shows: the connection, and the values last read from the earbuds.</summary>
public sealed record EarbudsState
{
    public ConnectionStatus Status { get; init; } = ConnectionStatus.Away;
    public string? Error { get; init; }

    /// <summary>Why the last connection attempt failed.</summary>
    public string? ConnectFailure { get; init; }

    public int Pending { get; init; }
    public string? Name { get; init; }
    public string? Firmware { get; init; }

    /// <summary>Which earbud talks to this PC and whether the other is linked; null until they say.</summary>
    public EarbudLink? Link { get; init; }

    public BatteryReport? BatteryReport { get; init; }
    public SoundMode? Mode { get; init; }

    /// <summary>0 (lets in the least) .. 4 (the most).</summary>
    public int? HearThroughLevel { get; init; }

    /// <summary>Signed, one of <see cref="Jabra.SidetoneLevels"/>.</summary>
    public int? SidetoneLevel { get; init; }

    /// <summary>Raw code from DEVICE 4d 03, see <see cref="Jabra.VoiceAssistants"/>.</summary>
    public int? VoiceAssistant { get; init; }

    public IReadOnlyList<int>? EqGains { get; init; }
    public ImmutableDictionary<Toggle, bool?> Toggles { get; init; } = ImmutableDictionary<Toggle, bool?>.Empty;
    public ImmutableDictionary<Choice, int?> Choices { get; init; } = ImmutableDictionary<Choice, int?>.Empty;

    public bool Connected => Status == ConnectionStatus.Connected;

    /// <summary>Something has been read from the earbuds, so the panel has values to show.</summary>
    public bool HasData => Name is not null;

    public Battery? Battery => BatteryReport?.ForSides(Link);

    /// <summary>Known to be in the case or out of range. Only meaningful while connected.</summary>
    public bool IsOffline(Side side) => Connected && Link?.IsOnline(side) == false;

    public EqPreset? EqPreset => Protocol.EqPreset.Matching(EqGains);
    public bool WearingDetection => Toggles.GetValueOrDefault(Toggle.WearingDetection) == true;
    public bool Busy => Pending > 0;
}
