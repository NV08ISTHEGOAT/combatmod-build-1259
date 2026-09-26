using System.Windows;
using System.Windows.Controls;

namespace WaveClips.Controls
{
    /// <summary>Card with the WaveClient look: dark panel, cyan border, square corner accents, optional header.</summary>
    public class WavePanel : ContentControl
    {
        static WavePanel()
        {
            DefaultStyleKeyProperty.OverrideMetadata(typeof(WavePanel), new FrameworkPropertyMetadata(typeof(WavePanel)));
        }

        public static readonly DependencyProperty HeaderProperty =
            DependencyProperty.Register(nameof(Header), typeof(string), typeof(WavePanel), new PropertyMetadata(null));

        public static readonly DependencyProperty HeaderContentProperty =
            DependencyProperty.Register(nameof(HeaderContent), typeof(object), typeof(WavePanel), new PropertyMetadata(null));

        public string Header { get => (string)GetValue(HeaderProperty); set => SetValue(HeaderProperty, value); }
        public object HeaderContent { get => GetValue(HeaderContentProperty); set => SetValue(HeaderContentProperty, value); }
    }
}
