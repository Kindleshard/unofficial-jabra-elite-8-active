using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Elite8Active.Protocol;
using Drawing = System.Drawing;
using Forms = System.Windows.Forms;

namespace Elite8Active.Tray;

/// <summary>Draws the app's icons from the earbud artwork, at whatever size is asked for.</summary>
public static class IconArt
{
    // The earbud's middle and width on the artwork's 108-unit grid.
    private static readonly Point ArtCentre = new(54.6, 52.7);
    private const double ArtWidth = 49.5;

    private static readonly Color Amber = Color.FromRgb(0xFF, 0xC8, 0x3D);
    private static readonly Color Navy = Color.FromRgb(0x1C, 0x25, 0x41);
    private static readonly Color Indigo = Color.FromRgb(0x4F, 0x60, 0xA6);
    private static readonly Color Grey = Color.FromRgb(0x8A, 0x93, 0xA8);
    private static readonly Color Red = Color.FromRgb(0xFF, 0x5A, 0x4F);

    /// <summary>The notification-area icon: the earbud alone, amber while connected, with a red dot when the battery is low.</summary>
    public static BitmapSource Tray(int pixels, bool connected, bool lowBattery) => Render(pixels, dc =>
    {
        DrawBud(dc, pixels, connected ? Amber : Grey, fill: 0.94);
        if (!lowBattery) return;
        var r = pixels * 0.2;
        dc.DrawEllipse(new SolidColorBrush(Red), null, new Point(pixels - r, pixels - r), r, r);
    });

    /// <summary>The app's own icon: the navy earbud on the amber tile, like the Android launcher icon.</summary>
    public static BitmapSource App(int pixels) => Render(pixels, dc =>
    {
        var tile = new Rect(0, 0, pixels, pixels);
        var radius = pixels * 0.23;
        var amber = new LinearGradientBrush(
            [new GradientStop(Color.FromRgb(0xFF, 0xC1, 0x5A), 0), new GradientStop(Color.FromRgb(0xFF, 0x8A, 0x3D), 0.55), new GradientStop(Color.FromRgb(0xE8, 0x51, 0x3A), 1)],
            new Point(0, 0), new Point(1, 1));
        dc.DrawRoundedRectangle(amber, null, tile, radius, radius);
        var glow = new RadialGradientBrush(Color.FromArgb(0x99, 0xFF, 0xF1, 0xC9), Color.FromArgb(0, 0xFF, 0xF1, 0xC9))
        {
            Center = new Point(0.24, 0.19),
            GradientOrigin = new Point(0.24, 0.19),
            RadiusX = 0.65,
            RadiusY = 0.65,
        };
        dc.DrawRoundedRectangle(glow, null, tile, radius, radius);
        DrawBud(dc, pixels, Navy, fill: 0.66, gel: Indigo);
    });

    /// <summary>
    /// The earbud centred in a square of <paramref name="pixels"/>, <paramref name="fill"/> of its width wide.
    /// The ear gel is a lighter tint of the body unless <paramref name="gel"/> names its colour.
    /// </summary>
    private static void DrawBud(DrawingContext dc, double pixels, Color color, double fill, Color? gel = null)
    {
        var scale = pixels * fill / ArtWidth;
        dc.PushTransform(new TranslateTransform(pixels / 2, pixels / 2));
        dc.PushTransform(new ScaleTransform(scale, scale));
        dc.PushTransform(new TranslateTransform(-ArtCentre.X, -ArtCentre.Y));
        dc.DrawGeometry(new SolidColorBrush(gel ?? Color.FromArgb(0x99, color.R, color.G, color.B)), null, Art.IconGel);
        dc.DrawGeometry(new SolidColorBrush(color), null, Art.IconBody);
        dc.Pop();
        dc.Pop();
        dc.Pop();
    }

    private static BitmapSource Render(int pixels, Action<DrawingContext> draw)
    {
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen()) draw(dc);
        var bitmap = new RenderTargetBitmap(pixels, pixels, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        bitmap.Freeze();
        return bitmap;
    }

