using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Effects;
using System.Windows.Shapes;
using System.Windows.Threading;
using WaveClips.Core;
using WaveClips.Editor;

namespace WaveClips.Views
{
    /// <summary>Mixer row bound to one <see cref="TrackMix"/>.</summary>
    public sealed class TrackMixVm : ObservableObject
    {
        private readonly Action _changed;
        public TrackMix Model { get; }
        public TrackMixVm(TrackMix m, Action changed) { Model = m; _changed = changed; Brush = TrackRow.BrushFor(m.Color); }
        public string Name => Model.IsMix ? Model.Name + "  (recorded mix)" : Model.Name;
        public Brush Brush { get; }
        public bool Muted => Model.Muted;
        public bool Solo => Model.Solo;
        public double Volume { get => Model.Volume; set { Model.Volume = value; OnPropertyChanged(); OnPropertyChanged(nameof(VolumeText)); _changed(); } }
        public double Offset { get => Model.Offset; set { Model.Offset = Math.Round(value, 3); OnPropertyChanged(); OnPropertyChanged(nameof(OffsetText)); _changed(); } }
        public string VolumeText => $"{Model.Volume * 100:0}%";
        public string OffsetText => Math.Abs(Model.Offset) < 0.0005 ? "0 ms" : $"{Model.Offset * 1000:+0;-0} ms";
        public void Refresh() { OnPropertyChanged(nameof(Muted)); OnPropertyChanged(nameof(Solo)); OnPropertyChanged(nameof(Volume)); OnPropertyChanged(nameof(VolumeText)); }
    }

