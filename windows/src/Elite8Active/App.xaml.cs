using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Interop;
using System.Windows.Threading;
using Elite8Active.Bluetooth;
using Elite8Active.Protocol;
using Elite8Active.Store;

namespace Elite8Active.Tray;

/// <summary>
/// The Windows app: lives in the notification area and controls Jabra Elite 8 Active
/// earbuds while they're connected to this PC. This class wires the parts together.
/// </summary>
public partial class App : Application, IShell
{
    private const string InstanceName = @"Local\Elite8Active.Instance";

    // Named signals a second start uses to talk to the copy that's already running.
    private const string ShowSignal = @"Local\Elite8Active.Show";
    private const string QuitSignal = @"Local\Elite8Active.Quit";
    private static string ModeSignal(SoundMode mode) => $@"Local\Elite8Active.Mode.{mode}";

    /// <summary>Closes the copy that's already running, e.g. before replacing the program file.</summary>
    private const string QuitSwitch = "--quit";

    /// <summary>"--mode anc", "--mode hearthrough" or "--mode off": switches the sound mode, for scripts and shortcuts.</summary>
    private const string ModeSwitch = "--mode";

    private Mutex? _instance;
    private readonly List<EventWaitHandle> _signals = [];
    private SettingsStore _settings = null!;
    private EarbudsController _controller = null!;
    private PanelViewModel _viewModel = null!;
    private PanelWindow _panel = null!;
    private TrayIcon _tray = null!;
    private Hotkeys _hotkeys = null!;
    private Osd _osd = null!;
    private readonly DispatcherTimer _cacheTimer = new() { Interval = TimeSpan.FromSeconds(1) };
    private string? _cached;
    private int _statePosted;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        if (DevTools.Run(e.Args))
        {
            Shutdown();
            return;
        }

        var quit = e.Args.Contains(QuitSwitch);
        var mode = RequestedMode(e.Args);
        // One copy at a time: a second start passes its request on to the first and ends.
        _instance = new Mutex(initiallyOwned: true, InstanceName, out var first);
        if (!first || quit)
        {
            var name = quit ? QuitSignal : mode is { } requested ? ModeSignal(requested) : ShowSignal;
            if (EventWaitHandle.TryOpenExisting(name, out var signal)) signal.Set();
            Shutdown();
            return;
        }
        Listen(ShowSignal, () => _panel.ShowPanel(atCursor: false));
        Listen(QuitSignal, Quit);
        foreach (var each in SoundModes.All) Listen(ModeSignal(each), () => OnShortcut(each));

        DispatcherUnhandledException += (_, error) =>
        {
            Log.Write($"error: {error.Exception}");
            error.Handled = true;
        };

        _settings = new SettingsStore(AppFolders.Data);
        _controller = new EarbudsController(new WindowsEarbudsHost(), _settings, StateCache.Load(AppFolders.StateCache), Log.Write);
        _hotkeys = new Hotkeys();
        _hotkeys.Pressed += OnShortcut;
        PauseShortcuts(false);
        _viewModel = new PanelViewModel(_controller, _settings, this);
        _panel = new PanelWindow(_viewModel);
        _osd = new Osd();
        _tray = new TrayIcon();
        _tray.Clicked += _panel.Toggle;
        _tray.MenuRequested += ShowMenu;
        _tray.Update(_controller.State);

        _cacheTimer.Tick += (_, _) => SaveCache();
        _controller.StateChanged += _ =>
        {
            // Changes come in bursts from other threads; one pass on the UI thread covers them all.
            if (Interlocked.Exchange(ref _statePosted, 1) == 0) Dispatcher.BeginInvoke(OnStateChanged);
        };
        _controller.LowBattery += (title, text) => Dispatcher.BeginInvoke(() => _tray.Notify(title, text));
        _controller.Start();
        Log.Write($"started {_viewModel.Version}");

