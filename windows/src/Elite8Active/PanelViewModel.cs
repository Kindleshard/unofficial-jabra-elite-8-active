using System.Collections.ObjectModel;
using System.Windows.Input;
using System.Windows.Threading;
using Elite8Active.Protocol;
using Elite8Active.Store;

namespace Elite8Active.Tray;

/// <summary>What the panel needs from the rest of the app (shortcuts, start-up entry, quitting).</summary>
public interface IShell
{
    bool StartsWithWindows { get; set; }

    /// <summary>A shortcut that Windows wouldn't register, usually because another program has it.</summary>
    bool ShortcutUnavailable(SoundMode mode);

    /// <summary>Turns the shortcuts off while a new one is being typed, and applies the settings after.</summary>
    void PauseShortcuts(bool paused);

    void OpenBluetoothSettings();

    void Quit();
}

public enum GaugeKind { LeftBud, RightBud, Case }

public sealed class GaugeVm(GaugeKind kind, string label) : Observable
{
    public GaugeKind Kind => kind;
    public string Label => label;
    public int? Percent { get; private set; }
    public string Text { get; private set; } = "–";
    public bool IsOffline { get; private set; }

    public void Set(int? percent, bool offline)
    {
        Percent = offline ? null : percent;
        IsOffline = offline;
        Text = offline ? "Offline" : percent is { } p ? $"{p}%" : "–";
        RaiseAll();
    }
}

/// <summary>An on/off row. A null value means it hasn't been read yet.</summary>
public sealed class ToggleRow(string title, string? subtitle, Func<bool?> value, Func<bool> enabled, Action<bool> set) : Observable
{
    public string Title => title;
    public string? Subtitle => subtitle;
    public bool IsOn => value() == true;
    public bool IsEnabled => enabled() && value() is not null;
    public ICommand Toggle { get; } = new RelayCommand(_ => set(value() != true));
}

/// <summary>A row with one of a few values; choosing opens a menu.</summary>
public sealed class ChoiceRow(string title, IReadOnlyList<string> options, Func<int?> selected, Func<bool> enabled, Action<int> select, Func<string?>? note = null) : Observable
{
    public string Title => title;
    public IReadOnlyList<string> Options => options;
    public int? Selected => selected();
    public string SelectedLabel => selected() is { } i && i < options.Count ? options[i] : "–";
    public string? Note => note?.Invoke();
    public bool IsEnabled => enabled() && selected() is not null;
    public void Select(int index) => select(index);
}

/// <summary>A slider with a few fixed steps, shown only when it applies.</summary>
public sealed class StepRow(string title, int steps, string low, string high, Func<int?> value, Func<bool> visible, Func<bool> enabled, Action<int> set) : Observable
{
    public string Title => title;
    public double Maximum => steps - 1;
    public string Low => low;
    public string High => high;
    public double Value => value() ?? 0;
    public bool IsVisible => visible();
    public bool IsEnabled => enabled() && value() is not null;
    public ICommand Commit { get; } = new RelayCommand(v => set((int)Math.Round((double)v!)));
}

public sealed class PresetVm(string label, Func<bool> selected, Func<bool> enabled, Action apply, Action? delete = null) : Observable
{
    public string Label => label;
    public bool IsSelected => selected();
    public bool IsEnabled => enabled();
    public bool CanDelete => delete is not null;
    public ICommand Apply { get; } = new RelayCommand(_ => apply());
    public ICommand Delete { get; } = new RelayCommand(_ => delete?.Invoke());
}

public sealed class EqBandVm(string label, Func<double> value, Func<bool> enabled, Action<int> set) : Observable
{
    public string Label => label;
    public double Value => value();
    public bool IsEnabled => enabled();
    public ICommand Commit { get; } = new RelayCommand(v => set((int)Math.Round((double)v!)));
}

/// <summary>One global shortcut; clicking it waits for the new keys.</summary>
public sealed class HotkeyRow(SoundMode mode, Func<string> text, Func<bool> unavailable, Func<bool> enabled) : Observable
{
    private bool _recording;

    public SoundMode Mode => mode;
    public string Title => mode.Label();
    public string Text => _recording ? "Press the keys…" : text();
    public string? Problem => !_recording && enabled() && unavailable() ? "Taken by another program" : null;
    public bool IsEnabled => enabled();

    public bool IsRecording
    {
        get => _recording;
        set
        {
            _recording = value;
            RaiseAll();
        }
    }
}

public sealed class PanelViewModel : Observable
{
    private readonly EarbudsController _controller;
    private readonly SettingsStore _settings;
    private readonly IShell _shell;
    private readonly DispatcherTimer _errorTimer = new() { Interval = TimeSpan.FromSeconds(5) };
    private readonly List<Observable> _rows = [];
    private int _tab;
    private bool _namingEq;
    private string _eqNameDraft = "";

