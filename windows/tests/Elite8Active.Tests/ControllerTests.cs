using Elite8Active.Bluetooth;
using Elite8Active.Protocol;
using Elite8Active.Store;

namespace Elite8Active.Tests;

public class ControllerTests : IDisposable
{
    private readonly string _folder = Path.Combine(Path.GetTempPath(), "Elite8ActiveTests", Guid.NewGuid().ToString("N"));
    private readonly FakeEarbuds _buds = new();
    private readonly FakeHost _host = new();
    private readonly SettingsStore _settings;
    private readonly EarbudsController _controller;

    public ControllerTests()
    {
        _host.OnOpen = () => _buds;
        _settings = new SettingsStore(_folder);
        _controller = new EarbudsController(_host, _settings);
    }

    public void Dispose()
    {
        _controller.Dispose();
        if (Directory.Exists(_folder)) Directory.Delete(_folder, recursive: true);
    }

    private static async Task Until(Func<bool> condition, string what)
    {
        for (var waited = 0; waited < 5000; waited += 20)
        {
            if (condition()) return;
            await Task.Delay(20);
        }
        Assert.Fail($"Timed out waiting until {what}");
    }

    /// <summary>Connects and waits for the first full read.</summary>
    private async Task Connect()
    {
        await _controller.TickAsync();
        await Until(() => _controller.State is { Pending: 0, VoiceAssistant: not null }, "everything is read");
    }

    private EarbudsState State => _controller.State;

    [Fact]
    public async Task ConnectsAndReadsEverything()
    {
        await Connect();

        Assert.Equal(ConnectionStatus.Connected, State.Status);
        Assert.Equal("Jabra Elite 8 Active", State.Name);
        Assert.Equal("4.6.0", State.Firmware);
        Assert.Equal(new Battery(Left: 85, Right: 80, Case: 90), State.Battery);
        Assert.Equal(SoundMode.Anc, State.Mode);
        Assert.Equal(3, State.HearThroughLevel);
        Assert.Same(EqPreset.BassBoost, State.EqPreset);
        Assert.True(State.WearingDetection);
        Assert.False(State.Toggles[Toggle.SpatialSound]);
        Assert.True(State.Toggles[Toggle.AutoPause]);
        Assert.True(State.Toggles[Toggle.Sidetone]);
        Assert.Equal(-3, State.SidetoneLevel);
        Assert.Equal(1, State.Choices[Choice.SleepMode]);
        Assert.Equal(0x11, State.VoiceAssistant);
        Assert.Null(State.Error);
    }

    [Fact]
    public async Task ChangesAreWrittenAndReadBack()
    {
        await Connect();

        Assert.True(await _controller.SetModeAsync(SoundMode.HearThrough));
        Assert.Equal(SoundMode.HearThrough, _buds.Mode);
        Assert.Equal(SoundMode.HearThrough, State.Mode);
        Assert.Contains("13 be 01 02", _buds.Writes);

        Assert.True(await _controller.SetHearThroughLevelAsync(4));
        Assert.Equal(0, _buds.HearThroughValue);
        Assert.Equal(4, State.HearThroughLevel);

        Assert.True(await _controller.SetToggleAsync(Toggle.ReduceWind, true));
        Assert.Equal(Hex.Bytes("0001"), _buds.Config[0xEF]);
        Assert.True(State.Toggles[Toggle.ReduceWind]);

        Assert.True(await _controller.SetChoiceAsync(Choice.Guidance, 1));
        Assert.Equal(Hex.Bytes("00"), _buds.Config[0x3A]);
        Assert.Equal(1, State.Choices[Choice.Guidance]);

        Assert.True(await _controller.SetVoiceAssistantAsync(0x00));
        Assert.Equal(0, _buds.VoiceAssistant);
    }

    [Fact]
    public async Task EqIsEnabledWrittenAndSwitchedOffForFlat()
    {
        await Connect();

        Assert.True(await _controller.SetEqGainsAsync(EqPreset.Speech.Gains));
        Assert.Equal(EqPreset.Speech.Gains, _buds.EqGains);
        Assert.Same(EqPreset.Speech, State.EqPreset);

        Assert.True(await _controller.SetEqGainsAsync(EqPreset.Neutral.Gains));
        // Sound+'s sequence: on, the curve, then off again for the flat curve.
        Assert.Equal(new[] { "13 7e 01", "13 7e 00" }, _buds.Writes.Where(w => w.StartsWith("13 7e")).TakeLast(2));
    }

