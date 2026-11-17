using System.Runtime.InteropServices;
using System.Windows.Interop;
using Elite8Active.Protocol;
using Elite8Active.Store;

namespace Elite8Active.Tray;

/// <summary>The global keyboard shortcuts, one per sound mode, registered with Windows.</summary>
public sealed class Hotkeys : IDisposable
{
    private const int WmHotkey = 0x0312;
    private const uint NoRepeat = 0x4000;

    // A window that only receives messages; Windows posts WM_HOTKEY to it.
    private readonly HwndSource _window = new(new HwndSourceParameters("Elite8Active.Hotkeys") { ParentWindow = new IntPtr(-3), WindowStyle = 0 });
    private readonly Dictionary<int, SoundMode> _registered = [];
    private readonly HashSet<SoundMode> _unavailable = [];

    public Hotkeys() => _window.AddHook(OnMessage);

    public event Action<SoundMode>? Pressed;

    /// <summary>A shortcut Windows refused, usually because another program already has those keys.</summary>
    public bool IsUnavailable(SoundMode mode) => _unavailable.Contains(mode);

    /// <summary>Registers the shortcuts in the settings, replacing whatever was registered before.</summary>
    public void Apply(AppSettings settings)
    {
        Clear();
        if (!settings.HotkeysEnabled) return;
        foreach (var mode in SoundModes.All)
        {
            if (Hotkey.Parse(settings.Hotkeys.GetValueOrDefault(mode)) is not { IsUsable: true } hotkey) continue;
            var id = (int)mode;
            if (RegisterHotKey(_window.Handle, id, (uint)hotkey.Modifiers | NoRepeat, (uint)hotkey.VirtualKey)) _registered[id] = mode;
            else _unavailable.Add(mode);
        }
    }

    public void Clear()
    {
        foreach (var id in _registered.Keys) UnregisterHotKey(_window.Handle, id);
        _registered.Clear();
        _unavailable.Clear();
    }

    private IntPtr OnMessage(IntPtr window, int message, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (message == WmHotkey && _registered.TryGetValue(wParam.ToInt32(), out var mode))
        {
            handled = true;
            Pressed?.Invoke(mode);
        }
        return IntPtr.Zero;
    }

    public void Dispose()
    {
        Clear();
        _window.Dispose();
    }

    [DllImport("user32.dll")]
    private static extern bool RegisterHotKey(IntPtr window, int id, uint modifiers, uint key);

    [DllImport("user32.dll")]
    private static extern bool UnregisterHotKey(IntPtr window, int id);
}
