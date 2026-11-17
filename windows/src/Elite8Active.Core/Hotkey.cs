namespace Elite8Active;

/// <summary>The values are Windows' MOD_* flags.</summary>
[Flags]
public enum HotkeyModifiers { None = 0, Alt = 1, Ctrl = 2, Shift = 4, Win = 8 }

/// <summary>A global keyboard shortcut, written like "Ctrl+Alt+F9".</summary>
public sealed record Hotkey(HotkeyModifiers Modifiers, int VirtualKey)
{
    // Windows virtual-key codes for keys with a name of their own; letters and digits are their ASCII codes.
    private static readonly Dictionary<string, int> Named = new(StringComparer.OrdinalIgnoreCase)
    {
        ["Space"] = 0x20,
        ["PageUp"] = 0x21,
        ["PageDown"] = 0x22,
        ["End"] = 0x23,
        ["Home"] = 0x24,
        ["Left"] = 0x25,
        ["Up"] = 0x26,
        ["Right"] = 0x27,
        ["Down"] = 0x28,
        ["Insert"] = 0x2D,
        ["Delete"] = 0x2E,
        ["Pause"] = 0x13,
    };

    /// <summary>A shortcut needs Ctrl, Alt or Win, or it would swallow an ordinary key everywhere.</summary>
    public bool IsUsable => (Modifiers & (HotkeyModifiers.Ctrl | HotkeyModifiers.Alt | HotkeyModifiers.Win)) != 0 && VirtualKey != 0;

    public static Hotkey? Parse(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;
        var modifiers = HotkeyModifiers.None;
        int? key = null;
        foreach (var part in text.Split('+', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
        {
            if (Enum.TryParse<HotkeyModifiers>(part, ignoreCase: true, out var modifier) && modifier != HotkeyModifiers.None && !int.TryParse(part, out _))
            {
                modifiers |= modifier;
            }
            else if (key is null && KeyCode(part) is { } code)
            {
                key = code;
            }
            else
            {
                return null;
            }
        }
        return key is { } k ? new Hotkey(modifiers, k) : null;
    }

    public override string ToString()
    {
        var parts = new List<string>();
        foreach (var modifier in new[] { HotkeyModifiers.Ctrl, HotkeyModifiers.Alt, HotkeyModifiers.Shift, HotkeyModifiers.Win })
        {
            if (Modifiers.HasFlag(modifier)) parts.Add(modifier.ToString());
        }
        parts.Add(KeyName(VirtualKey));
        return string.Join('+', parts);
    }

    public static string KeyName(int key) => key switch
    {
        >= 0x70 and <= 0x87 => $"F{key - 0x6F}",
        >= 0x41 and <= 0x5A or >= 0x30 and <= 0x39 => ((char)key).ToString(),
        >= 0x60 and <= 0x69 => $"Num{key - 0x60}",
        _ => Named.FirstOrDefault(n => n.Value == key).Key ?? $"Key{key}",
    };

    private static int? KeyCode(string name)
    {
        if (name.Length == 1 && char.IsAsciiLetterOrDigit(name[0])) return char.ToUpperInvariant(name[0]);
        if (name.Length is 2 or 3 && (name[0] is 'F' or 'f') && int.TryParse(name[1..], out var f) && f is >= 1 and <= 24) return 0x6F + f;
        if (name.StartsWith("Num", StringComparison.OrdinalIgnoreCase) && int.TryParse(name[3..], out var n) && n is >= 0 and <= 9) return 0x60 + n;
        if (name.StartsWith("Key", StringComparison.OrdinalIgnoreCase) && int.TryParse(name[3..], out var raw) && raw is > 0 and < 256) return raw;
        return Named.TryGetValue(name, out var code) ? code : null;
    }
}