    public PanelViewModel(EarbudsController controller, SettingsStore settings, IShell shell)
    {
        _controller = controller;
        _settings = settings;
        _shell = shell;
        State = controller.State;
        _errorTimer.Tick += (_, _) =>
        {
            _errorTimer.Stop();
            _controller.DismissError();
        };

        bool Connected() => State.Connected;
        Left = Track(new GaugeVm(GaugeKind.LeftBud, "Left"));
        Right = Track(new GaugeVm(GaugeKind.RightBud, "Right"));
        Case = Track(new GaugeVm(GaugeKind.Case, "Case"));

        HearThroughLevel = Track(new StepRow(
            "How much you hear", Jabra.HearThroughSteps, "Less", "More",
            () => State.HearThroughLevel, () => State.Mode == SoundMode.HearThrough, Connected,
            step => _ = _controller.SetHearThroughLevelAsync(step)));

        Presets = [.. EqPreset.All.Select(p => Track(new PresetVm(
            p.Label, () => State.EqPreset == p, () => Connected() && State.EqGains is not null, () => _ = _controller.SetEqGainsAsync(p.Gains))))];
        Bands = [.. Jabra.EqBandLabels.Select((label, band) => Track(new EqBandVm(
            label, () => State.EqGains is { } g && band < g.Count ? g[band] : 0, () => Connected() && State.EqGains is not null, gain => SetBand(band, gain))))];

        ToggleRow ToggleFor(Toggle t, string? subtitle) => Track(new ToggleRow(
            t.Label, subtitle, () => State.Toggles.GetValueOrDefault(t),
            () => Connected() && (!t.NeedsWearing || State.WearingDetection), on => _ = _controller.SetToggleAsync(t, on)));
        ChoiceRow ChoiceFor(Choice c, string? title = null) => Track(new ChoiceRow(
            title ?? c.Label, [.. c.Options.Select(o => o.Label)], () => State.Choices.GetValueOrDefault(c), Connected, i => _ = _controller.SetChoiceAsync(c, i)));

        SpatialSound = ToggleFor(Toggle.SpatialSound, "Dolby spatial audio for music and video");
        WearingRows =
        [
            ToggleFor(Toggle.WearingDetection, "Detect when the earbuds are in your ears"),
            ToggleFor(Toggle.AutoPause, "Pause when you take an earbud out"),
            ToggleFor(Toggle.AutoAnswer, "Answer calls by putting an earbud in"),
            ToggleFor(Toggle.AutoMute, "Mute the mic when you take an earbud out"),
            ToggleFor(Toggle.AutoHearThrough, "Use HearThrough when wearing one earbud"),
        ];
        CallRows =
        [
            ToggleFor(Toggle.Sidetone, "Hear your own voice during calls"),
            Track(new StepRow(
                "Sidetone volume", Jabra.SidetoneLevels.Count, "Quieter", "Louder",
                () => State.SidetoneLevel is { } level && Jabra.SidetoneLevels.ToList().IndexOf(level) is >= 0 and var i ? i : null,
                () => State.Toggles.GetValueOrDefault(Toggle.Sidetone) == true, Connected,
                i => _ = _controller.SetSidetoneLevelAsync(Jabra.SidetoneLevels[i]))),
            ToggleFor(Toggle.MuteReminder, "A tone while the microphone is muted"),
            ToggleFor(Toggle.AutoVolume, "Louder calls in noisy places"),
            ChoiceFor(Choice.InCallEq),
        ];
        GeneralRows =
        [
            ChoiceFor(Choice.HearThroughAudio),
            ChoiceFor(Choice.SleepMode),
            ChoiceFor(Choice.Guidance),
            ToggleFor(Toggle.ReduceWind, "Less wind noise outdoors"),
        ];
        ButtonRows =
        [
            ChoiceFor(Choice.SoundModeCycle),
            Track(new ChoiceRow(
                "Press and hold", [.. Jabra.VoiceAssistants.Select(a => a.Label)],
                () => Jabra.VoiceAssistants.ToList().FindIndex(a => a.Code == State.VoiceAssistant) is >= 0 and var i ? i : null,
                Connected, i => _ = _controller.SetVoiceAssistantAsync(Jabra.VoiceAssistants[i].Code),
                () => State.VoiceAssistant == 0x11 ? "Needs Google Assistant on your phone" : null)),
        ];

        var app = settings.Settings;
        LowBatteryAlert = Track(new ToggleRow(
            "Low-battery notification", $"When an earbud or the case drops to {BatteryAlerts.Low} %",
            () => app.LowBatteryAlert, () => true, on => settings.Update(s => s.LowBatteryAlert = on)));
        Shortcuts = Track(new ToggleRow(
            "Keyboard shortcuts", "Switch sound modes from anywhere",
            () => app.HotkeysEnabled, () => true, on =>
            {
                settings.Update(s => s.HotkeysEnabled = on);
                _shell.PauseShortcuts(false);
            }));
        Hotkeys = [.. SoundModes.All.Select(mode => Track(new HotkeyRow(
            mode, () => app.Hotkeys.GetValueOrDefault(mode, "None"), () => _shell.ShortcutUnavailable(mode), () => app.HotkeysEnabled)))];
        StartWithWindows = Track(new ToggleRow(
            "Start with Windows", "Runs quietly in the notification area",
            () => _shell.StartsWithWindows, () => true, on => _shell.StartsWithWindows = on));

        SetMode = new RelayCommand(mode => _ = _controller.SetModeAsync((SoundMode)mode!));
        Refresh = new RelayCommand(_ => _ = _controller.RefreshAsync());
        OpenBluetoothSettings = new RelayCommand(_ => _shell.OpenBluetoothSettings());
        Quit = new RelayCommand(_ => _shell.Quit());
        BeginSaveEq = new RelayCommand(_ =>
        {
            EqNameDraft = "";
            IsNamingEq = true;
        });
        CancelSaveEq = new RelayCommand(_ => IsNamingEq = false);
        ConfirmSaveEq = new RelayCommand(_ =>
        {
            if (string.IsNullOrWhiteSpace(EqNameDraft) || State.EqGains is not { } gains) return;
            _settings.SaveEq(EqNameDraft, gains);
            IsNamingEq = false;
        });

        settings.Changed += () => System.Windows.Application.Current?.Dispatcher.BeginInvoke(RebuildSaved);
        RebuildSaved();
        Apply(State);
    }

