using System.Collections.Immutable;
using System.Runtime.InteropServices;
using Elite8Active.Bluetooth;
using Elite8Active.Protocol;
using Elite8Active.Store;

namespace Elite8Active.Tests;

public class StoreTests : IDisposable
{
    private readonly string _folder = Path.Combine(Path.GetTempPath(), "Elite8ActiveTests", Guid.NewGuid().ToString("N"));

    public void Dispose()
    {
        if (Directory.Exists(_folder)) Directory.Delete(_folder, recursive: true);
    }

    [Fact]
    public void SettingsSurviveARestart()
    {
        var store = new SettingsStore(_folder);
        store.Update(s =>
        {
            s.HotkeysEnabled = false;
            s.Hotkeys[SoundMode.Off] = "Ctrl+Shift+F1";
        });
        store.SaveEq("Podcasts", [-270, 0, 118, 61, -120]);

        var again = new SettingsStore(_folder).Settings;
        Assert.False(again.HotkeysEnabled);
        Assert.True(again.LowBatteryAlert);
        Assert.Equal("Ctrl+Shift+F1", again.Hotkeys[SoundMode.Off]);
        Assert.Equal("Ctrl+Alt+Shift+F9", again.Hotkeys[SoundMode.Anc]);
        Assert.Equal(new[] { -270, 0, 118, 61, -120 }, Assert.Single(again.SavedEqs).Gains);
    }

    [Fact]
    public void SavingANameAgainReplacesItAndTheListIsCapped()
    {
        var store = new SettingsStore(_folder);
        store.SaveEq("Gym", EqPreset.BassBoost.Gains);
        store.SaveEq(" gym ", EqPreset.Energize.Gains);
        Assert.Equal(EqPreset.Energize.Gains, Assert.Single(store.Settings.SavedEqs).Gains);

        for (var i = 0; i < 20; i++) store.SaveEq($"Curve {i}", [i, 0, 0, 0, 0]);
        Assert.Equal(AppSettings.MaxSavedEqs, store.Settings.SavedEqs.Count);
        Assert.Equal("Curve 19", store.Settings.SavedEqs[0].Name);

        store.DeleteEq("Curve 19");
        Assert.Equal("Curve 18", store.Settings.SavedEqs[0].Name);
    }

    [Fact]
    public void ADamagedSettingsFileFallsBackToDefaults()
    {
        Directory.CreateDirectory(_folder);
        File.WriteAllText(Path.Combine(_folder, "settings.json"), "{ not json");
        Assert.True(new SettingsStore(_folder).Settings.HotkeysEnabled);
    }

    [Fact]
    public void CachesEverythingReadFromTheEarbuds()
    {
        var state = new EarbudsState
        {
            Status = ConnectionStatus.Connected,
            Name = "Jabra Elite 8 Active",
            Firmware = "4.6.0",
            Link = new EarbudLink(Linked: false, Primary: Side.Left),
            BatteryReport = new BatteryReport(75, null, 90),
            Mode = SoundMode.HearThrough,
            HearThroughLevel = 3,
            SidetoneLevel = -3,
            VoiceAssistant = 0x11,
            EqGains = EqPreset.Energize.Gains,
            Toggles = ImmutableDictionary<Toggle, bool?>.Empty.Add(Toggle.WearingDetection, true).Add(Toggle.SpatialSound, false).Add(Toggle.AutoMute, null),
            Choices = ImmutableDictionary<Choice, int?>.Empty.Add(Choice.SleepMode, 1),
        };

        var back = StateCache.Decode(StateCache.Encode(state))!;

        // The connection itself is never cached.
        Assert.Equal(ConnectionStatus.Away, back.Status);
        Assert.Equal(state.Name, back.Name);
        Assert.Equal(state.Firmware, back.Firmware);
        Assert.Equal(state.Link, back.Link);
        Assert.Equal(state.BatteryReport, back.BatteryReport);
        Assert.Equal(state.Mode, back.Mode);
        Assert.Equal(3, back.HearThroughLevel);
        Assert.Equal(-3, back.SidetoneLevel);
        Assert.Equal(0x11, back.VoiceAssistant);
        Assert.Same(EqPreset.Energize, back.EqPreset);
        Assert.True(back.WearingDetection);
        Assert.False(back.Toggles[Toggle.SpatialSound]);
        Assert.False(back.Toggles.ContainsKey(Toggle.AutoMute));
        Assert.Equal(1, back.Choices[Choice.SleepMode]);
    }

    [Fact]
    public void ACacheWithoutANameIsIgnored()
    {
        Assert.Null(StateCache.Decode(StateCache.Encode(new EarbudsState())));
        Assert.Null(StateCache.Decode("not json"));
    }
}

public class HotkeyTests
{
    [Theory]
    [InlineData("Ctrl+Alt+F9", HotkeyModifiers.Ctrl | HotkeyModifiers.Alt, 0x78)]
    [InlineData("ctrl + shift + a", HotkeyModifiers.Ctrl | HotkeyModifiers.Shift, 0x41)]
    [InlineData("Win+Alt+Num3", HotkeyModifiers.Win | HotkeyModifiers.Alt, 0x63)]
    [InlineData("Alt+PageDown", HotkeyModifiers.Alt, 0x22)]
    public void ParsesAndWritesShortcuts(string text, HotkeyModifiers modifiers, int key)
    {
        var hotkey = Hotkey.Parse(text)!;
        Assert.Equal(new Hotkey(modifiers, key), hotkey);
        Assert.Equal(hotkey, Hotkey.Parse(hotkey.ToString()));
        Assert.True(hotkey.IsUsable);
    }

