using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;

namespace Elite8Active.Tray;

/// <summary>
/// A short confirmation near the bottom of the screen ("ANC on") after a keyboard shortcut. It
/// never takes the focus and clicks pass through it.
/// </summary>
public sealed class Osd : Window
{
    private readonly TextBlock _text = new() { FontSize = 15, FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center };
    private readonly Border _dot = new() { Width = 10, Height = 10, CornerRadius = new CornerRadius(5), Margin = new Thickness(0, 1, 10, 0), VerticalAlignment = VerticalAlignment.Center };
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromMilliseconds(1300) };

    public Osd()
    {
        WindowStyle = WindowStyle.None;
        AllowsTransparency = true;
        Background = Brushes.Transparent;
        ShowInTaskbar = false;
        ShowActivated = false;
        Topmost = true;
        Focusable = false;
        IsHitTestVisible = false;
        SizeToContent = SizeToContent.WidthAndHeight;
        WindowStartupLocation = WindowStartupLocation.Manual;
        FontFamily = (FontFamily)FindResource("Text");
        Foreground = (Brush)FindResource("OnSurface");
        Content = new Border
        {
            Background = (Brush)FindResource("SurfaceHigh"),
            BorderBrush = (Brush)FindResource("OutlineVariant"),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(16),
            Padding = new Thickness(18, 11, 20, 12),
            Margin = new Thickness(8),
            Child = new StackPanel { Orientation = Orientation.Horizontal, Children = { _dot, _text } },
        };
        _timer.Tick += (_, _) =>
        {
            _timer.Stop();
            var fade = new DoubleAnimation(0, TimeSpan.FromMilliseconds(250));
            fade.Completed += (_, _) =>
            {
                if (!_timer.IsEnabled) Hide();
            };
            BeginAnimation(OpacityProperty, fade);
        };
    }

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        var handle = new WindowInteropHelper(this).Handle;
        const int exStyle = -20, noActivate = 0x08000000, transparent = 0x20, toolWindow = 0x80;
        SetWindowLong(handle, exStyle, GetWindowLong(handle, exStyle) | noActivate | transparent | toolWindow);
    }

    public void Show(string text, bool ok)
    {
        _text.Text = text;
        _dot.Background = (Brush)FindResource(ok ? "Primary" : "Bad");
        BeginAnimation(OpacityProperty, null);
        Opacity = 1;
        Show();
        UpdateLayout();
        var work = SystemParameters.WorkArea;
        Left = work.Left + (work.Width - ActualWidth) / 2;
        Top = work.Bottom - ActualHeight - 64;
        _timer.Stop();
        _timer.Start();
    }

    [DllImport("user32.dll")]
    private static extern int GetWindowLong(IntPtr window, int index);

    [DllImport("user32.dll")]
    private static extern int SetWindowLong(IntPtr window, int index, int value);
}
