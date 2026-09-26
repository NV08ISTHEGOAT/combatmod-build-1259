using System;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Linq;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using WaveClips.Capture;
using WaveClips.Core;

namespace WaveClips.Views
{
    public sealed class TrackRow : ObservableObject
    {
        private string _status = "";
        private double _level;
        public AudioTrackConfig Config { get; init; }
        public string Name => Config.Name;
        public Brush Brush { get; init; }
        public string Status { get => _status; set => Set(ref _status, value); }
        public double Level { get => _level; set => Set(ref _level, value); }

        public static Brush BrushFor(string hex)
        {
            try { var b = (Brush)new BrushConverter().ConvertFromString(hex); b.Freeze(); return b; }
            catch { return Brushes.Cyan; }
        }
    }

    public partial class HomeView : UserControl, IPage
    {
        private readonly DispatcherTimer _timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(40) };
        private readonly ObservableCollection<TrackRow> _rows = new ObservableCollection<TrackRow>();
        private int _ticks;

        public HomeView()
        {
            InitializeComponent();
            DataContext = AppHost.Capture;
            GameTile.DataContext = AppHost.State;
            TrackList.ItemsSource = _rows;
            _timer.Tick += (_, __) => OnTick();
            AppHost.Capture.PropertyChanged += (_, e) => Dispatcher.BeginInvoke(new Action(UpdateHero));
            AppHost.State.PropertyChanged += (_, __) => UpdateHero();
            AppHost.Library.Items.CollectionChanged += (_, __) => UpdateRecent();
            AppHost.Settings.AudioTracks.CollectionChanged += (_, __) => BuildRows();
        }

        public void OnShown()
        {
            BuildRows();
            UpdateHero();
            UpdateRecent();
            UpdateTiles();
            _timer.Start();
        }

        public void OnHidden() => _timer.Stop();

        private void BuildRows()
        {
            _rows.Clear();
            foreach (var t in AppHost.Settings.AudioTracks.Where(t => t.Enabled))
                _rows.Add(new TrackRow { Config = t, Brush = TrackRow.BrushFor(t.Color), Status = "Idle - starts with the buffer" });
        }

        private void UpdateHero()
        {
            var c = AppHost.Capture;
            BigState.Text = c.State switch
            {
                BufferState.Running => c.BufferEnabled ? "ON" : "REC ONLY",
                BufferState.Starting => "STARTING",
                BufferState.Error => "ERROR",
                _ => "OFF",
            };
            BigState.Foreground = (Brush)FindResource(c.State == BufferState.Error ? "Danger" : c.State == BufferState.Running ? "Accent" : "TextDim");
            BufferSwitch.IsChecked = c.BufferEnabled;
            HeroWaves.Intensity = c.IsRecording ? 1.8 : c.State == BufferState.Running ? 1.1 : 0.4;
            HeroWaves.Speed = c.IsRecording ? 2.4 : 1;
            RecordLabel.Text = c.IsRecording ? "STOP  ·  " + c.RecordingTime : "START RECORDING";
            var h = AppHost.Settings.ClipHotkey;
            ClipHint.Text = h == null || h.IsEmpty ? "no hotkey set" : $"or press {h}";
            InstallFfmpegButton.Visibility = FFmpeg.Available ? Visibility.Collapsed : Visibility.Visible;
            GameSub.Text = AppHost.State.HasGame ? "Game audio track follows this game" : "Watching for your games";
            GameIcon.Visibility = AppHost.State.GameIcon != null ? Visibility.Visible : Visibility.Collapsed;
        }

        private void UpdateRecent()
        {
            var recent = AppHost.Library.Items.Take(5).ToList();
            RecentList.ItemsSource = recent;
            NoClips.Visibility = recent.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        }

        private void UpdateTiles()
        {
            var s = AppHost.Settings;
            var c = AppHost.Capture;
            var mon = Monitors.Find(Monitors.Enumerate(), s.MonitorId);
            int outH = s.OutputHeight > 0 && mon != null && s.OutputHeight < mon.Height ? s.OutputHeight : mon?.Height ?? 0;
            CaptureMain.Text = mon == null ? "No monitor" : $"{outH}p  ·  {s.Fps} fps";
            CaptureSub.Text = mon == null ? "" : $"Monitor {mon.Number}: {mon.FriendlyName}";
            var enc = Encoders.Resolve(s.Encoder);
            EncoderMain.Text = $"{enc.Vendor} {enc.Codec}";
            EncoderSub.Text = c.State == BufferState.Running ? $"{c.Fps:0} fps  ·  {c.DroppedFrames} dropped" : c.PipelineText == "" ? "Idle" : c.PipelineText;
            BufferMain.Text = $"{s.ClipSeconds} s";
            BufferSub.Text = c.State == BufferState.Running ? $"{c.BufferSizeText} on disk" : "Clip length";
        }

        private void OnTick()
        {
            var sources = AppHost.Audio.Sources;
            foreach (var row in _rows)
            {
                var src = sources.FirstOrDefault(x => x.Config == row.Config);
                if (src == null) { row.Level = 0; continue; }
                row.Level = src.TakePeak();
                row.Status = src.Status;
            }
            if (++_ticks % 12 == 0) UpdateTiles();
        }

        private async void OnBufferSwitch(object sender, RoutedEventArgs e)
        {
            await AppHost.Capture.SetBufferEnabledAsync(BufferSwitch.IsChecked == true);
            UpdateHero();
        }

        private async void OnClip(object sender, RoutedEventArgs e) => await AppHost.Capture.SaveClipAsync();
        private async void OnRecord(object sender, RoutedEventArgs e) => await AppHost.Capture.ToggleRecordingAsync();
        private void OnSeeAll(object sender, RoutedEventArgs e) => ((MainWindow)Window.GetWindow(this)).Navigate("Clips");
        private void OnConfigureAudio(object sender, RoutedEventArgs e)
        {
            var w = (MainWindow)Window.GetWindow(this);
            w.Navigate("Settings");
            SettingsView.Current?.ShowSection("Audio");
        }

        private void OnInstallFfmpeg(object sender, RoutedEventArgs e)
        {
            var w = (MainWindow)Window.GetWindow(this);
            w.Navigate("Settings");
            SettingsView.Current?.ShowSection("General");
        }

        private void OnRecentClick(object sender, RoutedEventArgs e)
        {
            if (((FrameworkElement)sender).Tag is ClipItem item)
                ((MainWindow)Window.GetWindow(this)).OpenInEditor(item.Path);
        }
    }
}