    private T Track<T>(T row) where T : Observable
    {
        _rows.Add(row);
        return row;
    }

    public EarbudsState State { get; private set; }

    /// <summary>Takes a new state from the controller. Call on the UI thread.</summary>
    public void Apply(EarbudsState state)
    {
        State = state;
        var battery = state.Battery;
        Left.Set(battery?.Left, state.IsOffline(Side.Left));
        Right.Set(battery?.Right, state.IsOffline(Side.Right));
        Case.Set(battery?.Case, offline: false);
        if (state.Error is not null && !_errorTimer.IsEnabled) _errorTimer.Start();
        if (!CanSaveEq) _namingEq = false;
        foreach (var row in _rows) row.RaiseAll();
        foreach (var saved in SavedPresets) saved.RaiseAll();
        RaiseAll();
    }

    // ------------------------------------------------------------------ header

    public string Title => State.Name ?? "Jabra earbuds";

    public string StatusText => State.Status switch
    {
        ConnectionStatus.Connected => "Connected" + (State.Firmware is { } fw ? $" · FW {fw}" : ""),
        ConnectionStatus.Connecting => "Connecting…",
        ConnectionStatus.Failed => "Couldn't connect",
        ConnectionStatus.NotPaired => "Not paired with this PC",
        ConnectionStatus.BluetoothOff => "Bluetooth is off",
        _ => "Not connected to this PC",
    };

    /// <summary>"good", "bad" or "idle", for the status dot's colour.</summary>
    public string StatusKind => State.Status switch
    {
        ConnectionStatus.Connected => "good",
        ConnectionStatus.Failed => "bad",
        _ => "idle",
    };

    /// <summary>A line under the header: the last error, or what to do about the connection.</summary>
    public string? Notice => State.Error ?? State.Status switch
    {
        ConnectionStatus.Failed => State.ConnectFailure,
        ConnectionStatus.NotPaired => "Pair the earbuds with this PC in Bluetooth settings, then they'll show up here.",
        ConnectionStatus.BluetoothOff => "Turn Bluetooth on to use the earbuds with this PC.",
        ConnectionStatus.Away when State.HasData => "Showing the last values. Take the earbuds out, or connect them in Bluetooth settings.",
        ConnectionStatus.Away => "Take the earbuds out of the case, or connect them in Bluetooth settings.",
        _ => null,
    };

    public bool NoticeIsError => State.Error is not null || State.Status == ConnectionStatus.Failed;
    public bool ShowRetry => State.Error is null && State.Status == ConnectionStatus.Failed;
    public bool ShowBluetoothSettings => State.Error is null && State.Status is ConnectionStatus.NotPaired or ConnectionStatus.Away or ConnectionStatus.BluetoothOff;
    public bool IsBusy => State.Busy || State.Status == ConnectionStatus.Connecting;

    /// <summary>Values from before the connection dropped are shown faded.</summary>
    public double DataOpacity => State.Connected ? 1 : 0.45;

