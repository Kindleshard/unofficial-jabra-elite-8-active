using System.Collections.Immutable;
using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Elite8Active.Bluetooth;
using Elite8Active.Protocol;
using Elite8Active.Store;

namespace Elite8Active.Tray;

/// <summary>
/// Command-line switches for working on the app's look without clicking through it:
///   --render file.png [--state connected|offline|away|failed|unpaired] [--tab 0|1|2] [--height 640]
///   --render-icons file.png     the tray and app icons at their real sizes
///   --make-icon file.ico        writes Assets\app.ico from the artwork
///   --render-app-icon file.png [--size 512]   the app icon as a PNG (e.g. for F-Droid's listing)
/// The panel is drawn with made-up values; nothing talks to the earbuds.
/// </summary>
public static class DevTools
{
    public static bool Run(string[] args)
    {
        string? Value(string name) => Array.IndexOf(args, name) is >= 0 and var at && at + 1 < args.Length ? args[at + 1] : null;

        if (Value("--make-icon") is { } icon)
        {
            File.WriteAllBytes(icon, IconArt.AppIconFile());
            return true;
        }
        if (Value("--render-app-icon") is { } png)
        {
            File.WriteAllBytes(png, IconArt.Png(IconArt.App(int.TryParse(Value("--size"), out var size) ? size : 512)));
            return true;
        }
        if (Value("--render-icons") is { } sheet)
        {
            File.WriteAllBytes(sheet, IconArt.Png(IconSheet()));
            return true;
        }
        if (Value("--render") is { } file)
        {
            var height = double.TryParse(Value("--height"), out var h) ? h : 640;
            File.WriteAllBytes(file, IconArt.Png(Panel(Value("--state") ?? "connected", int.TryParse(Value("--tab"), out var tab) ? tab : 0, height)));
            return true;
        }
        return false;
    }

    private sealed class NoHost : IEarbudsHost
    {
        public bool BluetoothOn => true;
        public IReadOnlyList<PairedDevice> PairedDevices() => [];
        public bool IsInUse(PairedDevice device) => false;
        public IByteChannel Open(PairedDevice device) => throw new IOException("Not available.");
    }

    private sealed class NoShell : IShell
    {
        public bool StartsWithWindows { get; set; }
        public bool ShortcutUnavailable(SoundMode mode) => mode == SoundMode.Off;
        public void PauseShortcuts(bool paused) { }
        public void OpenBluetoothSettings() { }
        public void Quit() { }
    }

    private static EarbudsState DemoState(string kind)
    {
        var values = new EarbudsState
        {
            Status = ConnectionStatus.Connected,
            Name = "Jabra Elite 8 Active",
            Firmware = "4.6.0",
            Link = new EarbudLink(Linked: kind != "offline", Primary: Side.Right),
            BatteryReport = new BatteryReport(Primary: 85, Other: 25, Case: 10),
            Mode = SoundMode.HearThrough,
            HearThroughLevel = 3,
            SidetoneLevel = 3,
            VoiceAssistant = 0x11,
            EqGains = [180, 60, -118, -61, 270],
            Toggles = Toggle.All.ToImmutableDictionary(t => t, t => (bool?)(t != Toggle.AutoAnswer && t != Toggle.ReduceWind)),
            Choices = Choice.All.ToImmutableDictionary(c => c, c => (int?)0),
        };
        return kind switch
        {
            "away" => values with { Status = ConnectionStatus.Away },
            "failed" => values with { Status = ConnectionStatus.Failed, ConnectFailure = "Another program on this PC is already using the earbuds' control channel." },
            "unpaired" => new EarbudsState { Status = ConnectionStatus.NotPaired },
            _ => values,
        };
    }

    private static BitmapSource Panel(string state, int tab, double height)
    {
        var folder = Path.Combine(Path.GetTempPath(), "Elite8ActiveRender");
        if (Directory.Exists(folder)) Directory.Delete(folder, recursive: true);
        var settings = new SettingsStore(folder);
        settings.SaveEq("Podcasts", [-270, 0, 0, 61, -120]);
        settings.SaveEq("Late night", [180, 60, -118, -61, 270]);
        using var controller = new EarbudsController(new NoHost(), settings, DemoState(state));
        var view = new PanelView { DataContext = new PanelViewModel(controller, settings, new NoShell()) { Tab = tab }, Height = height };

        view.Measure(new Size(view.Width, height));
        view.Arrange(new Rect(0, 0, view.Width, height));
        // Bindings and templates settle at lower dispatcher priorities; let them run before drawing.
        Dispatcher.CurrentDispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);
        view.UpdateLayout();

        const int scale = 2;
        var bitmap = new RenderTargetBitmap((int)view.Width * scale, (int)height * scale, 96 * scale, 96 * scale, PixelFormats.Pbgra32);
        bitmap.Render(view);
        return bitmap;
    }

    /// <summary>The tray icon in each state on a dark and a light taskbar, and the app icon at a few sizes.</summary>
    private static BitmapSource IconSheet()
    {
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen())
        {
            dc.DrawRectangle(new SolidColorBrush(Color.FromRgb(0x20, 0x20, 0x20)), null, new Rect(0, 0, 420, 70));
            dc.DrawRectangle(new SolidColorBrush(Color.FromRgb(0xEE, 0xEE, 0xEE)), null, new Rect(0, 70, 420, 70));
            dc.DrawRectangle(new SolidColorBrush(Color.FromRgb(0x30, 0x34, 0x40)), null, new Rect(0, 140, 420, 280));
            for (var row = 0; row < 2; row++)
            {
                double x = 12;
                foreach (var size in new[] { 16, 20, 24, 32 })
                {
                    foreach (var (connected, low) in new[] { (true, false), (true, true), (false, false) })
                    {
                        dc.DrawImage(IconArt.Tray(size, connected, low), new Rect(x, 70 * row + (70 - size) / 2.0, size, size));
                        x += size + 8;
                    }
                    x += 14;
                }
            }
            double left = 12;
            foreach (var size in new[] { 256, 64, 48, 32, 24, 16 })
            {
                dc.DrawImage(IconArt.App(size), new Rect(left, 152, size, size));
                left += size + 12;
            }
        }
        var bitmap = new RenderTargetBitmap(420, 420, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        return bitmap;
    }
}
