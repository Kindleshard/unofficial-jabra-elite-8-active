using System.Text.Json;
using System.Text.Json.Serialization;
using Elite8Active.Protocol;

namespace Elite8Active.Store;

/// <summary>A custom EQ curve saved under a name.</summary>
public sealed record SavedEq(string Name, int[] Gains);

/// <summary>The app's own options, kept in settings.json (the earbuds' settings live on the earbuds).</summary>
public sealed class AppSettings
{
    public const int MaxSavedEqs = 12;
    public const int EqNameMaxLength = 20;

    public bool LowBatteryAlert { get; set; } = true;
    public bool HotkeysEnabled { get; set; } = true;

    /// <summary>
    /// Global shortcuts per sound mode. Three modifiers on purpose: Ctrl+Alt+letter is AltGr on many
    /// keyboards, Ctrl+Alt+F-keys belong to graphics drivers, and Ctrl+Shift+F-keys to editors.
    /// </summary>
    public Dictionary<SoundMode, string> Hotkeys { get; set; } = new()
    {
        [SoundMode.Anc] = "Ctrl+Alt+Shift+F9",
        [SoundMode.HearThrough] = "Ctrl+Alt+Shift+F10",
        [SoundMode.Off] = "Ctrl+Alt+Shift+F11",
    };

    /// <summary>Your own EQ presets, newest first (the earbuds only hold the current curve).</summary>
    public List<SavedEq> SavedEqs { get; set; } = [];

    /// <summary>The earbuds' Bluetooth address once they've answered, so they're found even if renamed.</summary>
    public string? KnownAddress { get; set; }

    /// <summary>Parts already warned about as low, so a restart doesn't warn again.</summary>
    public List<string> WarnedLow { get; set; } = [];
}

public sealed class SettingsStore
{
    private static readonly JsonSerializerOptions Json = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        Converters = { new JsonStringEnumConverter() },
    };

    private readonly string _path;
    private readonly object _gate = new();

    public SettingsStore(string directory)
    {
        _path = Path.Combine(directory, "settings.json");
        Settings = Load(_path);
    }

    public AppSettings Settings { get; }

    /// <summary>Raised after every change, on the thread that made it.</summary>
    public event Action? Changed;

    public void Update(Action<AppSettings> change)
    {
        lock (_gate)
        {
            change(Settings);
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
                File.WriteAllText(_path, JsonSerializer.Serialize(Settings, Json));
            }
            catch (IOException)
            {
                // Still applies for this run.
            }
        }
        Changed?.Invoke();
    }

    /// <summary>Saves the curve as <paramref name="name"/>, replacing one with the same name.</summary>
    public void SaveEq(string name, IReadOnlyList<int> gains) => Update(s =>
    {
        name = name.Trim();
        s.SavedEqs.RemoveAll(e => e.Name.Equals(name, StringComparison.OrdinalIgnoreCase));
        s.SavedEqs.Insert(0, new SavedEq(name, [.. gains]));
        if (s.SavedEqs.Count > AppSettings.MaxSavedEqs) s.SavedEqs.RemoveRange(AppSettings.MaxSavedEqs, s.SavedEqs.Count - AppSettings.MaxSavedEqs);
    });

    public void DeleteEq(string name) => Update(s => s.SavedEqs.RemoveAll(e => e.Name == name));

    private static AppSettings Load(string path)
    {
        try
        {
            if (File.Exists(path)) return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(path), Json) ?? new AppSettings();
        }
        catch (Exception e) when (e is IOException or JsonException)
        {
            // A damaged file shouldn't stop the app; start from the defaults.
        }
        return new AppSettings();
    }
}
