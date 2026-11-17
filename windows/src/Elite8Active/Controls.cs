using System.ComponentModel;
using System.Globalization;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Data;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace Elite8Active.Tray;

public abstract class Observable : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    protected void Raise([CallerMemberName] string? name = null) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));

    /// <summary>Everything may have changed: bindings read all properties again.</summary>
    public void RaiseAll() => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(string.Empty));
}

public sealed class RelayCommand(Action<object?> run) : ICommand
{
    public event EventHandler? CanExecuteChanged { add { } remove { } }

    public bool CanExecute(object? parameter) => true;

    public void Execute(object? parameter) => run(parameter);
}

/// <summary>
/// A half-circle battery gauge: full at 100 %, emptying from its left end towards the right
/// (like the Android app's). Green, orange from 30 %, red from 15 %.
/// </summary>
public sealed class ArcGauge : FrameworkElement
{
    public static readonly DependencyProperty PercentProperty = DependencyProperty.Register(
        nameof(Percent), typeof(int?), typeof(ArcGauge), new FrameworkPropertyMetadata(null, OnPercentChanged));

    private static readonly DependencyProperty LevelProperty = DependencyProperty.Register(
        "Level", typeof(double), typeof(ArcGauge), new FrameworkPropertyMetadata(0.0, FrameworkPropertyMetadataOptions.AffectsRender));

    public int? Percent
    {
        get => (int?)GetValue(PercentProperty);
        set => SetValue(PercentProperty, value);
    }

    private static void OnPercentChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        var gauge = (ArcGauge)d;
        var level = Math.Clamp((gauge.Percent ?? 0) / 100.0, 0, 1);
        if (gauge.IsVisible)
        {
            gauge.BeginAnimation(LevelProperty, new DoubleAnimation(level, TimeSpan.FromMilliseconds(350)) { EasingFunction = new CubicEase() });
        }
        else
        {
            gauge.BeginAnimation(LevelProperty, null);
            gauge.SetValue(LevelProperty, level);
        }
    }

    protected override void OnRender(DrawingContext dc)
    {
        const double stroke = 6;
        var radius = Math.Min(ActualWidth / 2, ActualHeight) - stroke / 2;
        if (radius <= 0) return;
        var centre = new Point(ActualWidth / 2, ActualHeight - stroke / 2);
        var level = (double)GetValue(LevelProperty);

        dc.DrawGeometry(null, Pen((Brush)FindResource("SurfaceHighest"), stroke), Arc(centre, radius, 0));
        if (level <= 0) return;
        var brush = Percent switch
        {
            null => (Brush)FindResource("Outline"),
            <= 15 => (Brush)FindResource("Bad"),
            <= 30 => (Brush)FindResource("Low"),
            _ => (Brush)FindResource("Good"),
        };
        dc.DrawGeometry(null, Pen(brush, stroke), Arc(centre, radius, 1 - level));
    }

    private static Pen Pen(Brush brush, double thickness) =>
        new(brush, thickness) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round };

    /// <summary>The half circle from <paramref name="from"/> (0 = its left end, 1 = its right end) to the right end.</summary>
    private static Geometry Arc(Point centre, double radius, double from)
    {
        var angle = Math.PI * (1 + from);
        var start = new Point(centre.X + radius * Math.Cos(angle), centre.Y + radius * Math.Sin(angle));
        var end = new Point(centre.X + radius, centre.Y);
        var geometry = new StreamGeometry();
        using (var g = geometry.Open())
        {
            g.BeginFigure(start, false, false);
            g.ArcTo(end, new Size(radius, radius), 0, false, SweepDirection.Clockwise, true, false);
        }
        geometry.Freeze();
        return geometry;
    }
}

/// <summary>
/// Makes a slider show a value from the view model but only send it back when the user lets go
/// (or clicks the track, or uses the keyboard), the way the Android app's sliders do. While the
/// thumb is being dragged, updates from the earbuds don't pull it away.
/// </summary>
public static class SliderCommit
{
    public static readonly DependencyProperty SourceProperty = DependencyProperty.RegisterAttached(
        "Source", typeof(double), typeof(SliderCommit), new PropertyMetadata(double.NaN, OnSourceChanged));

    public static readonly DependencyProperty CommandProperty = DependencyProperty.RegisterAttached(
        "Command", typeof(ICommand), typeof(SliderCommit), new PropertyMetadata(null, OnCommandChanged));

    private static readonly DependencyProperty ApplyingProperty = DependencyProperty.RegisterAttached(
        "Applying", typeof(bool), typeof(SliderCommit), new PropertyMetadata(false));

    public static double GetSource(DependencyObject d) => (double)d.GetValue(SourceProperty);
    public static void SetSource(DependencyObject d, double value) => d.SetValue(SourceProperty, value);
    public static ICommand? GetCommand(DependencyObject d) => (ICommand?)d.GetValue(CommandProperty);
    public static void SetCommand(DependencyObject d, ICommand? value) => d.SetValue(CommandProperty, value);

    private static bool Dragging(Slider slider) => slider.Template?.FindName("PART_Track", slider) is Track { Thumb.IsDragging: true };

    private static void OnSourceChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not Slider slider || double.IsNaN((double)e.NewValue) || Dragging(slider)) return;
        slider.SetValue(ApplyingProperty, true);
        slider.Value = (double)e.NewValue;
        slider.SetValue(ApplyingProperty, false);
    }

    private static void OnCommandChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not Slider slider || e.OldValue is not null) return;
        slider.ValueChanged += (_, _) =>
        {
            if (!(bool)slider.GetValue(ApplyingProperty) && !Dragging(slider)) Commit(slider);
        };
        slider.AddHandler(Thumb.DragCompletedEvent, new DragCompletedEventHandler((_, _) => Commit(slider)));
    }

    private static void Commit(Slider slider)
    {
        if (Math.Abs(slider.Value - GetSource(slider)) < 0.001) return;
        GetCommand(slider)?.Execute(slider.Value);
    }
}

/// <summary>Visible when the value equals the parameter (or, with no parameter, when it's true / not null).</summary>
public sealed class VisibleWhen : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var show = parameter is null
            ? value is bool b ? b : value is not null && !(value is string s && s.Length == 0)
            : string.Equals(System.Convert.ToString(value, CultureInfo.InvariantCulture), System.Convert.ToString(parameter, CultureInfo.InvariantCulture), StringComparison.Ordinal);
        return show ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => throw new NotSupportedException();
}

/// <summary>True when the value equals the parameter.</summary>
public sealed class IsEqual : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        string.Equals(System.Convert.ToString(value, CultureInfo.InvariantCulture), System.Convert.ToString(parameter, CultureInfo.InvariantCulture), StringComparison.Ordinal);

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => Binding.DoNothing;
}

/// <summary>An EQ gain in the earbuds' units (hundredths) as "+2.7".</summary>
public sealed class GainText : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        (System.Convert.ToDouble(value, CultureInfo.InvariantCulture) / 100).ToString("+0.0;-0.0;0.0", CultureInfo.InvariantCulture);

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => Binding.DoNothing;
}
