using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;

namespace WaveClips.Views
{
    /// <summary>Tiny themed text prompt.</summary>
    public static class InputDialog
    {
        public static string Ask(Window owner, string title, string prompt, string initial)
        {
            var box = new TextBox { Text = initial, Margin = new Thickness(0, 8, 0, 14), MinWidth = 360 };
            var ok = new Button { Content = "OK", IsDefault = true, Style = (Style)Application.Current.FindResource("AccentButton"), MinWidth = 90 };
            var cancel = new Button { Content = "Cancel", IsCancel = true, Margin = new Thickness(8, 0, 0, 0), MinWidth = 90 };
            var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
            buttons.Children.Add(ok);
            buttons.Children.Add(cancel);
            var panel = new StackPanel { Margin = new Thickness(20) };
            panel.Children.Add(new TextBlock { Text = prompt, Style = (Style)Application.Current.FindResource("H3") });
            panel.Children.Add(box);
            panel.Children.Add(buttons);
            var win = new Window
            {
                Title = title,
                Owner = owner,
                Content = new Border { Child = panel, BorderBrush = (System.Windows.Media.Brush)Application.Current.FindResource("Accent"), BorderThickness = new Thickness(1) },
                SizeToContent = SizeToContent.WidthAndHeight,
                WindowStartupLocation = WindowStartupLocation.CenterOwner,
                ResizeMode = ResizeMode.NoResize,
                WindowStyle = WindowStyle.ToolWindow,
                Background = (System.Windows.Media.Brush)Application.Current.FindResource("Bg1"),
            };
            ok.Click += (_, __) => win.DialogResult = true;
            win.Loaded += (_, __) => { box.Focus(); box.SelectAll(); Keyboard.Focus(box); };
            return win.ShowDialog() == true ? box.Text.Trim() : null;
        }
    }
}
