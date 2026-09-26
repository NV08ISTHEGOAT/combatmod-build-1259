using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using WaveClips.Core;

namespace WaveClips.Controls
{
    /// <summary>Draws the monitor layout like Windows display settings; click a monitor to record it.</summary>
    public class MonitorPicker : FrameworkElement
    {
        public static readonly DependencyProperty MonitorsProperty =
            DependencyProperty.Register(nameof(Monitors), typeof(IReadOnlyList<MonitorInfo>), typeof(MonitorPicker),
                new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.AffectsRender));

        public static readonly DependencyProperty SelectedDeviceProperty =
            DependencyProperty.Register(nameof(SelectedDevice), typeof(string), typeof(MonitorPicker),
                new FrameworkPropertyMetadata("", FrameworkPropertyMetadataOptions.AffectsRender | FrameworkPropertyMetadataOptions.BindsTwoWayByDefault));

        public IReadOnlyList<MonitorInfo> Monitors { get => (IReadOnlyList<MonitorInfo>)GetValue(MonitorsProperty); set => SetValue(MonitorsProperty, value); }
        public string SelectedDevice { get => (string)GetValue(SelectedDeviceProperty); set => SetValue(SelectedDeviceProperty, value); }

        private readonly List<(Rect rect, MonitorInfo mon)> _hit = new List<(Rect, MonitorInfo)>();
        private MonitorInfo _hover;

        private static readonly Color Cyan = Color.FromRgb(0x00, 0xE5, 0xFF);

        public MonitorPicker()
        {
            Cursor = Cursors.Hand;
            MinHeight = 170;
        }

        protected override void OnRender(DrawingContext dc)
        {
            _hit.Clear();
            dc.DrawRectangle(Brushes.Transparent, null, new Rect(0, 0, ActualWidth, ActualHeight));
            var mons = Monitors;
            if (mons == null || mons.Count == 0) return;
            double minX = mons.Min(m => m.X), minY = mons.Min(m => m.Y);
            double maxX = mons.Max(m => m.X + m.Width), maxY = mons.Max(m => m.Y + m.Height);
            double pad = 12;
            double scale = Math.Min((ActualWidth - pad * 2) / (maxX - minX), (ActualHeight - pad * 2) / (maxY - minY));
            double ox = (ActualWidth - (maxX - minX) * scale) / 2, oy = (ActualHeight - (maxY - minY) * scale) / 2;
            var selected = WaveClips.Core.Monitors.Find(mons, SelectedDevice);
            double dpi = VisualTreeHelper.GetDpi(this).PixelsPerDip;

            foreach (var m in mons)
            {
                var r = new Rect(ox + (m.X - minX) * scale + 3, oy + (m.Y - minY) * scale + 3, m.Width * scale - 6, m.Height * scale - 6);
                if (r.Width < 4 || r.Height < 4) continue;
                _hit.Add((r, m));
                bool sel = m == selected;
                bool hov = m == _hover;
                var fill = new SolidColorBrush(sel ? Color.FromRgb(0x00, 0x4D, 0x5A) : hov ? Color.FromRgb(0x0F, 0x2F, 0x3E) : Color.FromRgb(0x0F, 0x20, 0x30));
                var pen = new Pen(new SolidColorBrush(sel ? Cyan : Color.FromRgb(0x2A, 0x50, 0x60)), sel ? 2 : 1);
                dc.DrawRectangle(fill, pen, r);
                if (sel)
                {
                    var glow = new Pen(new SolidColorBrush(Color.FromArgb(60, Cyan.R, Cyan.G, Cyan.B)), 6);
                    dc.DrawRectangle(null, glow, r);
                    // corner accents
                    var acc = new SolidColorBrush(Cyan);
                    foreach (var p in new[] { r.TopLeft, new Point(r.Right - 5, r.Top), new Point(r.Left, r.Bottom - 5), new Point(r.Right - 5, r.Bottom - 5) })
                        dc.DrawRectangle(acc, null, new Rect(p, new Size(5, 5)));
                }
                var num = new FormattedText(m.Number.ToString(), CultureInfo.CurrentUICulture, FlowDirection.LeftToRight,
                    new Typeface("Bahnschrift SemiBold"), Math.Max(14, Math.Min(34, r.Height * 0.35)),
                    new SolidColorBrush(sel ? Cyan : Color.FromRgb(0x80, 0xDE, 0xEA)), dpi);
                dc.DrawText(num, new Point(r.Left + (r.Width - num.Width) / 2, r.Top + (r.Height - num.Height) / 2 - 8));
                var res = new FormattedText($"{m.Width}×{m.Height}  {m.RefreshRate}Hz", CultureInfo.CurrentUICulture, FlowDirection.LeftToRight,
                    new Typeface("Segoe UI"), 10, new SolidColorBrush(Color.FromRgb(0x4A, 0x70, 0x80)), dpi);
                if (res.Width < r.Width - 6)
                    dc.DrawText(res, new Point(r.Left + (r.Width - res.Width) / 2, r.Top + (r.Height + num.Height) / 2 - 4));
            }
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            var p = e.GetPosition(this);
            var h = _hit.FirstOrDefault(x => x.rect.Contains(p)).mon;
            if (h != _hover) { _hover = h; InvalidateVisual(); }
        }

        protected override void OnMouseLeave(MouseEventArgs e) { _hover = null; InvalidateVisual(); }

        protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
        {
            var p = e.GetPosition(this);
            var m = _hit.FirstOrDefault(x => x.rect.Contains(p)).mon;
            if (m != null) SelectedDevice = m.DeviceName;
        }
    }
}
