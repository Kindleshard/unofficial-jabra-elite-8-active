using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;

namespace Elite8Active.Tray;

/// <summary>
/// The panel as a flyout: no title bar, opens in the corner by the notification area, and closes
/// when you click anywhere else or press Esc.
/// </summary>
public sealed class PanelWindow : Window
{
    private const double PanelHeight = 720;
    private const int Gap = 12;
    private DateTime _hiddenAt = DateTime.MinValue;

    public PanelWindow(PanelViewModel viewModel)
    {
        Title = PanelViewModel.AppName;
        Content = new PanelView { DataContext = viewModel };
        Width = 384;
        Height = PanelHeight;
        WindowStyle = WindowStyle.None;
        ResizeMode = ResizeMode.NoResize;
        ShowInTaskbar = false;
        Topmost = true;
        Background = (Brush)FindResource("Background");
        WindowStartupLocation = WindowStartupLocation.Manual;
        Deactivated += (_, _) => HidePanel();
        // On its way back up, so a shortcut being typed or the EQ name box gets Esc first.
        KeyDown += (_, e) =>
        {
            if (e.Key == Key.Escape) HidePanel();
        };
    }

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        var handle = new WindowInteropHelper(this).Handle;
        // Windows 11: rounded corners (which also brings the shadow) and a border in the theme's colour.
        var round = 2;
        DwmSetWindowAttribute(handle, 33, ref round, sizeof(int));
        var border = 0x00553D35; // OutlineVariant as 0x00BBGGRR
        DwmSetWindowAttribute(handle, 34, ref border, sizeof(int));
    }

    /// <summary>Opens if closed and closes if open, as a click on the tray icon should.</summary>
    public void Toggle()
    {
        if (IsVisible) HidePanel();
        // The click that should close the panel has already closed it, by taking the focus away.
        else if (DateTime.UtcNow - _hiddenAt > TimeSpan.FromMilliseconds(300)) ShowPanel();
    }

    /// <summary>Opens on the monitor the mouse is on (where the tray icon was clicked), or on the main one.</summary>
    public void ShowPanel(bool atCursor = true)
    {
        var handle = new WindowInteropHelper(this).EnsureHandle();
        var point = default(NativePoint);
        if (atCursor) GetCursorPos(out point);
        const uint nearest = 2, primary = 1;
        var monitor = MonitorFromPoint(point, atCursor ? nearest : primary);
        var info = new MonitorInfo { Size = Marshal.SizeOf<MonitorInfo>() };
        GetMonitorInfo(monitor, ref info);
        var scale = GetDpiForMonitor(monitor, 0, out var dpi, out _) == 0 ? dpi / 96.0 : 1;

        // Sized for this monitor: shorter if the screen is, and in the corner the taskbar leaves free.
        var work = info.Work;
        Height = Math.Min(PanelHeight, (work.Bottom - work.Top) / scale - 2 * Gap);
        var width = (int)Math.Round(Width * scale);
        var height = (int)Math.Round(Height * scale);
        var gap = (int)Math.Round(Gap * scale);
        var taskbarOnTop = work.Top > info.Monitor.Top;
        var x = work.Right - width - gap;
        var y = taskbarOnTop ? work.Top + gap : work.Bottom - height - gap;
        SetWindowPos(handle, IntPtr.Zero, x, y, width, height, 0x0010 | 0x0004);

        Show();
        Activate();
    }

    private void HidePanel()
    {
        if (!IsVisible) return;
        _hiddenAt = DateTime.UtcNow;
        Hide();
    }

    /// <summary>Closing just hides; the app ends from the tray menu.</summary>
    protected override void OnClosing(System.ComponentModel.CancelEventArgs e)
    {
        if (!AllowClose)
        {
            e.Cancel = true;
            HidePanel();
        }
        base.OnClosing(e);
    }

    public bool AllowClose { get; set; }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativePoint
    {
        public int X, Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeRect
    {
        public int Left, Top, Right, Bottom;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MonitorInfo
    {
        public int Size;
        public NativeRect Monitor;
        public NativeRect Work;
        public uint Flags;
    }

    [DllImport("user32.dll")]
    private static extern bool GetCursorPos(out NativePoint point);

    [DllImport("user32.dll")]
    private static extern IntPtr MonitorFromPoint(NativePoint point, uint flags);

    [DllImport("user32.dll")]
    private static extern bool GetMonitorInfo(IntPtr monitor, ref MonitorInfo info);

    [DllImport("shcore.dll")]
    private static extern int GetDpiForMonitor(IntPtr monitor, int type, out uint dpiX, out uint dpiY);

    [DllImport("user32.dll")]
    private static extern bool SetWindowPos(IntPtr window, IntPtr after, int x, int y, int width, int height, uint flags);

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr window, int attribute, ref int value, int size);
}
