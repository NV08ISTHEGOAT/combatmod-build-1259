using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;

namespace WaveClips.Editor
{
    /// <summary>
    /// The editor timeline: ruler, video thumbnails with cut segments, text overlay lane and one waveform lane
    /// per audio track. Drag segment edges to trim, drag text bars to move/resize, click to seek.
    /// </summary>
    public sealed class TimelineControl : FrameworkElement
    {
        public const double HeaderW = 158, RulerH = 26, VideoH = 66, TextH = 24, AudioH = 46, Gap = 4;

        private EditProject _project;
        private EditorMedia _media;
        private double _position, _pps = 40, _scroll;

        public EditProject Project { get => _project; set { _project = value; InvalidateMeasure(); InvalidateVisual(); } }
        public EditorMedia Media { get => _media; set { _media = value; InvalidateVisual(); } }
        public double Position { get => _position; set { _position = value; InvalidateVisual(); } }
        public int SelectedSegment { get; private set; } = -1;
        public TextOverlay SelectedText { get; private set; }

        /// <summary>Seconds visible at the left edge of the lanes.</summary>
        public double Scroll { get => _scroll; set { _scroll = Math.Clamp(value, 0, MaxScroll); InvalidateVisual(); ViewChanged?.Invoke(); } }
        public double PixelsPerSecond { get => _pps; set { _pps = Math.Clamp(value, 2, 800); Scroll = _scroll; InvalidateVisual(); ViewChanged?.Invoke(); } }
        public double ViewportSeconds => Math.Max(0.1, (ActualWidth - HeaderW) / _pps);
        public double MaxScroll => Math.Max(0, (_project?.Duration ?? 0) - ViewportSeconds + 0.5);

        public event Action<double> SeekRequested;
        public event Action BeforeEdit;
        public event Action Edited;
        public event Action SelectionChanged;
        public event Action ViewChanged;
        public event Action<int, Point> SegmentContextMenu;

        // ------------------------------------------------------------ brushes
        private static SolidColorBrush B(uint argb)
        {
            var b = new SolidColorBrush(Color.FromArgb((byte)(argb >> 24), (byte)(argb >> 16), (byte)(argb >> 8), (byte)argb));
            b.Freeze();
            return b;
        }
        private static Pen P(Brush b, double w) { var p = new Pen(b, w); p.Freeze(); return p; }

        private static readonly Brush Bg = B(0xFF071B24), Lane = B(0xFF0B2530), HeaderBg = B(0xFF06161E), RulerBg = B(0xFF04121A);
        private static readonly Brush Cyan = B(0xFF00E5FF), Label = B(0xFF80DEEA), Dim = B(0xFF4A7080), Text = B(0xFFE0F7FA);
        private static readonly Brush RemovedFill = B(0xC0040C10), SelFill = B(0x2200E5FF), TextBar = B(0xFF004D5A), TextBarSel = B(0xFF007A8C);
        private static readonly Brush Danger = B(0xFFFF4060), Warn = B(0xFFFFC940), Badge = B(0xE0040C10);
        private static readonly Pen CyanPen = P(Cyan, 2), SepPen = P(B(0xFF004D60), 1), TickPen = P(B(0xFF2A5060), 1), HandlePen = P(B(0xFFE0F7FA), 2);
        private static readonly Pen HatchPen = P(B(0x55FF4060), 1), PlayheadGlow = P(B(0x5500E5FF), 7);
        private static readonly Typeface Face = new Typeface("Segoe UI"), Heading = new Typeface("Bahnschrift SemiBold");

        public TimelineControl()
        {
            Focusable = true;
            ClipToBounds = true;
        }

        private int AudioCount => _project?.Tracks.Count ?? 0;
        private double VideoY => RulerH + Gap;
        private double TextY => VideoY + VideoH + Gap;
        private double AudioY(int i) => TextY + TextH + Gap + i * (AudioH + Gap);
        private double X(double t) => HeaderW + (t - _scroll) * _pps;
        private double T(double x) => (x - HeaderW) / _pps + _scroll;

