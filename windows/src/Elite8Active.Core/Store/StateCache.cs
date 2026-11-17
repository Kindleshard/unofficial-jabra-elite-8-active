using System.Collections.Immutable;
using System.Text.Json;
using Elite8Active.Protocol;

namespace Elite8Active.Store;

/// <summary>
/// The last values read from the earbuds, kept on disk so the panel has something to show before
/// (and between) connections. Only what came from the earbuds is kept, not the connection.
/// </summary>
public static class StateCache
{
    private sealed class Cached
    {
        public string? Name { get; set; }
        public string? Firmware { get; set; }
        public bool? Linked { get; set; }
        public Side? Primary { get; set; }
        public int?[]? Battery { get; set; }
        public int? Mode { get; set; }
        public int? HearThrough { get; set; }
        public int? Sidetone { get; set; }
        public int? VoiceAssistant { get; set; }
        public int[]? Eq { get; set; }
        public Dictionary<string, bool> Toggles { get; set; } = [];
        public Dictionary<string, int> Choices { get; set; } = [];
    }

    public static string Encode(EarbudsState s) => JsonSerializer.Serialize(new Cached
    {
        Name = s.Name,
        Firmware = s.Firmware,
        Linked = s.Link?.Linked,
        Primary = s.Link?.Primary,
        Battery = s.BatteryReport is { } b ? [b.Primary, b.Other, b.Case] : null,
        Mode = (int?)s.Mode,
        HearThrough = s.HearThroughLevel,
        Sidetone = s.SidetoneLevel,
        VoiceAssistant = s.VoiceAssistant,
        Eq = s.EqGains?.ToArray(),
        Toggles = s.Toggles.Where(t => t.Value is not null).ToDictionary(t => t.Key.Key, t => t.Value!.Value),
        Choices = s.Choices.Where(c => c.Value is not null).ToDictionary(c => c.Key.Key, c => c.Value!.Value),
    });

    /// <summary>Null if there's nothing worth showing (the earbuds were never read).</summary>
    public static EarbudsState? Decode(string json)
    {
        Cached? c;
        try
        {
            c = JsonSerializer.Deserialize<Cached>(json);
        }
        catch (JsonException)
        {
            return null;
        }
        if (c?.Name is null) return null;
        return new EarbudsState
        {
            Name = c.Name,
            Firmware = c.Firmware,
            Link = c is { Linked: { } linked, Primary: { } primary } ? new EarbudLink(linked, primary) : null,
            BatteryReport = c.Battery is { Length: 3 } b ? new BatteryReport(b[0], b[1], b[2]) : null,
            Mode = c.Mode is { } mode ? SoundModes.Of(mode) : null,
            HearThroughLevel = c.HearThrough,
            SidetoneLevel = c.Sidetone,
            VoiceAssistant = c.VoiceAssistant,
            EqGains = c.Eq,
            Toggles = Toggle.All.Where(t => c.Toggles.ContainsKey(t.Key)).ToImmutableDictionary(t => t, t => (bool?)c.Toggles[t.Key]),
            Choices = Choice.All.Where(ch => c.Choices.ContainsKey(ch.Key)).ToImmutableDictionary(ch => ch, ch => (int?)c.Choices[ch.Key]),
        };
    }

    public static EarbudsState? Load(string path)
    {
        try
        {
            return File.Exists(path) ? Decode(File.ReadAllText(path)) : null;
        }
        catch (IOException)
        {
            return null;
        }
    }

    public static void Save(string path, EarbudsState state)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            File.WriteAllText(path, Encode(state));
        }
        catch (IOException)
        {
            // The cache is a convenience; the next change tries again.
        }
    }
}