    [Fact]
    public void WritesModifiersInAFixedOrder() =>
        Assert.Equal("Ctrl+Alt+Shift+F12", new Hotkey(HotkeyModifiers.Shift | HotkeyModifiers.Alt | HotkeyModifiers.Ctrl, 0x7B).ToString());

    [Theory]
    [InlineData("")]
    [InlineData("Ctrl+Alt")]
    [InlineData("Ctrl+F9+F10")]
    [InlineData("Ctrl+Banana")]
    [InlineData("2+F9")]
    public void RejectsWhatIsNotAShortcut(string text) => Assert.Null(Hotkey.Parse(text));

    [Fact]
    public void AShortcutNeedsCtrlAltOrWin()
    {
        Assert.False(Hotkey.Parse("Shift+A")!.IsUsable);
        Assert.False(Hotkey.Parse("F9")!.IsUsable);
    }
}

public class HostTests
{
    private const string Name = "Jabra Elite 8 Active";

    private static bool InUse(bool bluetooth, params AudioEndpoint[] outputs) => WindowsEarbudsHost.InUse(Name, outputs, () => bluetooth);

    [Fact]
    public void InUseWhileTheEarbudsAreAnActiveAudioOutput()
    {
        var speakers = new AudioEndpoint("Speakers (Realtek(R) Audio)", AudioEndpointState.Active);
        Assert.True(InUse(false, speakers, new AudioEndpoint($"Headphones ({Name})", AudioEndpointState.Active)));
        // Windows lists the outputs as unplugged once the earbuds disconnect, even if the app's own
        // channel is still keeping the Bluetooth link up.
        Assert.False(InUse(true, speakers, new AudioEndpoint($"Headphones ({Name})", AudioEndpointState.Unplugged),
            new AudioEndpoint($"Headset ({Name} Hands-Free)", AudioEndpointState.NotPresent)));
    }

    [Fact]
    public void FallsBackToTheBluetoothLinkWhenAudioCannotTell()
    {
        var speakers = new AudioEndpoint("Speakers (Realtek(R) Audio)", AudioEndpointState.Active);
        // No output under that name, or the outputs were disabled in Sound settings.
        Assert.True(InUse(true, speakers));
        Assert.False(InUse(false, speakers));
        Assert.True(InUse(true, new AudioEndpoint($"Headphones ({Name})", AudioEndpointState.Disabled)));
        Assert.False(InUse(false, new AudioEndpoint($"Headphones ({Name})", AudioEndpointState.Disabled)));
    }

    [Fact]
    public void BluetoothStructuresMatchWindows()
    {
        Assert.Equal(30, Marshal.SizeOf<RfcommChannel.SockAddrBth>());
        Assert.Equal(560, Marshal.SizeOf<BluetoothDevices.DeviceInfo>());
        Assert.Equal(40, Marshal.SizeOf<BluetoothDevices.SearchParams>());
    }

    [Fact]
    public void FormatsAddresses()
    {
        var device = new PairedDevice(0x0011223344AB, Name);
        Assert.Equal("00:11:22:33:44:AB", PairedDevice.FormatAddress(device.Address));
        Assert.Equal(device.Address, PairedDevice.ParseAddress("00:11:22:33:44:ab"));
        Assert.Equal("44:AB", device.ShortAddress);
    }
}

public class BatteryAlertsTests
{
    [Fact]
    public void WarnsAtTenPercentOnceUntilChargedPastTwenty()
    {
        var alerts = new BatteryAlerts([]);
        Assert.Empty(alerts.NewlyLow(new Battery(11, 50, 90)));
        Assert.Equal(new[] { ("Left earbud", 10) }, alerts.NewlyLow(new Battery(10, 50, 90)));
        Assert.Empty(alerts.NewlyLow(new Battery(5, 50, 90)));
        // Charging a little doesn't re-arm it; past 20 % does.
        Assert.Empty(alerts.NewlyLow(new Battery(15, 50, 90)));
        Assert.Empty(alerts.NewlyLow(new Battery(60, 50, 90)));
        Assert.Equal(new[] { ("Left earbud", 9), ("Case", 3) }, alerts.NewlyLow(new Battery(9, null, 3)));
    }

    [Fact]
    public void RemembersWhatWasWarnedAboutBeforeARestart()
    {
        var alerts = new BatteryAlerts(["Case"]);
        Assert.Empty(alerts.NewlyLow(new Battery(80, 80, 8)));
    }

    [Fact]
    public void WordsTheWarning()
    {
        Assert.Equal(("Earbuds battery low", "Left earbud 9% · Right earbud 7%. Put them in the case to charge."),
            BatteryAlerts.Message([("Left earbud", 9), ("Right earbud", 7)]));
    }
}