        protected override Size MeasureOverride(Size available)
        {
            double h = AudioY(AudioCount) + 6;
            return new Size(double.IsInfinity(available.Width) ? 800 : available.Width, h);
        }

        public void FitToView()
        {
            if (_project == null || ActualWidth <= HeaderW) return;
            _scroll = 0;
            PixelsPerSecond = (ActualWidth - HeaderW - 12) / _project.Duration;
        }

        public void EnsureVisible(double t)
        {
            if (t < _scroll || t > _scroll + ViewportSeconds * 0.95) Scroll = t - ViewportSeconds * 0.1;
        }

        // ------------------------------------------------------------ render
        protected override void OnRender(DrawingContext dc)
        {
            double w = ActualWidth, h = ActualHeight;
            dc.DrawRectangle(Bg, null, new Rect(0, 0, w, h));
            if (_project == null) return;
            double dpi = VisualTreeHelper.GetDpi(this).PixelsPerDip;
            double dur = _project.Duration;
            var lanes = new Rect(HeaderW, 0, Math.Max(0, w - HeaderW), h);

            // ---- ruler ----
            dc.DrawRectangle(RulerBg, null, new Rect(0, 0, w, RulerH));
            dc.PushClip(new RectangleGeometry(lanes));
            double step = NiceStep(70 / _pps);
            double first = Math.Floor(_scroll / step) * step;
            for (double t = first; t <= _scroll + ViewportSeconds + step; t += step)
            {
                double x = X(t);
                dc.DrawLine(TickPen, new Point(x, RulerH - 9), new Point(x, RulerH));
                for (int k = 1; k < 5; k++)
                {
                    double xm = X(t + step * k / 5);
                    dc.DrawLine(TickPen, new Point(xm, RulerH - 4), new Point(xm, RulerH));
                }
                if (t >= 0 && t <= dur + 0.001) DrawText(dc, Fmt(t, step < 1), x + 3, 4, 10, Dim, dpi);
            }

            // ---- video lane: thumbnails ----
            var vRect = new Rect(X(0), VideoY, dur * _pps, VideoH);
            dc.DrawRectangle(Lane, null, vRect);
            if (_media != null && _media.Thumbs.Count > 0)
            {
                double tStep = dur / _media.Thumbs.Count;
                double thumbW = VideoH * 16.0 / 9;
                // Draw one thumbnail roughly every thumbW pixels, picking the nearest frame in time.
                for (double x = Math.Max(X(0), HeaderW - thumbW); x < Math.Min(X(dur), w); x += thumbW)
                {
                    double t = T(x);
                    int idx = (int)Math.Clamp(Math.Floor(t / tStep), 0, _media.Thumbs.Count - 1);
                    double wClip = Math.Min(thumbW, X(dur) - x);
                    dc.PushClip(new RectangleGeometry(new Rect(x, VideoY, wClip, VideoH)));
                    dc.DrawImage(_media.Thumbs[idx].image, new Rect(x, VideoY, thumbW, VideoH));
                    dc.Pop();
                }
            }

            // ---- text lane ----
            dc.DrawRectangle(Lane, null, new Rect(X(0), TextY, dur * _pps, TextH));
            foreach (var tx in _project.Texts)
            {
                var r = new Rect(X(tx.Start), TextY + 2, Math.Max(4, (tx.End - tx.Start) * _pps), TextH - 4);
                dc.DrawRoundedRectangle(tx == SelectedText ? TextBarSel : TextBar, tx == SelectedText ? CyanPen : null, r, 3, 3);
                dc.PushClip(new RectangleGeometry(r));
                DrawText(dc, "T  " + tx.Text, r.X + 6, r.Y + 2, 11, Text, dpi);
                dc.Pop();
            }

            // ---- audio lanes ----
            for (int i = 0; i < AudioCount; i++)
            {
                var tr = _project.Tracks[i];
                double y = AudioY(i);
                dc.DrawRectangle(Lane, null, new Rect(X(0), y, dur * _pps, AudioH));
                bool audible = _project.AudibleTracks().Contains(tr);
                var color = BrushFrom(tr.Color, audible ? 1.0 : 0.25);
                var peaks = _media != null && i < _media.Peaks.Length ? _media.Peaks[i] : null;
                if (peaks != null && peaks.Length > 0)
                {
                    double mid = y + AudioH / 2;
                    double gain = Math.Min(2, tr.Volume);
                    var geo = new StreamGeometry();
                    using (var g = geo.Open())
                    {
                        double x0 = Math.Max(HeaderW, X(0)), x1 = Math.Min(w, X(dur));
                        for (double x = x0; x < x1; x += 2)
                        {
                            double ta = T(x) - tr.Offset, tb = T(x + 2) - tr.Offset;
                            int a = (int)Math.Floor(ta * EditorMedia.PeaksPerSecond), b = (int)Math.Ceiling(tb * EditorMedia.PeaksPerSecond);
                            float p = 0;
                            for (int k = Math.Max(0, a); k < Math.Min(peaks.Length, Math.Max(a + 1, b)); k++) if (peaks[k] > p) p = peaks[k];
                            double amp = Math.Min(1, Math.Sqrt(p) * gain) * (AudioH / 2 - 3);
                            if (amp < 0.5) continue;
                            g.BeginFigure(new Point(x, mid - amp), false, false);
                            g.LineTo(new Point(x, mid + amp), true, false);
                        }
                    }
                    geo.Freeze();
                    dc.DrawGeometry(null, new Pen(color, 1.6), geo);
                }
            }

            // ---- segments (on top of all lanes) ----
            double laneBottom = AudioY(AudioCount) - Gap;
            for (int i = 0; i < _project.Segments.Count; i++)
            {
                var s = _project.Segments[i];
                var r = new Rect(X(s.Start), VideoY, Math.Max(0, s.Length * _pps), laneBottom - VideoY);
                if (s.Removed)
                {
                    dc.DrawRectangle(RemovedFill, null, r);
                    dc.PushClip(new RectangleGeometry(r));
                    for (double x = r.Left - r.Height; x < r.Right; x += 12)
                        dc.DrawLine(HatchPen, new Point(x, r.Bottom), new Point(x + r.Height, r.Top));
                    dc.Pop();
                    if (r.Width > 60) DrawText(dc, "CUT", r.X + 6, VideoY + 4, 10, Danger, dpi, Heading);
                }
                else
                {
                    if (i == SelectedSegment) dc.DrawRectangle(SelFill, CyanPen, new Rect(r.X, VideoY, r.Width, VideoH));
                    if (Math.Abs(s.Speed - 1) > 0.001 && r.Width > 30)
                    {
                        var label = s.Speed < 1 ? $"{s.Speed:0.##}x  SLOW-MO" : $"{s.Speed:0.##}x";
                        var ft = MakeText(label, 10, Warn, dpi, Heading);
                        dc.DrawRectangle(Badge, null, new Rect(r.X + 4, VideoY + VideoH - 18, ft.Width + 8, 15));
                        dc.DrawText(ft, new Point(r.X + 8, VideoY + VideoH - 17));
                    }
                }
                if (i > 0 || s.Start > 0.001)
                    dc.DrawLine(HandlePen, new Point(r.X, VideoY), new Point(r.X, VideoY + VideoH));
            }
            // outer trim handles
            dc.DrawRectangle(Cyan, null, new Rect(X(0) - 1, VideoY, 4, VideoH));
            dc.DrawRectangle(Cyan, null, new Rect(X(dur) - 3, VideoY, 4, VideoH));

            // ---- playhead ----
            double px = X(_position);
            dc.DrawLine(PlayheadGlow, new Point(px, 0), new Point(px, h));
            dc.DrawLine(CyanPen, new Point(px, 0), new Point(px, h));
            var tri = new StreamGeometry();
            using (var g = tri.Open())
            {
                g.BeginFigure(new Point(px - 7, 0), true, true);
                g.LineTo(new Point(px + 7, 0), true, false);
                g.LineTo(new Point(px, 10), true, false);
            }
            tri.Freeze();
            dc.DrawGeometry(Cyan, null, tri);
            dc.Pop(); // lanes clip

            // ---- headers ----
            dc.DrawRectangle(HeaderBg, null, new Rect(0, 0, HeaderW, h));
            dc.DrawLine(SepPen, new Point(HeaderW - 0.5, 0), new Point(HeaderW - 0.5, h));
            DrawText(dc, Fmt(_position, true), 12, 5, 12, Cyan, dpi, Heading);
            DrawText(dc, "VIDEO", 14, VideoY + VideoH / 2 - 8, 12, Label, dpi, Heading);
            DrawText(dc, "TEXT", 14, TextY + 4, 11, Label, dpi, Heading);
            for (int i = 0; i < AudioCount; i++)
            {
                var tr = _project.Tracks[i];
                double y = AudioY(i);
                dc.DrawRectangle(BrushFrom(tr.Color, 1), null, new Rect(4, y + 4, 3, AudioH - 8));
                var name = tr.Name.Length > 14 ? tr.Name.Substring(0, 13) + "…" : tr.Name;
                DrawText(dc, name, 14, y + 4, 12, Text, dpi, Heading);
                DrawText(dc, $"{tr.Volume * 100:0}%", 14, y + 24, 10, Dim, dpi);
                DrawToggle(dc, MuteRectAt(i), "M", tr.Muted, Danger, dpi);
                DrawToggle(dc, SoloRectAt(i), "S", tr.Solo, Warn, dpi);
            }
        }