        if (mode is { } wanted) _ = SwitchOnceConnected(wanted);
        else if (!e.Args.Contains(Autostart.BackgroundSwitch)) _panel.ShowPanel(atCursor: false);
    }

    private static SoundMode? RequestedMode(string[] args)
    {
        var at = Array.IndexOf(args, ModeSwitch);
        if (at < 0 || at + 1 >= args.Length) return null;
        return SoundModes.All.Cast<SoundMode?>().FirstOrDefault(m => m.ToString()!.Equals(args[at + 1], StringComparison.OrdinalIgnoreCase));
    }

    /// <summary>Runs <paramref name="action"/> on the UI thread whenever another start of the app raises the signal.</summary>
    private void Listen(string name, Action action)
    {
        var signal = new EventWaitHandle(false, EventResetMode.AutoReset, name);
        _signals.Add(signal);
        ThreadPool.RegisterWaitForSingleObject(signal, (_, _) => Dispatcher.BeginInvoke(action), null, Timeout.Infinite, executeOnlyOnce: false);
    }

    /// <summary>Started with a mode to switch to: the connection has to come up first.</summary>
    private async Task SwitchOnceConnected(SoundMode mode)
    {
        for (var waited = 0; waited < 10_000 && !_controller.State.Connected; waited += 200) await Task.Delay(200);
        OnShortcut(mode);
    }

    private void OnStateChanged()
    {
        Interlocked.Exchange(ref _statePosted, 0);
        var state = _controller.State;
        _viewModel.Apply(state);
        _tray.Update(state);
        _cacheTimer.Stop();
        _cacheTimer.Start();
    }

    /// <summary>Keeps the last values on disk, a moment after they stop changing.</summary>
    private void SaveCache()
    {
        _cacheTimer.Stop();
        var state = _controller.State;
        if (!state.HasData) return;
        var encoded = StateCache.Encode(state);
        if (encoded == _cached) return;
        _cached = encoded;
        Task.Run(() => StateCache.Save(AppFolders.StateCache, state));
    }

    private async void OnShortcut(SoundMode mode)
    {
        var ok = await _controller.SetModeAsync(mode);
        var done = mode == SoundMode.Off ? "ANC and HearThrough off" : $"{mode.Label()} on";
        _osd.Show(ok ? done : "Earbuds not connected", ok);
        Log.Write($"shortcut for {mode.Label()}: {(ok ? "done" : "not connected")}");
    }

    /// <summary>The tray icon's right-click menu.</summary>
    private void ShowMenu()
    {
        var state = _controller.State;
        var menu = new ContextMenu { Placement = PlacementMode.MousePoint };
        foreach (var mode in SoundModes.All)
        {
            var item = new MenuItem { Header = mode.Label(), IsChecked = state.Connected && state.Mode == mode, IsEnabled = state.Connected };
            item.Click += (_, _) => _ = _controller.SetModeAsync(mode);
            menu.Items.Add(item);
        }
        menu.Items.Add(new Separator());
        var open = new MenuItem { Header = "Open" };
        open.Click += (_, _) => _panel.ShowPanel(atCursor: true);
        menu.Items.Add(open);
        var quit = new MenuItem { Header = "Quit" };
        quit.Click += (_, _) => Quit();
        menu.Items.Add(quit);
        menu.IsOpen = true;
        // A menu opened from the notification area only closes on a click elsewhere if it's in front.
        if (PresentationSource.FromVisual(menu) is HwndSource source) SetForegroundWindow(source.Handle);
    }

    // ------------------------------------------------------------------ what the panel asks for

    public bool StartsWithWindows
    {
        get => Autostart.IsEnabled;
        set => Autostart.Set(value);
    }

    public bool ShortcutUnavailable(SoundMode mode) => _hotkeys.IsUnavailable(mode);

    public void PauseShortcuts(bool paused)
    {
        if (paused)
        {
            _hotkeys.Clear();
            return;
        }
        _hotkeys.Apply(_settings.Settings);
        foreach (var mode in SoundModes.All.Where(_hotkeys.IsUnavailable))
        {
            Log.Write($"shortcut {_settings.Settings.Hotkeys.GetValueOrDefault(mode)} for {mode.Label()} is taken by another program");
        }
    }

    public void OpenBluetoothSettings() => Process.Start(new ProcessStartInfo("ms-settings:bluetooth") { UseShellExecute = true });

    public void Quit()
    {
        _panel.AllowClose = true;
        Shutdown();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        if (_tray is not null)
        {
            SaveCache();
            _hotkeys.Dispose();
            _tray.Dispose();
            _controller.Dispose();
            Log.Write("stopped");
        }
        foreach (var signal in _signals) signal.Dispose();
        _instance?.Dispose();
        base.OnExit(e);
    }

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr window);
}