    public bool LeftBudOffline => State.IsOffline(Side.Left);
    public bool RightBudOffline => State.IsOffline(Side.Right);

    public GaugeVm Left { get; }
    public GaugeVm Right { get; }
    public GaugeVm Case { get; }

    public int Tab
    {
        get => _tab;
        set
        {
            _tab = value;
            Raise();
        }
    }

    // ------------------------------------------------------------------ sound

    public bool CanChangeSound => State.Connected && State.Mode is not null;
    public bool IsAnc => State.Mode == SoundMode.Anc;
    public bool IsHearThrough => State.Mode == SoundMode.HearThrough;
    public bool IsOff => State.Mode == SoundMode.Off;
    public ICommand SetMode { get; }
    public StepRow HearThroughLevel { get; }

    public string EqName => State.EqPreset?.Label ?? SavedMatch?.Name ?? (State.EqGains is null ? "" : "Custom");
    public IReadOnlyList<PresetVm> Presets { get; }
    public ObservableCollection<PresetVm> SavedPresets { get; } = [];
    public bool HasSavedPresets => SavedPresets.Count > 0;
    public IReadOnlyList<EqBandVm> Bands { get; }
    public ToggleRow SpatialSound { get; }

    private SavedEq? SavedMatch => State.EqGains is { } gains ? _settings.Settings.SavedEqs.FirstOrDefault(e => e.Gains.SequenceEqual(gains)) : null;

    /// <summary>A curve of your own that isn't saved yet.</summary>
    public bool CanSaveEq => State.EqGains is not null && State.EqPreset is null && SavedMatch is null;

    public bool IsNamingEq
    {
        get => _namingEq;
        set
        {
            _namingEq = value;
            RaiseAll();
        }
    }

    public bool ShowSaveEq => CanSaveEq && !IsNamingEq;

    public string EqNameDraft
    {
        get => _eqNameDraft;
        set
        {
            _eqNameDraft = value.Length > AppSettings.EqNameMaxLength ? value[..AppSettings.EqNameMaxLength] : value;
            Raise();
        }
    }

    public ICommand BeginSaveEq { get; }
    public ICommand ConfirmSaveEq { get; }
    public ICommand CancelSaveEq { get; }

    private void SetBand(int band, int gain)
    {
        if (State.EqGains is not { } gains || band >= gains.Count) return;
        var changed = gains.ToArray();
        changed[band] = Math.Clamp(gain, Jabra.EqGainMin, Jabra.EqGainMax);
        _ = _controller.SetEqGainsAsync(changed);
    }

    private void RebuildSaved()
    {
        SavedPresets.Clear();
        foreach (var saved in _settings.Settings.SavedEqs.ToList())
        {
            SavedPresets.Add(new PresetVm(
                saved.Name, () => State.EqGains is { } g && saved.Gains.SequenceEqual(g), () => State.Connected && State.EqGains is not null,
                () => _ = _controller.SetEqGainsAsync(saved.Gains), () => _settings.DeleteEq(saved.Name)));
        }
        foreach (var row in _rows) row.RaiseAll();
        RaiseAll();
    }

    // ------------------------------------------------------------------ earbud settings

    public IReadOnlyList<Observable> WearingRows { get; }
    public IReadOnlyList<Observable> CallRows { get; }
    public IReadOnlyList<Observable> GeneralRows { get; }
    public IReadOnlyList<Observable> ButtonRows { get; }
    public bool ShowWearingHint => State.Connected && !State.WearingDetection;

    // ------------------------------------------------------------------ this PC

    public ToggleRow LowBatteryAlert { get; }
    public ToggleRow Shortcuts { get; }
    public IReadOnlyList<HotkeyRow> Hotkeys { get; }
    public ToggleRow StartWithWindows { get; }
    public const string AppName = "Unofficial Jabra Elite 8 Active";

    public string Version => $"{AppName} {typeof(PanelViewModel).Assembly.GetName().Version?.ToString(3)} for Windows";
    public ICommand Refresh { get; }
    public ICommand OpenBluetoothSettings { get; }
    public ICommand Quit { get; }

    /// <summary>Starts or stops waiting for a shortcut's new keys; the shortcuts themselves are off meanwhile.</summary>
    public void SetRecording(HotkeyRow row, bool recording)
    {
        row.IsRecording = recording;
        _shell.PauseShortcuts(recording);
        foreach (var hotkey in Hotkeys) hotkey.RaiseAll();
    }

    /// <summary>The keys typed for a shortcut that's being changed. False if they can't be used.</summary>
    public bool SetHotkey(HotkeyRow row, Hotkey hotkey)
    {
        if (!hotkey.IsUsable) return false;
        _settings.Update(s => s.Hotkeys[row.Mode] = hotkey.ToString());
        return true;
    }
}