        private Rect MuteRectAt(int i) => new Rect(HeaderW - 52, AudioY(i) + (AudioH - 18) / 2, 20, 18);
        private Rect SoloRectAt(int i) => new Rect(HeaderW - 28, AudioY(i) + (AudioH - 18) / 2, 20, 18);

        private void DrawToggle(DrawingContext dc, Rect r, string label, bool on, Brush onBrush, double dpi)
        {
            dc.DrawRoundedRectangle(on ? onBrush : Lane, on ? null : SepPen, r, 3, 3);
            var ft = MakeText(label, 11, on ? Bg : Dim, dpi, Heading);
            dc.DrawText(ft, new Point(r.X + (r.Width - ft.Width) / 2, r.Y + (r.Height - ft.Height) / 2));
        }

        private void DrawText(DrawingContext dc, string s, double x, double y, double size, Brush b, double dpi, Typeface face = null)
            => dc.DrawText(MakeText(s, size, b, dpi, face), new Point(x, y));

        private static FormattedText MakeText(string s, double size, Brush b, double dpi, Typeface face = null)
            => new FormattedText(s, CultureInfo.CurrentUICulture, FlowDirection.LeftToRight, face ?? Face, size, b, dpi);

        private static readonly Dictionary<string, Brush> BrushCache = new Dictionary<string, Brush>();
        private static Brush BrushFrom(string hex, double opacity)
        {
            var key = hex + opacity;
            if (BrushCache.TryGetValue(key, out var b)) return b;
            Color c;
            try { c = (Color)ColorConverter.ConvertFromString(hex); } catch { c = Colors.Cyan; }
            c.A = (byte)(255 * opacity);
            var br = new SolidColorBrush(c);
            br.Freeze();
            BrushCache[key] = br;
            return br;
        }

