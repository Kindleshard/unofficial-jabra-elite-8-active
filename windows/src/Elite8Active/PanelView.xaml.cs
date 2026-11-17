using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace Elite8Active.Tray;

/// <summary>The panel's contents: the earbuds' card on top, three tabs underneath.</summary>
public partial class PanelView : UserControl
{
    private ConnectionStatus? _lastStatus;

    public PanelView()
    {
        InitializeComponent();
        DataContextChanged += (_, e) =>
        {
            if (e.OldValue is PanelViewModel old) old.PropertyChanged -= OnViewModelChanged;
            if (e.NewValue is PanelViewModel vm)
            {
                _lastStatus = vm.State.Status;
                vm.PropertyChanged += OnViewModelChanged;
            }
        };
    }

    private PanelViewModel ViewModel => (PanelViewModel)DataContext;

    private void OnViewModelChanged(object? sender, PropertyChangedEventArgs e)
    {
        var status = ViewModel.State.Status;
        if (status == _lastStatus) return;
        // Green when the connection comes back, red when it's lost; quiet for everything else.
        if (status == ConnectionStatus.Connected) FlashCard("Good");
        else if (_lastStatus == ConnectionStatus.Connected) FlashCard("Bad");
        _lastStatus = status;
    }

    private void FlashCard(string brush)
    {
        if (!IsVisible) return;
        Flash.Background = (Brush)FindResource(brush);
        Flash.BeginAnimation(OpacityProperty, new DoubleAnimation(0.4, 0, TimeSpan.FromMilliseconds(1400)) { EasingFunction = new QuadraticEase() });
    }

    private void Tab_Click(object sender, RoutedEventArgs e) => ViewModel.Tab = int.Parse((string)((FrameworkElement)sender).Tag);

    /// <summary>Opens the options of a choice row as a menu under its button.</summary>
    private void Choice_Click(object sender, RoutedEventArgs e)
    {
        var button = (Button)sender;
        var row = (ChoiceRow)button.DataContext;
        var menu = new ContextMenu { PlacementTarget = button, Placement = PlacementMode.Bottom, HorizontalOffset = -4, VerticalOffset = 4 };
        for (var i = 0; i < row.Options.Count; i++)
        {
            var index = i;
            var item = new MenuItem { Header = row.Options[i], IsChecked = i == row.Selected };
            item.Click += (_, _) => row.Select(index);
            menu.Items.Add(item);
        }
        menu.IsOpen = true;
    }

    // A shortcut button waits for the next key press with Ctrl, Alt or Win held down.

    private void Hotkey_Click(object sender, RoutedEventArgs e)
    {
        var button = (Button)sender;
        var row = (HotkeyRow)button.DataContext;
        if (row.IsRecording) return;
        ViewModel.SetRecording(row, true);
        button.Focus();
    }

    private void Hotkey_KeyDown(object sender, KeyEventArgs e)
    {
        var row = (HotkeyRow)((Button)sender).DataContext;
        if (!row.IsRecording) return;
        e.Handled = true;
        var key = e.Key == Key.System ? e.SystemKey : e.Key;
        if (key is Key.LeftCtrl or Key.RightCtrl or Key.LeftAlt or Key.RightAlt or Key.LeftShift or Key.RightShift or Key.LWin or Key.RWin) return;
        if (key != Key.Escape)
        {
            var modifiers = HotkeyModifiers.None;
            if (Keyboard.Modifiers.HasFlag(ModifierKeys.Control)) modifiers |= HotkeyModifiers.Ctrl;
            if (Keyboard.Modifiers.HasFlag(ModifierKeys.Alt)) modifiers |= HotkeyModifiers.Alt;
            if (Keyboard.Modifiers.HasFlag(ModifierKeys.Shift)) modifiers |= HotkeyModifiers.Shift;
            if (Keyboard.Modifiers.HasFlag(ModifierKeys.Windows)) modifiers |= HotkeyModifiers.Win;
            // Without Ctrl, Alt or Win it would swallow an ordinary key everywhere: keep waiting.
            if (!ViewModel.SetHotkey(row, new Hotkey(modifiers, KeyInterop.VirtualKeyFromKey(key)))) return;
        }
        ViewModel.SetRecording(row, false);
    }

    private void Hotkey_LostFocus(object sender, KeyboardFocusChangedEventArgs e)
    {
        if (((Button)sender).DataContext is HotkeyRow { IsRecording: true } row) ViewModel.SetRecording(row, false);
    }

    private void EqName_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) ViewModel.ConfirmSaveEq.Execute(null);
        else if (e.Key == Key.Escape) ViewModel.CancelSaveEq.Execute(null);
        else return;
        e.Handled = true;
    }

    private void EqName_IsVisibleChanged(object sender, DependencyPropertyChangedEventArgs e)
    {
        if ((bool)e.NewValue) Dispatcher.BeginInvoke(() => EqName.Focus());
    }
}
