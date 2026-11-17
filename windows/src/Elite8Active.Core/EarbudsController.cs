using System.Collections.Immutable;
using System.Text;
using Elite8Active.Bluetooth;
using Elite8Active.Protocol;
using Elite8Active.Store;

namespace Elite8Active;

/// <summary>
/// Keeps the control channel open while the earbuds are connected to this PC, holds what was read
/// from them, and applies changes. No UI: the panel, tray icon and shortcuts all sit on top of this.
/// </summary>
public sealed class EarbudsController : IDisposable
{
    private static readonly TimeSpan FirstRetry = TimeSpan.FromSeconds(3);
    private static readonly TimeSpan LongestRetry = TimeSpan.FromSeconds(30);

    private readonly IEarbudsHost _host;
    private readonly SettingsStore _settings;
    private readonly BatteryAlerts _alerts;
    private readonly Action<string> _log;
    private readonly SemaphoreSlim _wake = new(0);
    private readonly CancellationTokenSource _stop = new();

    private EarbudsState _state;
    private EarbudSession? _session;
    private PairedDevice? _device;
    private int _absentChecks;
    private DateTime _retryAt = DateTime.MinValue;
    private TimeSpan _retryDelay = FirstRetry;

    public EarbudsController(IEarbudsHost host, SettingsStore settings, EarbudsState? cached = null, Action<string>? log = null)
    {
        _host = host;
        _settings = settings;
        _alerts = new BatteryAlerts(settings.Settings.WarnedLow);
        _log = log ?? (_ => { });
        _state = cached ?? new EarbudsState();
    }

    /// <summary>How often Windows is asked whether the earbuds are (still) connected to this PC.</summary>
    public TimeSpan PollInterval { get; init; } = TimeSpan.FromSeconds(2);

    public EarbudsState State => Volatile.Read(ref _state);

    /// <summary>Raised after every change, on whichever thread made it.</summary>
    public event Action<EarbudsState>? StateChanged;

    /// <summary>An earbud or the case has just become low: a title and a line of text.</summary>
    public event Action<string, string>? LowBattery;

    private void Update(Func<EarbudsState, EarbudsState> change)
    {
        EarbudsState before, after;
        do
        {
            before = Volatile.Read(ref _state);
            after = change(before);
        } while (Interlocked.CompareExchange(ref _state, after, before) != before);
        StateChanged?.Invoke(after);
    }

    // ------------------------------------------------------------------ connection