        private static double NiceStep(double raw)
        {
            foreach (var s in new[] { 0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600 }) if (s >= raw) return s;
            return 1200;
        }

        public static string Fmt(double t, bool fraction)
        {
            if (t < 0) t = 0;
            var ts = TimeSpan.FromSeconds(t);
            var s = ts.TotalHours >= 1 ? ts.ToString(@"h\:mm\:ss") : ts.ToString(@"m\:ss");
            return fraction ? s + "." + (int)(ts.Milliseconds / 100) : s;
        }

        // ------------------------------------------------------------ interaction
        private enum Drag { None, Scrub, Boundary, TextMove, TextStart, TextEnd }
        private Drag _drag;
        private int _dragBoundary;
        private double _dragOffset;
        private bool _edited;

        protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e)
        {
            Focus();
            if (_project == null) return;
            var p = e.GetPosition(this);
            _edited = false;

            // header toggles
            for (int i = 0; i < AudioCount; i++)
            {
                if (MuteRectAt(i).Contains(p)) { Toggle(i, mute: true); e.Handled = true; return; }
                if (SoloRectAt(i).Contains(p)) { Toggle(i, mute: false); e.Handled = true; return; }
            }
            if (p.X < HeaderW) return;

            double t = Math.Clamp(T(p.X), 0, _project.Duration);
            if (p.Y < RulerH) { _drag = Drag.Scrub; SeekRequested?.Invoke(t); }
            else if (p.Y >= VideoY && p.Y < VideoY + VideoH)
            {
                int b = HitBoundary(p.X);
                if (b != int.MinValue) StartBoundaryDrag(b);
                else
                {
                    SelectedSegment = _project.Segments.IndexOf(_project.SegmentAt(t));
                    SelectedText = null;
                    SelectionChanged?.Invoke();
                    _drag = Drag.Scrub;
                    SeekRequested?.Invoke(t);
                }
            }
            else if (p.Y >= TextY && p.Y < TextY + TextH)
            {
                var hit = _project.Texts.LastOrDefault(x => p.X >= X(x.Start) - 4 && p.X <= X(x.End) + 4);
                SelectedText = hit;
                SelectionChanged?.Invoke();
                if (hit != null)
                {
                    BeforeEdit?.Invoke();
                    if (Math.Abs(p.X - X(hit.Start)) < 6) _drag = Drag.TextStart;
                    else if (Math.Abs(p.X - X(hit.End)) < 6) _drag = Drag.TextEnd;
                    else { _drag = Drag.TextMove; _dragOffset = t - hit.Start; }
                }
                else { _drag = Drag.Scrub; SeekRequested?.Invoke(t); }
            }
            else { _drag = Drag.Scrub; SeekRequested?.Invoke(t); }

            CaptureMouse();
            InvalidateVisual();
            e.Handled = true;
        }