    public static byte[] Png(BitmapSource image)
    {
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(image));
        using var stream = new MemoryStream();
        encoder.Save(stream);
        return stream.ToArray();
    }

    /// <summary>An .ico file holding the app icon at the sizes Windows asks for (PNG-compressed entries).</summary>
    public static byte[] AppIconFile()
    {
        int[] sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256];
        var images = sizes.Select(size => Png(App(size))).ToArray();
        using var stream = new MemoryStream();
        using var writer = new BinaryWriter(stream);
        writer.Write((ushort)0);
        writer.Write((ushort)1);
        writer.Write((ushort)sizes.Length);
        var offset = 6 + 16 * sizes.Length;
        for (var i = 0; i < sizes.Length; i++)
        {
            writer.Write((byte)(sizes[i] == 256 ? 0 : sizes[i]));
            writer.Write((byte)(sizes[i] == 256 ? 0 : sizes[i]));
            writer.Write((byte)0);
            writer.Write((byte)0);
            writer.Write((ushort)1);
            writer.Write((ushort)32);
            writer.Write(images[i].Length);
            writer.Write(offset);
            offset += images[i].Length;
        }
        foreach (var image in images) writer.Write(image);
        writer.Flush();
        return stream.ToArray();
    }
}

/// <summary>The icon in the notification area: status at a glance, a click for the panel, a menu on right-click.</summary>
public sealed class TrayIcon : IDisposable
{
    private readonly Forms.NotifyIcon _icon = new();
    private (bool Connected, bool Low)? _drawn;
    private IntPtr _handle;

    public TrayIcon()
    {
        _icon.MouseClick += (_, e) =>
        {
            if (e.Button == Forms.MouseButtons.Left) Clicked?.Invoke();
            else if (e.Button == Forms.MouseButtons.Right) MenuRequested?.Invoke();
        };
        _icon.BalloonTipClicked += (_, _) => Clicked?.Invoke();
        Update(new EarbudsState());
        _icon.Visible = true;
    }

    public event Action? Clicked;
    public event Action? MenuRequested;

    public void Update(EarbudsState state)
    {
        var battery = state.Connected ? state.Battery : null;
        _icon.Text = Tooltip(state);
        var look = (state.Connected, Low: new[] { battery?.Left, battery?.Right, battery?.Case }.Any(level => level <= BatteryAlerts.Low));
        if (look == _drawn) return;
        _drawn = look;

        var size = Forms.SystemInformation.SmallIconSize.Width;
        using var bitmap = new Drawing.Bitmap(new MemoryStream(IconArt.Png(IconArt.Tray(size, look.Connected, look.Low))));
        var handle = bitmap.GetHicon();
        _icon.Icon = Drawing.Icon.FromHandle(handle);
        if (_handle != IntPtr.Zero) DestroyIcon(_handle);
        _handle = handle;
    }

    public static string Tooltip(EarbudsState state)
    {
        var name = state.Name ?? PanelViewModel.AppName;
        if (!state.Connected)
        {
            return name + "\n" + (state.Status == ConnectionStatus.Connecting ? "Connecting…" : "Not connected to this PC");
        }
        var battery = state.Battery;
        string Level(string part, int? level, Side? side) =>
            side is { } s && state.IsOffline(s) ? $"{part} offline" : level is { } l ? $"{part} {l}%" : $"{part} –";
        var levels = string.Join(" · ", Level("L", battery?.Left, Side.Left), Level("R", battery?.Right, Side.Right), Level("Case", battery?.Case, null));
        return $"{name}\n{levels}" + (state.Mode is { } mode ? $"\nSound mode: {mode.Label()}" : "");
    }

    public void Notify(string title, string text) => _icon.ShowBalloonTip(8000, title, text, Forms.ToolTipIcon.None);

    public void Dispose()
    {
        _icon.Visible = false;
        _icon.Dispose();
        if (_handle != IntPtr.Zero) DestroyIcon(_handle);
    }

    [DllImport("user32.dll")]
    private static extern bool DestroyIcon(IntPtr icon);
}