    public void Start() => Task.Run(async () =>
    {
        while (!_stop.IsCancellationRequested)
        {
            try
            {
                await TickAsync().ConfigureAwait(false);
            }
            catch (Exception e)
            {
                _log($"connection check failed: {e.Message}");
            }
            try
            {
                await _wake.WaitAsync(PollInterval, _stop.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    });

    /// <summary>One look at Windows: connect, keep, or let go. Only the loop (and tests) call this.</summary>
    internal async Task TickAsync()
    {
        if (Volatile.Read(ref _session) is { IsOpen: true })
        {
            // Follow Windows: once the earbuds stop being connected to this PC (in the case, or
            // "Disconnect" in Bluetooth settings), let the control channel go too. Two checks in
            // a row, so a blip doesn't count.
            if (_device is { } device && !_host.IsInUse(device))
            {
                if (++_absentChecks >= 2) EndSession(ConnectionStatus.Away);
            }
            else
            {
                _absentChecks = 0;
            }
            return;
        }

        var known = _settings.Settings.KnownAddress;
        var paired = _host.PairedDevices().Where(IsEarbuds)
            .OrderBy(d => PairedDevice.FormatAddress(d.Address) != known).ToList();
        if (paired.Count == 0)
        {
            // Windows lists no devices at all while Bluetooth is off, so ask which it is.
            SetStatus(_host.BluetoothOn ? ConnectionStatus.NotPaired : ConnectionStatus.BluetoothOff);
            return;
        }
        var here = paired.Where(_host.IsInUse).ToList();
        if (here.Count == 0)
        {
            _retryAt = DateTime.MinValue;
            _retryDelay = FirstRetry;
            SetStatus(ConnectionStatus.Away);
            return;
        }
        if (DateTime.UtcNow >= _retryAt) await ConnectAsync(here).ConfigureAwait(false);
    }

    private async Task ConnectAsync(IReadOnlyList<PairedDevice> devices)
    {
        Update(s => s with { Status = ConnectionStatus.Connecting, ConnectFailure = null });
        string? failure = null;
        var unsupported = false;
        foreach (var device in devices)
        {
            EarbudSession? session = null;
            string name;
            int? product;
            try
            {
                var channel = await Task.Run(() => _host.Open(device)).ConfigureAwait(false);
                session = new EarbudSession(channel);
                // Make sure it's really the earbuds' control port answering, and which model they are.
                var ident = await session.RequestAsync(FrameType.Read, Gnp.CmdIdent, Jabra.IdentName, timeoutMs: 2000).ConfigureAwait(false);
                name = Jabra.LengthPrefixedString(ident.Payload);
                product = await ReadProductIdAsync(session).ConfigureAwait(false);
            }
            catch (IOException e)
            {
                session?.Dispose();
                // Keep the clearest reason: a wrong model says more than an unreachable one.
                if (!unsupported) failure = e.Message;
                _log($"connect via {device.ShortAddress} failed: {e.Message}");
                continue;
            }
            if (!Jabra.IsSupported(product, name))
            {
                session.Dispose();
                unsupported = true;
                failure = Jabra.UnsupportedMessage(name);
                _log($"connect via {device.ShortAddress}: unsupported model {name} ({product})");
                continue;
            }
            Remember(device);
            StartSession(device, session);
            return;
        }
        _retryAt = DateTime.UtcNow + _retryDelay;
        _retryDelay = _retryDelay * 2 < LongestRetry ? _retryDelay * 2 : LongestRetry;
        Update(s => s with { Status = ConnectionStatus.Failed, ConnectFailure = failure ?? "The earbuds' control channel couldn't be opened." });
    }

    private static async Task<int?> ReadProductIdAsync(EarbudSession session)
    {
        try
        {
            return Jabra.ProductId(await session.ReadAsync(Gnp.CmdIdent, Jabra.IdentProductId).ConfigureAwait(false));
        }
        catch (GnpNackException)
        {
            return null;
        }
    }

    /// <summary>Remembers the earbuds' address once they've answered, so they're still found after a rename.</summary>
    private void Remember(PairedDevice device)
    {
        var address = PairedDevice.FormatAddress(device.Address);
        if (_settings.Settings.KnownAddress != address) _settings.Update(s => s.KnownAddress = address);
    }

    /// <summary>A Jabra device by its Bluetooth name, or the earbuds this app has connected to before.</summary>
    private bool IsEarbuds(PairedDevice device) =>
        device.Name.Contains("Jabra", StringComparison.OrdinalIgnoreCase) ||
        _settings.Settings.KnownAddress is { } known && PairedDevice.FormatAddress(device.Address) == known;

    private void StartSession(PairedDevice device, EarbudSession session)
    {
        _device = device;
        _absentChecks = 0;
        _retryDelay = FirstRetry;
        Volatile.Write(ref _session, session);
        session.EventReceived += frame => OnEvent(session, frame);
        session.Closed += () => OnClosed(session);
        _log($"connected via {device.ShortAddress}");
        Update(s => s with { Status = ConnectionStatus.Connected, ConnectFailure = null });
        if (!session.IsOpen) OnClosed(session);
        else _ = RunAsync(ReadAllAsync);
    }

    /// <summary>The link dropped by itself; the next check decides whether to reconnect.</summary>
    private void OnClosed(EarbudSession session)
    {
        if (Interlocked.CompareExchange(ref _session, null, session) != session) return;
        _log("connection closed");
        SetStatus(ConnectionStatus.Away);
        _wake.Release();
    }

    private void EndSession(ConnectionStatus status)
    {
        Interlocked.Exchange(ref _session, null)?.Dispose();
        SetStatus(status);
    }

    private void SetStatus(ConnectionStatus status)
    {
        if (State.Status != status) Update(s => s with { Status = status });
    }

    /// <summary>Reads everything again, or tries to connect straight away if there's no connection.</summary>
    public Task RefreshAsync()
    {
        if (Volatile.Read(ref _session) is { IsOpen: true }) return RunAsync(ReadAllAsync);
        _retryAt = DateTime.MinValue;
        _wake.Release();
        return Task.CompletedTask;
    }

    // ------------------------------------------------------------------ changes

    public Task<bool> SetModeAsync(SoundMode mode) =>
        ChangeAsync(s => s with { Mode = mode }, ReadModeAsync, session => session.WriteSoundModeAsync(mode));

    public Task<bool> SetHearThroughLevelAsync(int step) => ChangeAsync(
        s => s with { HearThroughLevel = step }, ReadHearThroughLevelAsync,
        session => session.WriteAsync(Gnp.CmdConfig, Jabra.ConfigSoundMode, [Jabra.HearThroughLevelSlot, (byte)Jabra.HearThroughValue(step)]));

    /// <summary>Same sequence as Sound+: enable EQ, write the curve, and disable EQ for the flat curve.</summary>
    public Task<bool> SetEqGainsAsync(IReadOnlyList<int> gains) => ChangeAsync(s => s with { EqGains = gains }, ReadEqAsync, async session =>
    {
        await session.WriteAsync(Gnp.CmdConfig, Jabra.ConfigEqEnabled, [1]).ConfigureAwait(false);
        await session.WriteAsync(Gnp.CmdConfig, Jabra.ConfigEq, Jabra.EqPayload(gains)).ConfigureAwait(false);
        if (gains.All(g => g == 0)) await session.WriteAsync(Gnp.CmdConfig, Jabra.ConfigEqEnabled, [0]).ConfigureAwait(false);
    });

    public Task<bool> SetToggleAsync(Toggle toggle, bool enabled) => ChangeAsync(
        s => s with { Toggles = s.Toggles.SetItem(toggle, enabled) },
        async session =>
        {
            await ReadToggleAsync(session, toggle).ConfigureAwait(false);
            // The earbuds keep or drop the dependent settings themselves; re-read them.
            if (toggle == Toggle.WearingDetection)
            {
                foreach (var dependent in Toggle.All.Where(t => t.NeedsWearing)) await ReadToggleAsync(session, dependent).ConfigureAwait(false);
            }
        },
        session =>
        {
            // Sidetone's on/off shares its value with the level, so keep the level as it is.
            var payload = toggle == Toggle.Sidetone ? Jabra.SidetonePayload(enabled, State.SidetoneLevel ?? 0) : toggle.WritePayload(enabled);
            return session.WriteAsync(Gnp.CmdConfig, toggle.Sub, payload);
        });

    public Task<bool> SetSidetoneLevelAsync(int level) => ChangeAsync(
        s => s with { SidetoneLevel = level }, session => ReadToggleAsync(session, Toggle.Sidetone),
        session => session.WriteAsync(Gnp.CmdConfig, Jabra.ConfigSidetone, Jabra.SidetonePayload(State.Toggles.GetValueOrDefault(Toggle.Sidetone) == true, level)));

    public Task<bool> SetChoiceAsync(Choice choice, int index) => ChangeAsync(
        s => s with { Choices = s.Choices.SetItem(choice, index) }, session => ReadChoiceAsync(session, choice),
        session => session.WriteAsync(Gnp.CmdConfig, choice.Sub, choice.Options[index].Value));

    public Task<bool> SetVoiceAssistantAsync(int code) => ChangeAsync(
        s => s with { VoiceAssistant = code }, ReadVoiceAssistantAsync,
        session => session.WriteAsync(Gnp.CmdDevice, Jabra.DeviceVoiceAssistant, [Jabra.VoiceAssistantSlot, (byte)code]));

    public void DismissError() => Update(s => s with { Error = null });

    /// <summary>
    /// Shows <paramref name="optimistic"/> at once, writes, then reads the real value back, which
    /// also undoes the change if the earbuds refused it.
    /// </summary>
    private Task<bool> ChangeAsync(Func<EarbudsState, EarbudsState> optimistic, Func<EarbudSession, Task> readBack, Func<EarbudSession, Task> write) =>
        RunAsync(async session =>
        {
            Update(optimistic);
            await write(session).ConfigureAwait(false);
            await readBack(session).ConfigureAwait(false);
        }, onError: readBack);

    /// <summary>True if <paramref name="block"/> ran to the end.</summary>
    private async Task<bool> RunAsync(Func<EarbudSession, Task> block, Func<EarbudSession, Task>? onError = null)
    {
        Update(s => s with { Pending = s.Pending + 1 });
        try
        {
            var session = await SessionAsync().ConfigureAwait(false);
            if (session is null)
            {
                Update(s => s with { Error = "The earbuds aren't connected to this PC." });
                return false;
            }
            try
            {
                await block(session).ConfigureAwait(false);
                return true;
            }
            catch (Exception e) when (e is IOException or ArgumentException or IndexOutOfRangeException)
            {
                Update(s => s with { Error = e is IOException ? e.Message : "The earbuds sent something unexpected." });
                if (onError is not null && session.IsOpen)
                {
                    try
                    {
                        await onError(session).ConfigureAwait(false);
                    }
                    catch (Exception again) when (again is IOException or ArgumentException or IndexOutOfRangeException)
                    {
                        // The value stays as shown until the next read.
                    }
                }
                return false;
            }
        }
        finally
        {
            Update(s => s with { Pending = s.Pending - 1 });
        }
    }

    /// <summary>The open session; a change made while it's being opened waits a few seconds for it.</summary>
    private async Task<EarbudSession?> SessionAsync()
    {
        for (var waited = 0; waited < 5000 && State.Status == ConnectionStatus.Connecting; waited += 100)
        {
            await Task.Delay(100).ConfigureAwait(false);
        }
        return Volatile.Read(ref _session) is { IsOpen: true } session ? session : null;
    }

    // ------------------------------------------------------------------ reads

    private async Task ReadAllAsync(EarbudSession session)
    {
        await ReadNameAsync(session).ConfigureAwait(false);
        var firmware = Jabra.LengthPrefixedString(await session.ReadAsync(Gnp.CmdIdent, Jabra.IdentFirmware).ConfigureAwait(false));
        Update(s => s with { Firmware = firmware });
        // The link first, so the battery report can be told apart into left and right.
        if (await session.ReadLinkAsync().ConfigureAwait(false) is { } link) Update(s => s with { Link = link });
        await ReadBatteryAsync(session).ConfigureAwait(false);
        await ReadModeAsync(session).ConfigureAwait(false);
        await ReadHearThroughLevelAsync(session).ConfigureAwait(false);
        await ReadEqAsync(session).ConfigureAwait(false);
        foreach (var toggle in Toggle.All) await ReadToggleAsync(session, toggle).ConfigureAwait(false);
        foreach (var choice in Choice.All) await ReadChoiceAsync(session, choice).ConfigureAwait(false);
        await ReadVoiceAssistantAsync(session).ConfigureAwait(false);
    }

    private async Task ReadNameAsync(EarbudSession session)
    {
        var name = Encoding.ASCII.GetString(await session.ReadAsync(Gnp.CmdConfig, Jabra.ConfigName).ConfigureAwait(false));
        Update(s => s with { Name = name });
    }

    private async Task ReadBatteryAsync(EarbudSession session)
    {
        var report = await session.ReadBatteryReportAsync().ConfigureAwait(false);
        Update(s => s with { BatteryReport = report });
        CheckBattery();
    }

    private async Task ReadModeAsync(EarbudSession session)
    {
        var mode = await session.ReadSoundModeAsync().ConfigureAwait(false);
        Update(s => s with { Mode = mode });
    }

    private async Task ReadHearThroughLevelAsync(EarbudSession session)
    {
        var value = (await session.ReadAsync(Gnp.CmdConfig, Jabra.ConfigSoundMode, [Jabra.HearThroughLevelSlot]).ConfigureAwait(false))[0];
        Update(s => s with { HearThroughLevel = Jabra.HearThroughStep(value) });
    }

    private async Task ReadVoiceAssistantAsync(EarbudSession session)
    {
        var code = (await session.ReadAsync(Gnp.CmdDevice, Jabra.DeviceVoiceAssistant, [Jabra.VoiceAssistantSlot]).ConfigureAwait(false))[0];
        Update(s => s with { VoiceAssistant = code });
    }

    private async Task ReadEqAsync(EarbudSession session)
    {
        var gains = Jabra.ParseEqGains(await session.ReadAsync(Gnp.CmdConfig, Jabra.ConfigEq, [0]).ConfigureAwait(false));
        Update(s => s with { EqGains = gains });
    }

    private async Task ReadToggleAsync(EarbudSession session, Toggle toggle)
    {
        var payload = await session.ReadAsync(Gnp.CmdConfig, toggle.Sub, toggle.ReadPayload).ConfigureAwait(false);
        var value = toggle.Parse(payload);
        Update(s => s with
        {
            Toggles = s.Toggles.SetItem(toggle, value),
            SidetoneLevel = toggle == Toggle.Sidetone ? Jabra.SidetoneLevel(payload) : s.SidetoneLevel,
        });
    }

    private async Task ReadChoiceAsync(EarbudSession session, Choice choice)
    {
        var index = choice.Parse(await session.ReadAsync(Gnp.CmdConfig, choice.Sub).ConfigureAwait(false));
        Update(s => s with { Choices = s.Choices.SetItem(choice, index) });
    }

    /// <summary>Re-reads a CONFIG setting the earbuds say has changed.</summary>
    private async Task ReadSettingAsync(EarbudSession session, int sub)
    {
        switch (sub)
        {
            case Jabra.ConfigSoundMode:
                await ReadModeAsync(session).ConfigureAwait(false);
                await ReadHearThroughLevelAsync(session).ConfigureAwait(false);
                break;
            case Jabra.ConfigEq:
                await ReadEqAsync(session).ConfigureAwait(false);
                break;
            case Jabra.ConfigName:
                await ReadNameAsync(session).ConfigureAwait(false);
                break;
            default:
                if (Toggle.BySub(sub) is { } toggle) await ReadToggleAsync(session, toggle).ConfigureAwait(false);
                if (Choice.BySub(sub) is { } choice) await ReadChoiceAsync(session, choice).ConfigureAwait(false);
                break;
        }
    }

    /// <summary>Keeps the panel in sync with changes made on the earbuds, the phone or the case.</summary>
    private void OnEvent(EarbudSession session, Frame frame)
    {
        var payload = frame.Payload;
        if (frame.Cmd == Gnp.CmdStatus && frame.Sub == Jabra.StatusBattery)
        {
            if (payload.Length < 5) return;
            var report = Jabra.ParseBattery(payload);
            Update(s => s with { BatteryReport = report });
            CheckBattery();
        }
        else if (frame.Cmd == Gnp.CmdDevice && frame.Sub == Jabra.DeviceEvents && payload.Length > 0)
        {
            switch (payload[0])
            {
                case Jabra.EventLink when Jabra.ParseLink(payload) is { } link:
                    Update(s => s with { Link = link });
                    CheckBattery();
                    break;
                case Jabra.EventSoundMode:
                    Reread(session, ReadModeAsync);
                    break;
                case Jabra.EventSetting when payload.Length > 1:
                    Reread(session, s => ReadSettingAsync(s, payload[1]));
                    break;
            }
        }
    }

    // Off the reader thread: it has to stay free to deliver the replies.
    private static void Reread(EarbudSession session, Func<EarbudSession, Task> read) => Task.Run(async () =>
    {
        try
        {
            await read(session).ConfigureAwait(false);
        }
        catch (Exception e) when (e is IOException or ArgumentException or IndexOutOfRangeException)
        {
            // The value stays as shown until the next read.
        }
    });

    private void CheckBattery()
    {
        if (!_settings.Settings.LowBatteryAlert || State.Battery is not { } battery) return;
        IReadOnlyList<(string Part, int Level)> low;
        lock (_alerts)
        {
            var before = _alerts.Warned.Count;
            low = _alerts.NewlyLow(battery);
            if (low.Count > 0 || _alerts.Warned.Count != before) _settings.Update(s => s.WarnedLow = [.. _alerts.Warned]);
        }
        if (low.Count == 0) return;
        var (title, text) = BatteryAlerts.Message(low);
        LowBattery?.Invoke(title, text);
    }

    public void Dispose()
    {
        _stop.Cancel();
        Interlocked.Exchange(ref _session, null)?.Dispose();
    }
}
