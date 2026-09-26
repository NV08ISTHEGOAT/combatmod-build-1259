using System;
using System.Windows;
using System.Windows.Media;

namespace WaveClips.Controls
{
    /// <summary>Slowly flowing sine waves - the "Wave" in WaveClient. Only animates while visible.</summary>
    public class WaveBackground : FrameworkElement
    {
        private double _phase;
        private TimeSpan _last;
        private bool _hooked;

        public static readonly DependencyProperty IntensityProperty =
            DependencyProperty.Register(nameof(Intensity), typeof(double), typeof(WaveBackground),
                new FrameworkPropertyMetadata(1.0, FrameworkPropertyMetadataOptions.AffectsRender));

        public static readonly DependencyProperty SpeedProperty =
            DependencyProperty.Register(nameof(Speed), typeof(double), typeof(WaveBackground), new PropertyMetadata(1.0));

        /// <summary>0..1 opacity multiplier (e.g. pump it while recording).</summary>
        public double Intensity { get => (double)GetValue(IntensityProperty); set => SetValue(IntensityProperty, value); }
        public double Speed { get => (double)GetValue(SpeedProperty); set => SetValue(SpeedProperty, value); }

        public WaveBackground()
        {
            IsHitTestVisible = false;
            IsVisibleChanged += (_, __) => UpdateHook();
            Loaded += (_, __) => UpdateHook();
            Unloaded += (_, __) => { if (_hooked) { CompositionTarget.Rendering -= OnRendering; _hooked = false; } };
        }

        private void UpdateHook()
        {
            bool want = IsVisible && IsLoaded;
            if (want && !_hooked) { CompositionTarget.Rendering += OnRendering; _hooked = true; }
            else if (!want && _hooked) { CompositionTarget.Rendering -= OnRendering; _hooked = false; }
        }

        private void OnRendering(object sender, EventArgs e)
        {
            var t = ((RenderingEventArgs)e).RenderingTime;
            if (t == _last) return;
            double dt = _last == TimeSpan.Zero ? 0 : (t - _last).TotalSeconds;
            _last = t;
            _phase += dt * 0.6 * Speed;
            InvalidateVisual();
        }

        private static readonly Color Cyan = Color.FromRgb(0x00, 0xE5, 0xFF);

        protected override void OnRender(DrawingContext dc)
        {
            double w = ActualWidth, h = ActualHeight;
            if (w < 2 || h < 2) return;
            dc.DrawRectangle(Brushes.Transparent, null, new Rect(0, 0, w, h));
            var layers = new[]
            {
                (amp: 0.18, freq: 1.3, speed: 1.0, y: 0.62, a: 0.22, thick: 2.0),
                (amp: 0.12, freq: 2.1, speed: -1.4, y: 0.68, a: 0.14, thick: 1.5),
                (amp: 0.22, freq: 0.8, speed: 0.7, y: 0.74, a: 0.10, thick: 3.0),
            };
            foreach (var l in layers)
            {
                var geo = new StreamGeometry();
                using (var g = geo.Open())
                {
                    int steps = Math.Max(24, (int)(w / 8));
                    for (int i = 0; i <= steps; i++)
                    {
                        double x = w * i / steps;
                        double u = x / w;
                        double y = h * l.y + h * l.amp * Math.Sin(u * Math.PI * 2 * l.freq + _phase * l.speed)
                                             * (0.6 + 0.4 * Math.Sin(u * Math.PI));
                        if (i == 0) g.BeginFigure(new Point(x, y), false, false);
                        else g.LineTo(new Point(x, y), true, true);
                    }
                }
                geo.Freeze();
                var pen = new Pen(new SolidColorBrush(Color.FromArgb((byte)(255 * l.a * Intensity), Cyan.R, Cyan.G, Cyan.B)), l.thick);
                pen.Freeze();
                dc.DrawGeometry(null, pen, geo);
            }
        }
    }
}