    [Fact]
    public async Task SidetoneKeepsItsLevelWhenSwitched()
    {
        await Connect();

        Assert.True(await _controller.SetToggleAsync(Toggle.Sidetone, false));
        Assert.Equal(Hex.Bytes("01fd"), _buds.Config[0x7C]);
        Assert.True(await _controller.SetSidetoneLevelAsync(6));
        Assert.Equal(Hex.Bytes("0106"), _buds.Config[0x7C]);
        Assert.Equal(6, State.SidetoneLevel);
        Assert.False(State.Toggles[Toggle.Sidetone]);
    }

    [Fact]
    public async Task ARefusedChangeShowsTheReasonAndTheRealValue()
    {
        await Connect();
        _buds.Refused.Add(Jabra.ConfigSoundMode);

        Assert.False(await _controller.SetModeAsync(SoundMode.Off));

        Assert.Equal(SoundMode.Anc, State.Mode);
        Assert.Contains("refused", State.Error);
        _controller.DismissError();
        Assert.Null(State.Error);
    }

    [Fact]
    public async Task FollowsChangesMadeElsewhere()
    {
        await Connect();

        // The phone app switched the mode: the earbuds only say that it changed.
        _buds.Mode = SoundMode.Off;
        _buds.SendEvent(Gnp.CmdDevice, Jabra.DeviceEvents, Jabra.EventSoundMode, 1, 1);
        await Until(() => State.Mode == SoundMode.Off, "the mode is re-read");

        _buds.Config[0xE4] = [5, 5, 1];
        _buds.SendEvent(Gnp.CmdDevice, Jabra.DeviceEvents, Jabra.EventSetting, 0xE4);
        await Until(() => State.Toggles[Toggle.SpatialSound] == true, "Spatial Sound is re-read");

        _buds.SendEvent(Gnp.CmdStatus, Jabra.StatusBattery, Hex.Bytes("80460000021500 5005 0050"));
        await Until(() => State.Battery == new Battery(80, 70, 80), "the battery event arrives");
    }

    [Fact]
    public async Task AnEarbudInTheCaseGoesOfflineAndSidesFollowTheLink()
    {
        await Connect();

        _buds.SendEvent(Gnp.CmdDevice, Jabra.DeviceEvents, Jabra.EventLink, 0, 1);
        await Until(() => State.IsOffline(Side.Left), "the left earbud is offline");
        Assert.Equal(new Battery(null, 80, 90), State.Battery);
        Assert.False(State.IsOffline(Side.Right));

        // The left earbud takes the link over: the first level in the report is now the left one.
        _buds.SendEvent(Gnp.CmdDevice, Jabra.DeviceEvents, Jabra.EventLink, 1, 0);
        await Until(() => State.Battery == new Battery(80, 85, 90), "left and right swap");
    }

    [Fact]
    public async Task LetsGoWhenWindowsDisconnectsTheEarbuds()
    {
        await Connect();

        _host.InUse = false;
        await _controller.TickAsync();
        Assert.Equal(ConnectionStatus.Connected, State.Status);
        await _controller.TickAsync();

        Assert.Equal(ConnectionStatus.Away, State.Status);
        Assert.True(_buds.Disposed);
        // The last values stay, for the panel to show faded.
        Assert.Equal("Jabra Elite 8 Active", State.Name);
        Assert.Equal(1, _host.Opens);
    }

    [Fact]
    public async Task ReconnectsAfterTheLinkDrops()
    {
        await Connect();

        _buds.Drop();
        await Until(() => State.Status == ConnectionStatus.Away, "the drop is noticed");

        var again = new FakeEarbuds { Mode = SoundMode.Off };
        _host.OnOpen = () => again;
        await _controller.TickAsync();
        await Until(() => State is { Status: ConnectionStatus.Connected, Mode: SoundMode.Off, Pending: 0 }, "it reconnects and reads");
    }

