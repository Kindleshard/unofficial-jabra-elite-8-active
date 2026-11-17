using Elite8Active.Bluetooth;
using Elite8Active.Protocol;
using Elite8Active.Store;
using Xunit.Abstractions;

namespace Elite8Active.Tests;

/// <summary>Runs only with ELITE_LIVE=1, against real earbuds connected to this PC.</summary>
public sealed class LiveFactAttribute : FactAttribute
{
    public LiveFactAttribute()
    {
        if (Environment.GetEnvironmentVariable("ELITE_LIVE") != "1") Skip = "Set ELITE_LIVE=1 with the earbuds connected to this PC.";
    }
}

/// <summary>
/// The real stack end to end: Windows' device list, the RFCOMM socket and the earbuds themselves.
/// Only sound settings are changed, and each is put back. Close the tray app first: one program
/// per PC can hold the control channel.
/// </summary>
public class LiveTests(ITestOutputHelper output) : IDisposable
{
    private readonly string _folder = Path.Combine(Path.GetTempPath(), "Elite8ActiveTests", Guid.NewGuid().ToString("N"));

    public void Dispose()
    {
        if (Directory.Exists(_folder)) Directory.Delete(_folder, recursive: true);
    }

    private async Task<EarbudsController> ConnectAsync()
    {
        var controller = new EarbudsController(new WindowsEarbudsHost(), new SettingsStore(_folder), log: output.WriteLine);
        await controller.TickAsync();
        Assert.True(controller.State.Status == ConnectionStatus.Connected, $"{controller.State.Status}: {controller.State.ConnectFailure}");
        for (var waited = 0; waited < 15000 && controller.State is not { Pending: 0, VoiceAssistant: not null }; waited += 50) await Task.Delay(50);
        Assert.Null(controller.State.Error);
        return controller;
    }

    [LiveFact]
    public void WindowsListsTheEarbudsAsPairedAndInUse()
    {
        var host = new WindowsEarbudsHost();
        Assert.True(host.BluetoothOn);
        var earbuds = host.PairedDevices().Where(d => d.Name.Contains("Jabra", StringComparison.OrdinalIgnoreCase)).ToList();
        foreach (var device in earbuds) output.WriteLine($"{device.Name} ..{device.ShortAddress} in use: {host.IsInUse(device)}");
        foreach (var endpoint in AudioEndpoints.Outputs()) output.WriteLine($"output: {endpoint.Name} ({endpoint.State})");
        Assert.Contains(earbuds, host.IsInUse);
    }

    [LiveFact]
    public async Task ReadsEverything()
    {
        using var controller = await ConnectAsync();
        var s = controller.State;
        output.WriteLine($"{s.Name}, firmware {s.Firmware}");
        output.WriteLine($"link: {s.Link}; battery: {s.Battery}");
        output.WriteLine($"mode: {s.Mode}, HearThrough level {s.HearThroughLevel}, EQ {s.EqPreset?.Label ?? string.Join(",", s.EqGains ?? [])}");
        output.WriteLine("toggles: " + string.Join(", ", Toggle.All.Select(t => $"{t.Key}={s.Toggles.GetValueOrDefault(t)}")));
        output.WriteLine("choices: " + string.Join(", ", Choice.All.Select(c => $"{c.Key}={(s.Choices.GetValueOrDefault(c) is { } i ? c.Options[i].Label : "?")}")));
        output.WriteLine($"sidetone level {s.SidetoneLevel}, voice assistant 0x{s.VoiceAssistant:x2}");

        Assert.False(string.IsNullOrEmpty(s.Name));
        Assert.Matches(@"^\d+\.\d+\.\d+$", s.Firmware);
        Assert.NotNull(s.Link);
        Assert.NotNull(s.Battery);
        Assert.NotNull(s.Mode);
        Assert.Equal(5, s.EqGains?.Count);
        Assert.All(Toggle.All, t => Assert.NotNull(s.Toggles.GetValueOrDefault(t)));
        Assert.All(Choice.All, c => Assert.NotNull(s.Choices.GetValueOrDefault(c)));
    }

    [LiveFact]
    public async Task ChangesTheModeAndEqAndPutsThemBack()
    {
        using var controller = await ConnectAsync();
        var mode = controller.State.Mode!.Value;
        var gains = controller.State.EqGains!;
        try
        {
            var other = mode == SoundMode.Off ? SoundMode.HearThrough : SoundMode.Off;
            Assert.True(await controller.SetModeAsync(other), controller.State.Error);
            Assert.Equal(other, controller.State.Mode);

            var preset = gains.SequenceEqual(EqPreset.Smooth.Gains) ? EqPreset.Speech : EqPreset.Smooth;
            Assert.True(await controller.SetEqGainsAsync(preset.Gains), controller.State.Error);
            Assert.Same(preset, controller.State.EqPreset);
        }
        finally
        {
            Assert.True(await controller.SetModeAsync(mode), controller.State.Error);
            Assert.True(await controller.SetEqGainsAsync(gains), controller.State.Error);
        }
        Assert.Equal(mode, controller.State.Mode);
        Assert.Equal(gains, controller.State.EqGains);
        output.WriteLine($"restored: {mode}, EQ {string.Join(",", gains)}");
    }
}