    public partial class EditorView : UserControl, IPage
    {
        private EditProject _p;
        private EditorMedia _media;
        private readonly MultiTrackPlayer _audio = new MultiTrackPlayer();
        private readonly UndoStack _undo = new UndoStack();
        private readonly DispatcherTimer _tick = new DispatcherTimer(DispatcherPriority.Render) { Interval = TimeSpan.FromMilliseconds(25) };
        private readonly DispatcherTimer _autosave = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1.5) };
        private CancellationTokenSource _loadCts, _exportCts;
        private List<TrackMixVm> _mixer = new List<TrackMixVm>();
        private bool _playing, _mediaReady, _usingProxy, _updating;
        private double _pendingSeek = -1;
        private string _lastExport;

        // export choices
        private int _exHeight;
        private double _exFps;
        private double _exTarget;
        private bool _exMix = true, _exGif, _exUpscale;

        private static readonly string[] Palette = { "#00E5FF", "#8C9EFF", "#3DFFB0", "#FFC940", "#FF6E9C", "#B388FF" };

        public EditorView()
        {
            InitializeComponent();
            _tick.Tick += (_, __) => OnTick();
            _autosave.Tick += (_, __) => { _autosave.Stop(); SaveProject(); };
            Timeline.SeekRequested += t => Seek(t);
            Timeline.BeforeEdit += BeginEdit;
            Timeline.Edited += Edited;
            Timeline.SelectionChanged += () => { RefreshSegment(); SyncTextSelectionFromTimeline(); };
            Timeline.ViewChanged += UpdateScrollBar;
            Timeline.SegmentContextMenu += ShowSegmentMenu;
            Timeline.SizeChanged += (_, __) => UpdateScrollBar();
            PreviewKeyDown += OnKey;
            BuildStaticChips();
            ShowEmpty();
        }

        public void OnShown()
        {
            if (_p == null) ShowEmpty();
            _tick.Start();
            Focus();
        }

        public void OnHidden()
        {
            Pause();
            _tick.Stop();
            SaveProject();
        }

        // =====================================================================================
        // Loading
        // =====================================================================================
        private void ShowEmpty()
        {
            if (_p != null) return;
            EmptyRoot.Visibility = Visibility.Visible;
            EmptyRecent.ItemsSource = AppHost.Library.Items.Take(8).ToList();
        }

        public async void Load(string path)
        {
            if (!FFmpeg.Available)
            {
                MessageBox.Show("Install FFmpeg first (Settings → General).", "WaveClips");
                return;
            }
            Pause();
            SaveProject();
            _loadCts?.Cancel();
            var cts = _loadCts = new CancellationTokenSource();
            ShowBusy("Opening clip", System.IO.Path.GetFileName(path), cancellable: true);
            BusyProgress.IsIndeterminate = true;
            try
            {
                var info = await MediaProbe.ProbeAsync(path, cts.Token);
                if (info.Width <= 0 || info.Duration <= 0) throw new Exception("This file has no video WaveClips can edit.");
                var media = await EditorMedia.PrepareAsync(path, info, new Progress<string>(s => BusyText.Text = s), cts.Token);
                if (cts.IsCancellationRequested) return;

                EditProject p = null;
                var pf = EditorMedia.ProjectFileFor(path);
                if (File.Exists(pf))
                {
                    try
                    {
                        p = EditProject.Deserialize(File.ReadAllText(pf));
                        if (p.SourcePath != path || p.Tracks.Count != info.Audio.Count || Math.Abs(p.Duration - info.Duration) > 0.5) p = null;
                    }
                    catch { p = null; }
                }
                p ??= EditProject.Create(path, info, i => TrackColor(path, info, i));

                _p = p;
                _media = media;
                _undo.Clear();
                _audio.Load(media.TrackWavs);
                _mediaReady = false;
                _usingProxy = false;
                Player.Source = new Uri(path);
                Player.Play();
                Player.Pause();

                Timeline.Project = p;
                Timeline.Media = media;
                Timeline.Position = 0;
                ExportName.Text = System.IO.Path.GetFileNameWithoutExtension(path) + " (edit)";
                EmptyRoot.Visibility = Visibility.Collapsed;
                HideBusy();
                RefreshAll();
                await Dispatcher.InvokeAsync(() => { Timeline.FitToView(); UpdateZoomSlider(); }, DispatcherPriority.Loaded);
                Focus();
            }
            catch (OperationCanceledException) { HideBusy(); }
            catch (Exception ex)
            {
                Log.Error("Editor load", ex);
                HideBusy();
                MessageBox.Show(ex.Message, "Couldn't open clip");
            }
        }

        private static string TrackColor(string path, MediaInfo info, int index)
        {
            var name = info.Audio[index].Title;
            var cfg = AppHost.Settings.AudioTracks.FirstOrDefault(t => string.Equals(t.Name, name, StringComparison.OrdinalIgnoreCase));
            return cfg?.Color ?? Palette[index % Palette.Length];
        }

        private void OnMediaOpened(object sender, RoutedEventArgs e)
        {
            _mediaReady = true;
            Player.Pause();
            Seek(_pendingSeek >= 0 ? _pendingSeek : Timeline.Position);
            _pendingSeek = -1;
            RenderOverlay();
        }

        private async void OnMediaFailed(object sender, ExceptionRoutedEventArgs e)
        {
            if (_usingProxy || _p == null) { MessageBox.Show("Preview failed: " + e.ErrorException?.Message, "WaveClips"); return; }
            // Windows can't decode this codec (e.g. HEVC without the Store extension) - preview a converted copy instead.
            _usingProxy = true;
            ShowBusy("Preparing preview", "Converting for preview…", cancellable: false);
            BusyProgress.IsIndeterminate = true;
            try
            {
                var proxy = await EditorMedia.MakeProxyAsync(_p.SourcePath, new Progress<string>(s => BusyText.Text = s), CancellationToken.None);
                Player.Source = new Uri(proxy);
                Player.Play();
                Player.Pause();
            }
            catch (Exception ex) { MessageBox.Show(ex.Message, "WaveClips"); }
            finally { HideBusy(); }
        }

        private void OnMediaEnded(object sender, RoutedEventArgs e)
        {
            if (LoopBtn.IsChecked == true && _p != null) { Seek(_p.Kept.FirstOrDefault()?.Start ?? 0); Player.Play(); }
            else Pause();
        }

        private void SaveProject()
        {
            if (_p == null) return;
            try { File.WriteAllText(EditorMedia.ProjectFileFor(_p.SourcePath), _p.Serialize()); } catch { }
        }

        // =====================================================================================
        // Playback
        // =====================================================================================
        private double CurrentTime => _mediaReady ? Player.Position.TotalSeconds : Timeline.Position;

        private void Seek(double t)
        {
            if (_p == null) return;
            t = Math.Clamp(t, 0, _p.Duration);
            HideFx();
            if (_mediaReady) Player.Position = TimeSpan.FromSeconds(t);
            else _pendingSeek = t;
            if (_playing)
            {
                var seg = _p.SegmentAt(t);
                if (seg != null && Math.Abs(seg.Speed - 1) < 0.001) _audio.Play(t);
            }
            UpdatePlayhead(t);
        }

        private void Play()
        {
            if (_p == null || !_mediaReady) return;
            var kept = _p.Kept.ToList();
            if (kept.Count == 0) return;
            double t = CurrentTime;
            if (t >= kept[^1].End - 0.05) t = kept[0].Start;
            var next = _p.NextKept(t) ?? kept[0].Start;
            if (Math.Abs(next - CurrentTime) > 0.001) Seek(next);
            var seg = _p.SegmentAt(next);
            Player.SpeedRatio = seg?.Speed ?? 1;
            Player.Play();
            _playing = true;
            if (seg == null || Math.Abs(seg.Speed - 1) < 0.001) _audio.Play(next); else _audio.Pause();
            PlayIcon.Text = "";
            HideFx();
        }

        private void Pause()
        {
            if (!_playing) return;
            _playing = false;
            Player.Pause();
            _audio.Pause();
            PlayIcon.Text = "";
        }

        private void OnTick()
        {
            if (_p == null || !_playing) return;
            double t = Player.Position.TotalSeconds;
            var kept = _p.Kept.ToList();
            double end = kept.Count > 0 ? kept[^1].End : _p.Duration;
            if (t >= end - 0.02)
            {
                if (LoopBtn.IsChecked == true && kept.Count > 0) { Seek(kept[0].Start); return; }
                Pause();
                Seek(end);
                return;
            }
            var seg = _p.SegmentAt(t);
            if (seg != null && seg.Removed)
            {
                var next = _p.NextKept(seg.End);
                if (next == null) { Pause(); return; }
                Seek(next.Value);
                return;
            }
            if (seg != null)
            {
                bool normal = Math.Abs(seg.Speed - 1) < 0.001;
                if (Math.Abs(Player.SpeedRatio - seg.Speed) > 0.001)
                {
                    Player.SpeedRatio = seg.Speed;
                    if (normal) _audio.Play(t); else _audio.Pause();
                }
                else if (normal)
                {
                    if (!_audio.IsPlaying) _audio.Play(t);
                    else if (Math.Abs(_audio.Position - t) > 0.12) _audio.Seek(t);
                }
            }
            UpdatePlayhead(t);
            Timeline.EnsureVisible(t);
        }

        private void UpdatePlayhead(double t)
        {
            Timeline.Position = t;
            if (_p == null) return;
            TimeText.Text = $"{TimelineControl.Fmt(t, true)} / {TimelineControl.Fmt(_p.Duration, true)}";
            var seg = _p.SegmentAt(t);
            bool fast = seg != null && Math.Abs(seg.Speed - 1) > 0.001 && !seg.Removed;
            SpeedBadge.Visibility = fast ? Visibility.Visible : Visibility.Collapsed;
            if (fast) SpeedBadgeText.Text = $"{seg.Speed:0.##}x  ·  audio plays in the export";
            RenderOverlay();
        }

        private void OnPlayPause(object sender, RoutedEventArgs e) { if (_playing) Pause(); else Play(); }
        private void OnGoStart(object sender, RoutedEventArgs e) { Pause(); Seek(_p?.Kept.FirstOrDefault()?.Start ?? 0); }
        private void OnGoEnd(object sender, RoutedEventArgs e) { Pause(); Seek(_p?.Kept.LastOrDefault()?.End ?? 0); }
        private void OnFrameBack(object sender, RoutedEventArgs e) => Step(-1);
        private void OnFrameFwd(object sender, RoutedEventArgs e) => Step(1);

        private void Step(int frames, bool second = false)
        {
            if (_p == null) return;
            Pause();
            Seek(CurrentTime + (second ? frames : frames / Math.Max(1, _p.Fps)));
        }

        private void OnPreviewVolume(object sender, RoutedPropertyChangedEventArgs<double> e) => _audio.MasterVolume = (float)e.NewValue;

        private void ApplyAudioMix()
        {
            if (_p == null) return;
            var audible = _p.AudibleTracks().ToHashSet();
            for (int i = 0; i < _p.Tracks.Count; i++)
            {
                var t = _p.Tracks[i];
                _audio.SetTrack(t.StreamIndex, audible.Contains(t) ? (float)t.Volume : 0f, t.Offset);
            }
        }

        // =====================================================================================
        // Editing
        // =====================================================================================
        private void BeginEdit() { if (_p != null) _undo.Push(_p); }

        private void Edited()
        {
            if (_p == null) return;
            ApplyAudioMix();
            Timeline.InvalidateVisual();
            RenderOverlay();
            RefreshSegment();
            UpdateOutputLength();
            UndoBtn.IsEnabled = _undo.CanUndo;
            RedoBtn.IsEnabled = _undo.CanRedo;
            _autosave.Stop();
            _autosave.Start();
        }

        private void OnSliderGrab(object sender, MouseButtonEventArgs e) => BeginEdit();

        private void OnUndo(object sender, RoutedEventArgs e) => UndoRedo(true);
        private void OnRedo(object sender, RoutedEventArgs e) => UndoRedo(false);

        private void UndoRedo(bool undo)
        {
            if (_p == null) return;
            _p = undo ? _undo.Undo(_p) : _undo.Redo(_p);
            Timeline.Project = _p;
            RefreshAll();
        }

        private void OnSplit(object sender, RoutedEventArgs e)
        {
            if (_p == null) return;
            BeginEdit();
            double t = CurrentTime;
            _p.Split(t);
            Timeline.SelectSegment(_p.Segments.IndexOf(_p.SegmentAt(t)));
            Edited();
        }

        private void OnToggleCut(object sender, RoutedEventArgs e)
        {
            if (_p == null) return;
            int i = Timeline.SelectedSegment;
            if (i < 0 || i >= _p.Segments.Count) i = _p.Segments.IndexOf(_p.SegmentAt(CurrentTime));
            if (i < 0) return;
            var s = _p.Segments[i];
            if (!s.Removed && _p.Kept.Count() <= 1 && _p.Kept.Contains(s)) { MessageBox.Show("That's the last piece - split it first.", "WaveClips"); return; }
            BeginEdit();
            s.Removed = !s.Removed;
            _p.Normalize();
            Timeline.SelectSegment(Math.Min(i, _p.Segments.Count - 1));
            Edited();
        }

        private void OnTrimIn(object sender, RoutedEventArgs e)
        {
            if (_p == null) return;
            double t = CurrentTime;
            if (t < 0.05) return;
            BeginEdit();
            _p.Split(t);
            foreach (var s in _p.Segments.Where(s => s.End <= t + 0.001)) s.Removed = true;
            _p.Normalize();
            Edited();
        }

        private void OnTrimOut(object sender, RoutedEventArgs e)
        {
            if (_p == null) return;
            double t = CurrentTime;
            if (t > _p.Duration - 0.05) return;
            BeginEdit();
            _p.Split(t);
            foreach (var s in _p.Segments.Where(s => s.Start >= t - 0.001)) s.Removed = true;
            _p.Normalize();
            Edited();
        }

        private void ShowSegmentMenu(int index, Point p)
        {
            if (_p == null || index < 0) return;
            var seg = _p.Segments[index];
            var menu = new ContextMenu { PlacementTarget = Timeline, Placement = PlacementMode.MousePoint };
            void Add(string header, Action a) { var mi = new MenuItem { Header = header }; mi.Click += (_, __) => a(); menu.Items.Add(mi); }
            Add("✂  Split at playhead", () => OnSplit(null, null));
            Add(seg.Removed ? "↺  Restore this piece" : "🗑  Cut this piece", () => OnToggleCut(null, null));
            menu.Items.Add(new Separator());
            foreach (var sp in new[] { 0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 4.0 })
            {
                double speed = sp;
                Add((Math.Abs(seg.Speed - speed) < 0.001 ? "● " : "    ") + $"{speed:0.##}x speed", () => SetSpeed(speed));
            }
            menu.IsOpen = true;
        }

        private void SetSpeed(double speed)
        {
            if (_p == null) return;
            int i = Timeline.SelectedSegment;
            if (i < 0 || i >= _p.Segments.Count) i = _p.Segments.IndexOf(_p.SegmentAt(CurrentTime));
            if (i < 0) return;
            BeginEdit();
            _p.Segments[i].Speed = speed;
            Edited();
            RefreshSegment();
        }

        private void OnFadeChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (_updating || _p == null) return;
            _p.FadeIn = Math.Round(FadeInSlider.Value, 1);
            _p.FadeOut = Math.Round(FadeOutSlider.Value, 1);
            FadeInText.Text = $"{_p.FadeIn:0.0}s";
            FadeOutText.Text = $"{_p.FadeOut:0.0}s";
            Edited();
        }

        // ---------------------------------------------------------------- look
        private void OnLookSlider(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (_updating || _p == null) return;
            _p.Brightness = Math.Round(BrightSlider.Value, 3);
            _p.Contrast = Math.Round(ContrastSlider.Value, 3);
            _p.Saturation = Math.Round(SatSlider.Value, 3);
            RefreshLookLabels();
            Edited();
        }

        private void OnLookToggle(object sender, RoutedEventArgs e)
        {
            if (_p == null) return;
            BeginEdit();
            _p.Vignette = VignetteBox.IsChecked == true;
            _p.Sharpen = SharpenBox.IsChecked == true;
            Edited();
        }

        private void RefreshLookLabels()
        {
            BrightText.Text = $"{_p.Brightness:+0.00;-0.00;0}";
            ContrastText.Text = $"{_p.Contrast:0.00}";
            SatText.Text = $"{_p.Saturation:0.00}";
        }

        // ---------------------------------------------------------------- frame / crop
        private void OnFrameSlider(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (_updating || _p == null) return;
            _p.Zoom = Math.Round(ZoomSlider.Value, 3);
            _p.CropX = PanXSlider.Value;
            _p.CropY = PanYSlider.Value;
            ZoomText.Text = $"{_p.Zoom:0.00}x";
            Edited();
        }

        private void OnResetFrame(object sender, RoutedEventArgs e)
        {
            if (_p == null) return;
            BeginEdit();
            _p.Aspect = "source"; _p.Zoom = 1; _p.CropX = 0.5; _p.CropY = 0.5;
            RefreshAll();
            Edited();
        }

        // ---------------------------------------------------------------- text
        private TextOverlay SelectedText => TextList.SelectedItem as TextOverlay;

        private void OnAddText(object sender, RoutedEventArgs e)
        {
            if (_p == null) return;
            BeginEdit();
            double t = CurrentTime;
            var o = new TextOverlay { Start = t, End = Math.Min(_p.Duration, t + 3) };
            _p.Texts.Add(o);
            TabText.IsChecked = true;
            RefreshTexts(o);
            Timeline.SelectText(o);
            Edited();
            TextContent.Focus();
            TextContent.SelectAll();
        }

        private void OnTextSelected(object sender, SelectionChangedEventArgs e)
        {
            if (_updating) return;
            Timeline.SelectText(SelectedText);
            RefreshTextEditor();
            RenderOverlay();
        }

        private void SyncTextSelectionFromTimeline()
        {
            if (Timeline.SelectedText == null || Timeline.SelectedText == SelectedText) return;
            TabText.IsChecked = true;
            RefreshTexts(Timeline.SelectedText);
        }

        private bool _textTyping;
        private void OnTextContent(object sender, TextChangedEventArgs e)
        {
            if (_updating || SelectedText == null) return;
            if (!_textTyping) { BeginEdit(); _textTyping = true; }
            SelectedText.Text = TextContent.Text;
            _updating = true;
            TextList.Items.Refresh();
            _updating = false;
            Edited();
        }

        private void OnTextSize(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (_updating || SelectedText == null) return;
            SelectedText.Size = Math.Round(TextSize.Value, 4);
            Edited();
        }

        private void OnTextStartHere(object sender, RoutedEventArgs e)
        {
            if (SelectedText == null) return;
            BeginEdit();
            SelectedText.Start = Math.Min(CurrentTime, SelectedText.End - 0.2);
            RefreshTextEditor();
            Edited();
        }

        private void OnTextEndHere(object sender, RoutedEventArgs e)
        {
            if (SelectedText == null) return;
            BeginEdit();
            SelectedText.End = Math.Max(CurrentTime, SelectedText.Start + 0.2);
            RefreshTextEditor();
            Edited();
        }

        private void OnDeleteText(object sender, RoutedEventArgs e)
        {
            if (SelectedText == null || _p == null) return;
            BeginEdit();
            _p.Texts.Remove(SelectedText);
            RefreshTexts(null);
            Timeline.SelectText(null);
            Edited();
        }

        // ---------------------------------------------------------------- mixer
        private void OnMute(object sender, RoutedEventArgs e)
        {
            if (((FrameworkElement)sender).Tag is not TrackMixVm vm) return;
            BeginEdit();
            vm.Model.Muted = !vm.Model.Muted;
            vm.Refresh();
            Edited();
        }

        private void OnSolo(object sender, RoutedEventArgs e)
        {
            if (((FrameworkElement)sender).Tag is not TrackMixVm vm) return;
            BeginEdit();
            vm.Model.Solo = !vm.Model.Solo;
            vm.Refresh();
            Edited();
        }

        // =====================================================================================
        // Preview overlay (crop frame + text)
        // =====================================================================================
        private Rect VideoRect()
        {
            double hw = VideoHost.ActualWidth, hh = VideoHost.ActualHeight;
            if (_p == null || _p.Width <= 0 || hw <= 0) return new Rect(0, 0, hw, hh);
            double s = Math.Min(hw / _p.Width, hh / _p.Height);
            double w = _p.Width * s, h = _p.Height * s;
            return new Rect((hw - w) / 2, (hh - h) / 2, w, h);
        }

        private Rect CropDisplayRect()
        {
            var disp = VideoRect();
            var c = _p.CropRect();
            double s = disp.Width / _p.Width;
            return new Rect(disp.X + c.x * s, disp.Y + c.y * s, c.w * s, c.h * s);
        }

        private static readonly Brush DimBrush = MakeBrush(0xB0000000);
        private static Brush MakeBrush(uint argb)
        {
            var b = new SolidColorBrush(Color.FromArgb((byte)(argb >> 24), (byte)(argb >> 16), (byte)(argb >> 8), (byte)argb));
            b.Freeze();
            return b;
        }

        private void OnVideoHostSize(object sender, SizeChangedEventArgs e) => RenderOverlay();

        private void RenderOverlay()
        {
            Overlay.Children.Clear();
            if (_p == null || FxImage.Visibility == Visibility.Visible) return;
            var disp = VideoRect();
            var crop = CropDisplayRect();
            if (_p.HasCrop)
            {
                void Dim(double x, double y, double w, double h)
                {
                    if (w <= 0 || h <= 0) return;
                    var r = new Rectangle { Width = w, Height = h, Fill = DimBrush, IsHitTestVisible = false };
                    Canvas.SetLeft(r, x); Canvas.SetTop(r, y);
                    Overlay.Children.Add(r);
                }
                Dim(disp.X, disp.Y, disp.Width, crop.Y - disp.Y);
                Dim(disp.X, crop.Bottom, disp.Width, disp.Bottom - crop.Bottom);
                Dim(disp.X, crop.Y, crop.X - disp.X, crop.Height);
                Dim(crop.Right, crop.Y, disp.Right - crop.Right, crop.Height);
                var frame = new Rectangle
                {
                    Width = crop.Width, Height = crop.Height, Stroke = (Brush)FindResource("Accent"), StrokeThickness = 2,
                    Fill = Brushes.Transparent, Cursor = Cursors.SizeAll, Tag = "crop", Effect = (Effect)FindResource("GlowSoft"),
                };
                Canvas.SetLeft(frame, crop.X); Canvas.SetTop(frame, crop.Y);
                Overlay.Children.Add(frame);
                var label = new TextBlock { Text = $"{_p.Aspect.ToUpperInvariant()}  ·  drag to aim", Foreground = (Brush)FindResource("Accent"), FontSize = 11, IsHitTestVisible = false };
                Canvas.SetLeft(label, crop.X + 6); Canvas.SetTop(label, crop.Y + 4);
                Overlay.Children.Add(label);
            }

            double t = Timeline.Position;
            foreach (var o in _p.Texts.Where(o => t >= o.Start && t <= o.End || o == SelectedText && TabText.IsChecked == true))
            {
                var el = MakeTextVisual(o, crop.Height);
                el.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
                var size = el.DesiredSize;
                Canvas.SetLeft(el, crop.X + o.X * crop.Width - size.Width / 2);
                Canvas.SetTop(el, crop.Y + o.Y * crop.Height - size.Height / 2);
                el.Opacity = t >= o.Start && t <= o.End ? 1 : 0.45;
                Overlay.Children.Add(el);
            }
        }

        private FrameworkElement MakeTextVisual(TextOverlay o, double frameHeight)
        {
            Brush color;
            try { color = (Brush)new BrushConverter().ConvertFromString(o.Color); } catch { color = Brushes.White; }
            var tb = new TextBlock
            {
                Text = string.IsNullOrEmpty(o.Text) ? " " : o.Text,
                FontFamily = (FontFamily)FindResource("HeadingFont"),
                FontSize = Math.Max(6, o.Size * frameHeight),
                Foreground = color,
            };
            FrameworkElement el = tb;
            switch (o.Style)
            {
                case TextStyle.WaveGlow: tb.Effect = new DropShadowEffect { Color = Color.FromRgb(0, 0xE5, 0xFF), BlurRadius = 18, ShadowDepth = 0, Opacity = 1 }; break;
                case TextStyle.Outline: tb.Effect = new DropShadowEffect { Color = Colors.Black, BlurRadius = 5, ShadowDepth = 0, Opacity = 1 }; break;
                case TextStyle.Plain: tb.Effect = new DropShadowEffect { Color = Colors.Black, BlurRadius = 2, ShadowDepth = 2, Opacity = 0.6 }; break;
                case TextStyle.Box:
                    el = new Border { Background = MakeBrush(0xD9071B24), Padding = new Thickness(tb.FontSize / 3, tb.FontSize / 6, tb.FontSize / 3, tb.FontSize / 6), Child = tb };
                    break;
            }
            if (o == SelectedText)
                el = new Border { BorderBrush = (Brush)FindResource("Accent"), BorderThickness = new Thickness(1), Child = el, Padding = new Thickness(2) };
            el.Tag = o;
            el.Cursor = Cursors.SizeAll;
            return el;
        }

        private enum OverlayDrag { None, Crop, Text }
        private OverlayDrag _odrag;
        private Point _odragStart;
        private double _odragA, _odragB;
        private TextOverlay _odragText;

        private void OnOverlayDown(object sender, MouseButtonEventArgs e)
        {
            Focus();
            if (_p == null) return;
            if (FxImage.Visibility == Visibility.Visible) { HideFx(); RenderOverlay(); return; }
            var src = e.OriginalSource as DependencyObject;
            TextOverlay hitText = null;
            object tag = null;
            while (src != null && src != Overlay)
            {
                if (src is FrameworkElement fe && fe.Tag != null) { tag = fe.Tag; if (fe.Tag is TextOverlay to) { hitText = to; break; } }
                src = VisualTreeHelper.GetParent(src);
            }
            _odragStart = e.GetPosition(Overlay);
            if (hitText != null)
            {
                BeginEdit();
                _odrag = OverlayDrag.Text;
                _odragText = hitText;
                _odragA = hitText.X; _odragB = hitText.Y;
                if (SelectedText != hitText) { TabText.IsChecked = true; RefreshTexts(hitText); Timeline.SelectText(hitText); }
            }
            else if ((tag as string) == "crop")
            {
                BeginEdit();
                _odrag = OverlayDrag.Crop;
                _odragA = _p.CropX; _odragB = _p.CropY;
            }
            else { OnPlayPause(null, null); return; }
            Overlay.CaptureMouse();
        }

        private void OnOverlayMove(object sender, MouseEventArgs e)
        {
            if (_odrag == OverlayDrag.None || _p == null) return;
            var p = e.GetPosition(Overlay);
            double dx = p.X - _odragStart.X, dy = p.Y - _odragStart.Y;
            if (_odrag == OverlayDrag.Crop)
            {
                var disp = VideoRect();
                _p.CropX = Math.Clamp(_odragA + dx / disp.Width, 0, 1);
                _p.CropY = Math.Clamp(_odragB + dy / disp.Height, 0, 1);
            }
            else if (_odragText != null)
            {
                var crop = CropDisplayRect();
                _odragText.X = Math.Clamp(_odragA + dx / crop.Width, 0, 1);
                _odragText.Y = Math.Clamp(_odragB + dy / crop.Height, 0, 1);
            }
            RenderOverlay();
        }

        private void OnOverlayUp(object sender, MouseButtonEventArgs e)
        {
            if (_odrag == OverlayDrag.None) return;
            if (_odrag == OverlayDrag.Crop && _p != null)
            {
                // Snap the stored centre back into the reachable range so sliders match.
                var c = _p.CropRect();
                _p.CropX = _p.Width > c.w ? (c.x + c.w / 2.0) / _p.Width : 0.5;
                _p.CropY = _p.Height > c.h ? (c.y + c.h / 2.0) / _p.Height : 0.5;
            }
            _odrag = OverlayDrag.None;
            _odragText = null;
            Overlay.ReleaseMouseCapture();
            RefreshFormat();
            Edited();
        }

        // ---------------------------------------------------------------- FX frame preview
        private async void OnFxPreview(object sender, RoutedEventArgs e)
        {
            if (_p == null) return;
            Pause();
            var png = System.IO.Path.Combine(Paths.Temp, "fx_preview.png");
            var plan = ExportBuilder.BuildFramePreview(_p, CurrentTime, png);
            FxBadge.Visibility = Visibility.Visible;
            ((TextBlock)FxBadge.Child).Text = "Rendering…";
            var r = await FFmpeg.RunAsync(plan.Args, workDir: plan.WorkDir);
            if (!r.Success) { FxBadge.Visibility = Visibility.Collapsed; MessageBox.Show(r.StdErrTail, "Preview failed"); return; }
            FxImage.Source = ClipLibrary.LoadImage(png);
            FxImage.Visibility = Visibility.Visible;
            ((TextBlock)FxBadge.Child).Text = "FX PREVIEW  ·  click the video to close";
            Overlay.Children.Clear();
        }

        private void HideFx()
        {
            if (FxImage.Visibility != Visibility.Visible) return;
            FxImage.Visibility = Visibility.Collapsed;
            FxBadge.Visibility = Visibility.Collapsed;
        }

        // =====================================================================================
        // Inspector refresh
        // =====================================================================================
        private void RefreshAll()
        {
            if (_p == null) return;
            _updating = true;
            try
            {
                ClipTitle.Text = System.IO.Path.GetFileNameWithoutExtension(_p.SourcePath);
                _mixer = _p.Tracks.Select(t => new TrackMixVm(t, () => { Timeline.InvalidateVisual(); ApplyAudioMix(); _autosave.Stop(); _autosave.Start(); })).ToList();
                MixerList.ItemsSource = _mixer;
                NoAudioText.Visibility = _p.Tracks.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
                FadeInSlider.Value = _p.FadeIn; FadeOutSlider.Value = _p.FadeOut;
                FadeInText.Text = $"{_p.FadeIn:0.0}s"; FadeOutText.Text = $"{_p.FadeOut:0.0}s";
                BrightSlider.Value = _p.Brightness; ContrastSlider.Value = _p.Contrast; SatSlider.Value = _p.Saturation;
                VignetteBox.IsChecked = _p.Vignette; SharpenBox.IsChecked = _p.Sharpen;
                RefreshLookLabels();
                SetChips(LookChips, _p.Look);
                RefreshFormat();
                RefreshTexts(SelectedText != null && _p.Texts.Contains(SelectedText) ? SelectedText : null);
            }
            finally { _updating = false; }
            ApplyAudioMix();
            RefreshSegment();
            UpdateOutputLength();
            UndoBtn.IsEnabled = _undo.CanUndo;
            RedoBtn.IsEnabled = _undo.CanRedo;
            Timeline.InvalidateMeasure();
            Timeline.InvalidateVisual();
            UpdatePlayhead(Timeline.Position);
            UpdateExportSummary();
        }

        private void RefreshSegment()
        {
            if (_p == null) return;
            int i = Timeline.SelectedSegment;
            var s = i >= 0 && i < _p.Segments.Count ? _p.Segments[i] : _p.SegmentAt(CurrentTime);
            SegInfo.Text = s == null ? "" :
                $"{TimelineControl.Fmt(s.Start, true)} → {TimelineControl.Fmt(s.End, true)}  ({s.Length:0.0}s{(s.Removed ? ", cut" : "")})";
            CutBtn.Content = s != null && s.Removed ? "Restore piece" : "Cut piece";
            _updating = true;
            SetChips(SpeedChips, s?.Speed ?? 1.0);
            _updating = false;
        }

        private void RefreshFormat()
        {
            if (_p == null) return;
            bool was = _updating;
            _updating = true;
            SetChips(AspectChips, _p.Aspect);
            ZoomSlider.Value = _p.Zoom; PanXSlider.Value = _p.CropX; PanYSlider.Value = _p.CropY;
            ZoomText.Text = $"{_p.Zoom:0.00}x";
            _updating = was;
        }

        private void RefreshTexts(TextOverlay select)
        {
            bool was = _updating;
            _updating = true;
            TextList.ItemsSource = null;
            TextList.ItemsSource = _p?.Texts;
            TextList.SelectedItem = select;
            _updating = was;
            RefreshTextEditor();
        }

        private void RefreshTextEditor()
        {
            var t = SelectedText;
            TextEditor.IsEnabled = t != null;
            TextEditor.Opacity = t != null ? 1 : 0.4;
            _textTyping = false;
            bool was = _updating;
            _updating = true;
            TextContent.Text = t?.Text ?? "";
            TextSize.Value = t?.Size ?? 0.08;
            SetChips(TextStyleChips, t?.Style ?? TextStyle.WaveGlow);
            SetChips(TextColorChips, t?.Color ?? "#FFFFFF");
            TextTimes.Text = t == null ? "Add a text to edit it." : $"Shows from {TimelineControl.Fmt(t.Start, true)} to {TimelineControl.Fmt(t.End, true)}  (drag its bar in the timeline)";
            _updating = was;
        }

        private void UpdateOutputLength()
        {
            if (_p == null) return;
            OutLenText.Text = $"Export length {TimelineControl.Fmt(_p.OutputDuration, true)}";
            UpdateExportSummary();
        }

        // =====================================================================================
        // Chips
        // =====================================================================================
        private static int _chipGroup;

        private void MakeChips(Panel host, IEnumerable<(object value, string label)> items, Action<object> onPick)
        {
            host.Children.Clear();
            string group = "ed" + (++_chipGroup);
            foreach (var (value, label) in items)
            {
                var rb = new RadioButton { Content = label, Tag = value, GroupName = group, Style = (Style)FindResource("Chip"), Padding = new Thickness(10, 5, 10, 5) };
                rb.Checked += (_, __) => { if (!_updating) onPick(rb.Tag); };
                host.Children.Add(rb);
            }
        }

        private static void SetChips(Panel host, object value)
        {
            foreach (var rb in host.Children.OfType<RadioButton>())
                rb.IsChecked = Equals(rb.Tag, value);
        }

        private void BuildStaticChips()
        {
            MakeChips(SpeedChips, new[] { 0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 4.0 }.Select(s => ((object)s, s == 1 ? "1x" : $"{s:0.##}x")), v => SetSpeed((double)v));
            MakeChips(LookChips, new (object, string)[]
            {
                (ColorLook.None, "None"), (ColorLook.Vibrant, "Vibrant"), (ColorLook.Cinematic, "Cinematic"),
                (ColorLook.Wave, "Wave (cyan)"), (ColorLook.Warm, "Warm"), (ColorLook.BlackWhite, "B&W"),
            }, v => { if (_p == null) return; BeginEdit(); _p.Look = (ColorLook)v; Edited(); });
            MakeChips(TextStyleChips, new (object, string)[]
            {
                (TextStyle.WaveGlow, "Wave glow"), (TextStyle.Outline, "Outline"), (TextStyle.Plain, "Shadow"), (TextStyle.Box, "Box"),
            }, v => { if (SelectedText == null) return; BeginEdit(); SelectedText.Style = (TextStyle)v; Edited(); });
            MakeChips(TextColorChips, new (object, string)[]
            {
                ("#FFFFFF", "White"), ("#00E5FF", "Cyan"), ("#FFC940", "Gold"), ("#FF4060", "Red"), ("#3DFFB0", "Green"), ("#B388FF", "Purple"),
            }, v => { if (SelectedText == null) return; BeginEdit(); SelectedText.Color = (string)v; Edited(); });
            MakeChips(TextPosChips, new (object, string)[] { (0.12, "Top"), (0.5, "Middle"), (0.82, "Bottom") },
                v => { if (SelectedText == null) return; BeginEdit(); SelectedText.X = 0.5; SelectedText.Y = (double)v; Edited(); });
            MakeChips(AspectChips, new (object, string)[]
            {
                ("source", "Original"), ("16:9", "16:9"), ("9:16", "9:16 Shorts"), ("1:1", "1:1"), ("4:5", "4:5"),
            }, v => { if (_p == null) return; BeginEdit(); _p.Aspect = (string)v; RefreshFormat(); Edited(); });

            MakeChips(ExportPresetChips, new (object, string)[]
            {
                ("best", "Best quality"), ("d10", "Discord 10 MB"), ("d50", "Discord 50 MB"), ("yt", "YouTube 1080p60"),
                ("shorts", "TikTok / Shorts"), ("gif", "GIF"),
            }, v => ApplyExportPreset((string)v));
            MakeChips(ExportResChips, new (object, string)[] { (0, "Source"), (1440, "1440p"), (1080, "1080p"), (720, "720p"), (480, "480p") },
                v => { _exHeight = (int)v; _exUpscale = false; UpdateExportSummary(); });
            MakeChips(ExportFpsChips, new (object, string)[] { (0.0, "Source"), (60.0, "60"), (30.0, "30") },
                v => { _exFps = (double)v; UpdateExportSummary(); });
            MakeChips(ExportSizeChips, new (object, string)[] { (0.0, "No limit"), (10.0, "10 MB"), (25.0, "25 MB"), (50.0, "50 MB"), (100.0, "100 MB"), (500.0, "500 MB") },
                v => { _exTarget = (double)v; UpdateExportSummary(); });
            MakeChips(ExportAudioChips, new (object, string)[] { (true, "Mix into one track"), (false, "Keep separate tracks") },
                v => { _exMix = (bool)v; UpdateExportSummary(); });
            ApplyExportPreset("best");
        }

        private void ApplyExportPreset(string preset)
        {
            _exGif = false; _exUpscale = false; _exMix = true;
            switch (preset)
            {
                case "d10": _exHeight = 1080; _exFps = 60; _exTarget = 10; break;
                case "d50": _exHeight = 1080; _exFps = 60; _exTarget = 50; break;
                case "yt": _exHeight = 1080; _exFps = 60; _exTarget = 0; ExportQuality.Value = 90; break;
                case "shorts":
                    _exHeight = 1920; _exUpscale = true; _exFps = 60; _exTarget = 0;
                    if (_p != null && _p.Aspect != "9:16") { BeginEdit(); _p.Aspect = "9:16"; RefreshFormat(); Edited(); }
                    break;
                case "gif": _exGif = true; _exHeight = 360; _exFps = 20; _exTarget = 0; break;
                default: _exHeight = 0; _exFps = 0; _exTarget = 0; ExportQuality.Value = 85; break;
            }
            bool was = _updating;
            _updating = true;
            SetChips(ExportPresetChips, preset);
            SetChips(ExportResChips, _exHeight);
            SetChips(ExportFpsChips, _exFps);
            SetChips(ExportSizeChips, _exTarget);
            SetChips(ExportAudioChips, _exMix);
            _updating = was;
            UpdateExportSummary();
        }

        private void OnExportQuality(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (ExportQualityText != null) ExportQualityText.Text = $"{(int)ExportQuality.Value}";
        }

        private void UpdateExportSummary()
        {
            if (_p == null || ExportSummary == null) return;
            var crop = _p.CropRect();
            int h = _exHeight > 0 ? (_exUpscale ? _exHeight : Math.Min(_exHeight, crop.h)) : crop.h;
            int w = _p.OutputWidthFor(h);
            string size = _exTarget > 0 ? $"≤ {_exTarget:0} MB" : "size by quality";
            string audio = _exGif ? "no audio" : _exMix ? $"{_p.AudibleTracks().Count()} tracks mixed" : $"{_p.AudibleTracks().Count()} separate tracks";
            ExportSummary.Text = _exGif
                ? $"GIF  ·  {TimelineControl.Fmt(_p.OutputDuration, true)}  ·  {Math.Min(h, 360)}p  ·  loops forever"
                : $"MP4  ·  {w}×{h}  ·  {(_exFps > 0 ? _exFps : _p.Fps):0} fps  ·  {TimelineControl.Fmt(_p.OutputDuration, true)}  ·  {size}  ·  {audio}";
            ExportQualityRow.Visibility = _exTarget > 0 || _exGif ? Visibility.Collapsed : Visibility.Visible;
        }

        private void OnInspTab(object sender, RoutedEventArgs e)
        {
            if (!IsInitialized) return;
            PanelAudio.Visibility = TabAudio.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            PanelEdit.Visibility = TabEdit.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            PanelLook.Visibility = TabLook.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            PanelText.Visibility = TabText.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            PanelFormat.Visibility = TabFormat.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            PanelExport.Visibility = TabExport.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            RenderOverlay();
        }

        private void OnExportTab(object sender, RoutedEventArgs e) => TabExport.IsChecked = true;

        // =====================================================================================
        // Export
        // =====================================================================================
        private async void OnExport(object sender, RoutedEventArgs e)
        {
            if (_p == null) return;
            Pause();
            SaveProject();
            var dir = System.IO.Path.GetDirectoryName(_p.SourcePath)!;
            var name = Paths.SafeFileName(string.IsNullOrWhiteSpace(ExportName.Text) ? "WaveClips export" : ExportName.Text);
            var ext = _exGif ? ".gif" : ".mp4";
            var output = System.IO.Path.Combine(dir, name + ext);
            for (int i = 2; File.Exists(output); i++) output = System.IO.Path.Combine(dir, $"{name} ({i}){ext}");

            var opts = new ExportOptions
            {
                Height = _exHeight,
                Fps = _exFps,
                Quality = (int)ExportQuality.Value,
                TargetMB = _exTarget,
                MixDown = _exMix,
                Gif = _exGif,
                AllowUpscale = _exUpscale,
                OutputPath = output,
            };

            ExportPlan plan;
            try { plan = ExportBuilder.Build(_p, opts); }
            catch (Exception ex) { MessageBox.Show(ex.Message, "Export"); return; }

            _exportCts = new CancellationTokenSource();
            ShowBusy("Exporting", System.IO.Path.GetFileName(output), cancellable: true);
            BusyProgress.IsIndeterminate = false;
            BusyProgress.Value = 0;
            var sw = Stopwatch.StartNew();
            double total = Math.Max(0.1, plan.OutputDuration);
            try
            {
                var r = await FFmpeg.RunAsync(plan.Args, _exportCts.Token, onStdout: line =>
                {
                    if (!line.StartsWith("out_time_us=") && !line.StartsWith("out_time_ms=")) return;
                    if (!long.TryParse(line.Substring(line.IndexOf('=') + 1), out var us) || us < 0) return;
                    double frac = Math.Clamp(us / 1_000_000.0 / total, 0, 1);
                    Dispatcher.BeginInvoke(new Action(() =>
                    {
                        BusyProgress.Value = frac;
                        double eta = frac > 0.02 ? sw.Elapsed.TotalSeconds * (1 - frac) / frac : 0;
                        BusyDetail.Text = $"{frac * 100:0}%  ·  {(eta > 0 ? $"about {TimeSpan.FromSeconds(eta):m\\:ss} left" : "starting…")}";
                    }));
                }, priority: ProcessPriorityClass.BelowNormal, workDir: plan.WorkDir);
                if (!r.Success) throw new Exception(r.StdErrTail);

                _lastExport = output;
                var meta = AppHost.Library.GetMeta(_p.SourcePath);
                AppHost.Library.Add(output, new ClipMeta
                {
                    Game = meta?.Game,
                    Duration = plan.OutputDuration,
                    Tracks = _exMix || _exGif ? new[] { "Mix" } : _p.AudibleTracks().Select(t => t.Name).ToArray(),
                    Created = DateTime.Now,
                });
                long bytes = new FileInfo(output).Length;
                BusyTitle.Text = "Export done!";
                BusyText.Text = $"{System.IO.Path.GetFileName(output)}  ·  {bytes / 1048576.0:0.0} MB  ·  {sw.Elapsed:m\\:ss}";
                BusyDetail.Text = plan.Notes.Trim();
                BusyProgress.Value = 1;
                BusyCopy.Visibility = BusyShow.Visibility = BusyPlay.Visibility = Visibility.Visible;
                BusyCancel.Content = "Close";
                AppHost.Notifier.Show(Capture.NotifyKind.Clip, "Export done!", System.IO.Path.GetFileName(output));
            }
            catch (OperationCanceledException) { HideBusy(); TryDelete(output); }
            catch (Exception ex)
            {
                Log.Error("Export failed", ex);
                TryDelete(output);
                BusyTitle.Text = "Export failed";
                BusyText.Text = ex.Message.Length > 600 ? ex.Message.Substring(ex.Message.Length - 600) : ex.Message;
                BusyCancel.Content = "Close";
            }
            finally
            {
                _exportCts = null;
                try { Directory.Delete(plan.WorkDir, true); } catch { }
            }
        }

        private static void TryDelete(string f) { try { if (File.Exists(f)) File.Delete(f); } catch { } }

        // =====================================================================================
        // Busy overlay
        // =====================================================================================
        private void ShowBusy(string title, string text, bool cancellable)
        {
            BusyRoot.Visibility = Visibility.Visible;
            BusyTitle.Text = title;
            BusyText.Text = text;
            BusyDetail.Text = "";
            BusyProgress.Value = 0;
            BusyCancel.Content = "Cancel";
            BusyCancel.Visibility = cancellable ? Visibility.Visible : Visibility.Collapsed;
            BusyCopy.Visibility = BusyShow.Visibility = BusyPlay.Visibility = Visibility.Collapsed;
        }

        private void HideBusy()
        {
            BusyRoot.Visibility = Visibility.Collapsed;
            BusyProgress.IsIndeterminate = false;
        }

        private void OnBusyCancel(object sender, RoutedEventArgs e)
        {
            if (_exportCts != null) { _exportCts.Cancel(); return; }
            _loadCts?.Cancel();
            HideBusy();
        }

        private void OnBusyCopy(object sender, RoutedEventArgs e)
        {
            if (_lastExport == null) return;
            Clipboard.SetFileDropList(new System.Collections.Specialized.StringCollection { _lastExport });
            BusyDetail.Text = "Copied - paste it into Discord with Ctrl+V";
        }

        private void OnBusyShow(object sender, RoutedEventArgs e)
        {
            if (_lastExport != null) try { Process.Start("explorer.exe", $"/select,\"{_lastExport}\""); } catch { }
        }

        private void OnBusyPlay(object sender, RoutedEventArgs e)
        {
            if (_lastExport != null) try { Process.Start(new ProcessStartInfo(_lastExport) { UseShellExecute = true }); } catch { }
        }

        // =====================================================================================
        // Misc UI
        // =====================================================================================
        private void OnPickFromLibrary(object sender, RoutedEventArgs e) => ((MainWindow)Window.GetWindow(this)).Navigate("Clips");

        private void OnOpenFile(object sender, RoutedEventArgs e)
        {
            var dlg = new Microsoft.Win32.OpenFileDialog
            {
                Filter = "Videos|*.mp4;*.mkv;*.mov;*.webm;*.avi|All files|*.*",
                InitialDirectory = Directory.Exists(AppHost.Settings.ClipFolder) ? AppHost.Settings.ClipFolder : null,
            };
            if (dlg.ShowDialog() == true) Load(dlg.FileName);
        }

        private void OnOpenOther(object sender, RoutedEventArgs e) => OnOpenFile(sender, e);

        private void OnEmptyRecent(object sender, RoutedEventArgs e)
        {
            if (((FrameworkElement)sender).Tag is ClipItem c) Load(c.Path);
        }

        private void OnFit(object sender, RoutedEventArgs e) { Timeline.FitToView(); UpdateZoomSlider(); }

        private bool _zoomSync;
        private void OnTimelineZoom(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (_zoomSync || _p == null) return;
            double fit = Math.Max(2, (Timeline.ActualWidth - TimelineControl.HeaderW - 12) / _p.Duration);
            Timeline.PixelsPerSecond = fit * Math.Pow(200 / 1.0, e.NewValue) ;
            Timeline.EnsureVisible(Timeline.Position);
        }

        private void UpdateZoomSlider()
        {
            if (_p == null) return;
            double fit = Math.Max(2, (Timeline.ActualWidth - TimelineControl.HeaderW - 12) / _p.Duration);
            _zoomSync = true;
            ZoomTimeline.Value = Math.Clamp(Math.Log(Timeline.PixelsPerSecond / fit) / Math.Log(200), 0, 1);
            _zoomSync = false;
            UpdateScrollBar();
        }

        private void UpdateScrollBar()
        {
            TimelineScroll.Maximum = Timeline.MaxScroll;
            TimelineScroll.ViewportSize = Timeline.ViewportSeconds;
            TimelineScroll.LargeChange = Timeline.ViewportSeconds * 0.8;
            TimelineScroll.SmallChange = Timeline.ViewportSeconds * 0.1;
            TimelineScroll.Value = Timeline.Scroll;
            TimelineScroll.Visibility = Timeline.MaxScroll > 0.01 ? Visibility.Visible : Visibility.Hidden;
        }

        private void OnTimelineScroll(object sender, ScrollEventArgs e) => Timeline.Scroll = e.NewValue;

        private void OnKey(object sender, KeyEventArgs e)
        {
            if (_p == null || Keyboard.FocusedElement is TextBox) return;
            bool ctrl = Keyboard.Modifiers.HasFlag(ModifierKeys.Control);
            bool shift = Keyboard.Modifiers.HasFlag(ModifierKeys.Shift);
            switch (e.Key)
            {
                case Key.Space: OnPlayPause(null, null); break;
                case Key.S when !ctrl: OnSplit(null, null); break;
                case Key.Delete: case Key.Back: OnToggleCut(null, null); break;
                case Key.Z when ctrl: UndoRedo(true); break;
                case Key.Y when ctrl: UndoRedo(false); break;
                case Key.Left: Step(-1, shift); break;
                case Key.Right: Step(1, shift); break;
                case Key.I: OnTrimIn(null, null); break;
                case Key.O: OnTrimOut(null, null); break;
                case Key.T: OnAddText(null, null); break;
                case Key.F: OnFxPreview(null, null); break;
                case Key.Home: OnGoStart(null, null); break;
                case Key.End: OnGoEnd(null, null); break;
                default: return;
            }
            e.Handled = true;
        }
    }
}