    [Fact]
    public async Task DoesNotReachForEarbudsThatAreNotOnThisPc()
    {
        _host.InUse = false;
        await _controller.TickAsync();
        Assert.Equal(ConnectionStatus.Away, State.Status);
        Assert.Equal(0, _host.Opens);

        _host.Paired.Clear();
        await _controller.TickAsync();
        Assert.Equal(ConnectionStatus.NotPaired, State.Status);

        // With Bluetooth off Windows lists nothing either; that's a different thing to tell the user.
        _host.BluetoothOn = false;
        await _controller.TickAsync();
        Assert.Equal(ConnectionStatus.BluetoothOff, State.Status);

        Assert.False(await _controller.SetModeAsync(SoundMode.Off));
        Assert.Contains("aren't connected", State.Error);
    }

    [Fact]
    public async Task AFailedConnectionWaitsBeforeTryingAgain()
    {
        _host.OnOpen = () => throw new IOException("Another program on this PC is already using the earbuds' control channel.");

        await _controller.TickAsync();
        Assert.Equal(ConnectionStatus.Failed, State.Status);
        Assert.Contains("Another program", State.ConnectFailure);

        await _controller.TickAsync();
        Assert.Equal(1, _host.Opens);

        // Asking directly doesn't wait for the retry time.
        _host.OnOpen = () => _buds;
        await _controller.RefreshAsync();
        await _controller.TickAsync();
        Assert.Equal(ConnectionStatus.Connected, State.Status);
    }

    [Fact]
    public async Task RefusesOtherModels()
    {
        _buds.Name = "Jabra Elite 10";
        _buds.ProductId = 12345;

        await _controller.TickAsync();

        Assert.Equal(ConnectionStatus.Failed, State.Status);
        Assert.Equal("This app only works with the Jabra Elite 8 Active. \"Jabra Elite 10\" isn't supported.", State.ConnectFailure);
        Assert.True(_buds.Disposed);
        // Nothing was written to the other model.
        Assert.Empty(_buds.Writes);
    }

    [Fact]
    public async Task AcceptsTheModelByNameWhenThereIsNoProductId()
    {
        _buds.ProductId = null;
        await Connect();
        Assert.Equal(ConnectionStatus.Connected, State.Status);
    }

    [Fact]
    public async Task FindsRenamedEarbudsByTheAddressThatWorkedBefore()
    {
        _host.Paired.Add(new PairedDevice(0x0011223344CD, "Speakers in the kitchen"));
        await Connect();
        Assert.Equal("00:11:22:33:44:AB", _settings.Settings.KnownAddress);

        // Renamed in the phone app: the name no longer says Jabra.
        _controller.Dispose();
        _host.Paired[0] = new PairedDevice(0x0011223344AB, "My earbuds");
        _host.OnOpen = () => new FakeEarbuds { Name = "My earbuds" };
        using var again = new EarbudsController(_host, new SettingsStore(_folder));
        await again.TickAsync();
        Assert.Equal(ConnectionStatus.Connected, again.State.Status);
        Assert.Equal(2, _host.Opens);
    }

    [Fact]
    public async Task WarnsOncePerLowPartUntilItHasCharged()
    {
        var warnings = new List<string>();
        _controller.LowBattery += (title, text) => warnings.Add($"{title}: {text}");
        _buds.Battery = Hex.Bytes("80500000021500 0a05 0055");
        await Connect();

        Assert.Equal(new[] { "Case battery low: Case 10%. Charge the case soon." }, warnings);
        Assert.Equal(new[] { "Case" }, _settings.Settings.WarnedLow);

        _buds.SendEvent(Gnp.CmdStatus, Jabra.StatusBattery, Hex.Bytes("80500000021500 0505 0055"));
        _buds.SendEvent(Gnp.CmdStatus, Jabra.StatusBattery, Hex.Bytes("80500000021500 6405 0055"));
        await Until(() => _settings.Settings.WarnedLow.Count == 0, "the case has charged");
        _buds.SendEvent(Gnp.CmdStatus, Jabra.StatusBattery, Hex.Bytes("80080000021500 6405 0055"));
        await Until(() => warnings.Count == 2, "the right earbud is low");
        Assert.Equal("Right earbud battery low: Right earbud 8%. Put them in the case to charge.", warnings[1]);
    }

    [Fact]
    public async Task NoWarningWhenTheAlertIsOff()
    {
        _settings.Update(s => s.LowBatteryAlert = false);
        var warned = false;
        _controller.LowBattery += (_, _) => warned = true;
        _buds.Battery = Hex.Bytes("80050000021500 0505 0005");
        await Connect();
        Assert.False(warned);
    }
}