        private void Toggle(int i, bool mute)
        {
            BeforeEdit?.Invoke();
            var tr = _project.Tracks[i];
            if (mute) tr.Muted = !tr.Muted; else tr.Solo = !tr.Solo;
            InvalidateVisual();
            Edited?.Invoke();
        }

        /// <summary>Boundary index under x: -1 = outer start, Count-1 = outer end, else between i and i+1.</summary>
        private int HitBoundary(double x)
        {
            const double tol = 6;
            if (Math.Abs(x - X(0)) < tol) return -1;
            if (Math.Abs(x - X(_project.Duration)) < tol) return _project.Segments.Count - 1;
            for (int i = 0; i < _project.Segments.Count - 1; i++)
                if (Math.Abs(x - X(_project.Segments[i].End)) < tol) return i;
            return int.MinValue;
        }

        private void StartBoundaryDrag(int b)
        {
            BeforeEdit?.Invoke();
            var segs = _project.Segments;
            if (b == -1)
            {
                // Dragging the very start: add an empty "cut" piece in front and move its end.
                segs.Insert(0, new ClipSegment { Start = 0, End = 0, Removed = true });
                b = 0;
            }
            else if (b == segs.Count - 1)
            {
                segs.Add(new ClipSegment { Start = _project.Duration, End = _project.Duration, Removed = true });
            }
            _dragBoundary = b;
            _drag = Drag.Boundary;
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            if (_project == null) return;
            var p = e.GetPosition(this);
            double t = Math.Clamp(T(p.X), 0, _project.Duration);
            switch (_drag)
            {
                case Drag.Scrub: SeekRequested?.Invoke(t); break;
                case Drag.Boundary:
                {
                    var a = _project.Segments[_dragBoundary];
                    var b = _project.Segments[_dragBoundary + 1];
                    // Outer cut pieces may shrink to nothing (drag the trim back out); inner pieces keep 50 ms minimum.
                    bool outerStart = a.Removed && a.Start <= 0.0001;
                    bool outerEnd = b.Removed && b.End >= _project.Duration - 0.0001;
                    double lo = outerStart ? 0 : a.Start + 0.05;
                    double hi = outerEnd ? _project.Duration : b.End - 0.05;
                    t = Math.Clamp(t, lo, Math.Max(lo, hi));
                    a.End = t;
                    b.Start = t;
                    _edited = true;
                    SeekRequested?.Invoke(t);
                    break;
                }
                case Drag.TextMove when SelectedText != null:
                {
                    double len = SelectedText.End - SelectedText.Start;
                    SelectedText.Start = Math.Clamp(t - _dragOffset, 0, _project.Duration - len);
                    SelectedText.End = SelectedText.Start + len;
                    _edited = true;
                    break;
                }
                case Drag.TextStart when SelectedText != null:
                    SelectedText.Start = Math.Min(t, SelectedText.End - 0.2); _edited = true; break;
                case Drag.TextEnd when SelectedText != null:
                    SelectedText.End = Math.Max(t, SelectedText.Start + 0.2); _edited = true; break;
                default:
                {
                    bool edge = p.Y >= VideoY && p.Y < VideoY + VideoH && p.X > HeaderW && HitBoundary(p.X) != int.MinValue;
                    bool textEdge = p.Y >= TextY && p.Y < TextY + TextH &&
                                    _project.Texts.Any(x => Math.Abs(p.X - X(x.Start)) < 6 || Math.Abs(p.X - X(x.End)) < 6);
                    Cursor = edge || textEdge ? Cursors.SizeWE : p.X > HeaderW && p.Y < RulerH ? Cursors.IBeam : Cursors.Arrow;
                    return;
                }
            }
            if (p.X > ActualWidth - 20) Scroll += 4 / _pps * 4;
            else if (p.X < HeaderW + 20 && _scroll > 0) Scroll -= 4 / _pps * 4;
            InvalidateVisual();
        }

        protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
        {
            if (_drag == Drag.Boundary)
            {
                _project.Normalize();
                SelectedSegment = Math.Min(SelectedSegment, _project.Segments.Count - 1);
            }
            bool edited = _edited;
            _drag = Drag.None;
            _edited = false;
            ReleaseMouseCapture();
            InvalidateVisual();
            if (edited) Edited?.Invoke();
        }

        protected override void OnMouseRightButtonUp(MouseButtonEventArgs e)
        {
            if (_project == null) return;
            var p = e.GetPosition(this);
            if (p.X < HeaderW || p.Y < VideoY) return;
            double t = Math.Clamp(T(p.X), 0, _project.Duration);
            if (p.Y >= TextY && p.Y < TextY + TextH)
            {
                SelectedText = _project.Texts.LastOrDefault(x => t >= x.Start && t <= x.End);
                SelectionChanged?.Invoke();
                InvalidateVisual();
                return;
            }
            SelectedSegment = _project.Segments.IndexOf(_project.SegmentAt(t));
            SelectionChanged?.Invoke();
            InvalidateVisual();
            SegmentContextMenu?.Invoke(SelectedSegment, p);
            e.Handled = true;
        }

        protected override void OnMouseWheel(MouseWheelEventArgs e)
        {
            if (_project == null) return;
            if (Keyboard.Modifiers.HasFlag(ModifierKeys.Control))
            {
                double x = e.GetPosition(this).X;
                double anchor = T(Math.Max(HeaderW, x));
                PixelsPerSecond *= e.Delta > 0 ? 1.25 : 0.8;
                Scroll = anchor - (Math.Max(HeaderW, x) - HeaderW) / _pps;
            }
            else Scroll -= e.Delta / 120.0 * ViewportSeconds * 0.1;
            e.Handled = true;
        }

        public void SelectSegment(int i)
        {
            SelectedSegment = i;
            InvalidateVisual();
        }

        public void SelectText(TextOverlay t)
        {
            SelectedText = t;
            InvalidateVisual();
        }
    }
}
