using System;
using System.Windows;
using System.Windows.Media;

namespace WaveClips.Controls
{
    /// <summary>Segmented audio level meter (dB scaled) with peak hold.</summary>
    public class LevelMeter : FrameworkElement
    {
        public static readonly DependencyProperty LevelProperty =
            DependencyProperty.Register(nameof(Level), typeof(double), typeof(LevelMeter),
                new FrameworkPropertyMetadata(0.0, FrameworkPropertyMetadataOptions.AffectsRender, OnLevel));

        public static readonly DependencyProperty ColorProperty =
            DependencyProperty.Register(nameof(Color), typeof(Brush), typeof(LevelMeter),
                new FrameworkPropertyMetadata(new SolidColorBrush(System.Windows.Media.Color.FromRgb(0, 0xE5, 0xFF)), FrameworkPropertyMetadataOptions.AffectsRender));

        /// <summary>Linear peak 0..1.</summary>
        public double Level { get => (double)GetValue(LevelProperty); set => SetValue(LevelProperty, value); }
        public Brush Color { get => (Brush)GetValue(ColorProperty); set => SetValue(ColorProperty, value); }

        private double _shown, _peak;
        private DateTime _peakAt;

        private static readonly Brush Track = Freeze(new SolidColorBrush(System.Windows.Media.Color.FromRgb(0x1A, 0x30, 0x40)));
        private static readonly Brush Hot = Freeze(new SolidColorBrush(System.Windows.Media.Color.FromRgb(0xFF, 0x40, 0x60)));
        private static Brush Freeze(Brush b) { b.Freeze(); return b; }

        private static void OnLevel(DependencyObject d, DependencyPropertyChangedEventArgs e)
        {
            var m = (LevelMeter)d;
            double db = 20 * Math.Log10(Math.Max(1e-5, (double)e.NewValue));
            double v = Math.Clamp((db + 60) / 60, 0, 1);
            m._shown = v > m._shown ? v : m._shown * 0.82 + v * 0.18;
            if (m._shown >= m._peak || (DateTime.Now - m._peakAt).TotalSeconds > 1.2) { m._peak = m._shown; m._peakAt = DateTime.Now; }
        }

        protected override void OnRender(DrawingContext dc)
        {
            double w = ActualWidth, h = ActualHeight;
            if (w < 4) return;
            const int segs = 28;
            double gap = 2, sw = (w - gap * (segs - 1)) / segs;
            int lit = (int)Math.Round(_shown * segs);
            int peakSeg = (int)Math.Round(_peak * segs) - 1;
            for (int i = 0; i < segs; i++)
            {
                var r = new Rect(i * (sw + gap), 0, sw, h);
                Brush b = i < lit || i == peakSeg ? (i >= segs - 3 ? Hot : Color) : Track;
                dc.DrawRectangle(b, null, r);
            }
        }
    }
}
