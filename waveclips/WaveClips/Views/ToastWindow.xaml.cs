using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using WaveClips.Capture;
using WaveClips.Core;
using WaveClips.Interop;

namespace WaveClips.Views
{
    /// <summary>Small overlay in a corner of the recorded monitor. Never takes focus (so fullscreen games stay put).</summary>
    public partial class ToastWindow : Window
    {
        private readonly DispatcherTimer _hide = new DispatcherTimer { Interval = TimeSpan.FromSeconds(3.2) };

        [DllImport("user32.dll")]
        private static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);

        public ToastWindow()
        {
            InitializeComponent();
            _hide.Tick += (_, __) => { _hide.Stop(); AnimateOut(); };
            SourceInitialized += (_, __) =>
            {
                var h = new WindowInteropHelper(this).Handle;
                long ex = Native.GetWindowLongPtr(h, Native.GWL_EXSTYLE).ToInt64();
                ex |= Native.WS_EX_NOACTIVATE | Native.WS_EX_TOOLWINDOW | Native.WS_EX_TRANSPARENT;
                Native.SetWindowLongPtr(h, Native.GWL_EXSTYLE, new IntPtr(ex));
            };
        }

        public void Present(NotifyKind kind, string title, string message, MonitorInfo monitor, OverlayCorner corner)
        {
            TitleText.Text = title;
            MessageText.Text = message ?? "";
            MessageText.Visibility = string.IsNullOrEmpty(message) ? Visibility.Collapsed : Visibility.Visible;
            var (icon, brushKey) = kind switch
            {
                NotifyKind.Clip => ("", "Accent"),
                NotifyKind.Recording => ("", "Danger"),
                NotifyKind.Error => ("", "Danger"),
                _ => ("", "Accent"),
            };
            var brush = (Brush)FindResource(brushKey);
            IconText.Text = icon;
            IconText.Foreground = brush;
            IconBg.BorderBrush = brush;
            Card.BorderBrush = brush;
            TitleText.Foreground = brush;

            if (!IsVisible) Show();
            UpdateLayout();
            Position(monitor, corner);

            _hide.Stop();
            _hide.Interval = TimeSpan.FromSeconds(kind == NotifyKind.Error ? 5 : 3.2);
            AnimateIn();
            _hide.Start();
        }

        private void Position(MonitorInfo m, OverlayCorner corner)
        {
            if (m == null) return;
            var h = new WindowInteropHelper(this).Handle;
            var dpi = VisualTreeHelper.GetDpi(this);
            int w = (int)(ActualWidth * dpi.DpiScaleX), ht = (int)(ActualHeight * dpi.DpiScaleY);
            int margin = 12;
            int x = corner is OverlayCorner.TopLeft or OverlayCorner.BottomLeft ? m.X + margin : m.X + m.Width - w - margin;
            int y = corner is OverlayCorner.TopLeft or OverlayCorner.TopRight ? m.Y + margin + 40 : m.Y + m.Height - ht - margin - 60;
            const uint SWP_NOSIZE = 0x1, SWP_NOACTIVATE = 0x10;
            SetWindowPos(h, new IntPtr(-1) /*HWND_TOPMOST*/, x, y, 0, 0, SWP_NOSIZE | SWP_NOACTIVATE);
        }

        private void AnimateIn()
        {
            var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
            Root.BeginAnimation(OpacityProperty, new DoubleAnimation(1, TimeSpan.FromMilliseconds(180)));
            Slide.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(40, 0, TimeSpan.FromMilliseconds(260)) { EasingFunction = ease });
            Life.BeginAnimation(WidthProperty, new DoubleAnimation(Card.ActualWidth, 0, _hide.Interval));
        }

        private void AnimateOut()
        {
            var a = new DoubleAnimation(0, TimeSpan.FromMilliseconds(250));
            a.Completed += (_, __) => { if (Root.Opacity < 0.05) Hide(); };
            Root.BeginAnimation(OpacityProperty, a);
            Slide.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(0, 40, TimeSpan.FromMilliseconds(250)));
        }
    }
}
