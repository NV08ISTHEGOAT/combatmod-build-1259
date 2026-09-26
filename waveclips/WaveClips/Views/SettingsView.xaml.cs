using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Threading;
using NAudio.CoreAudioApi;
using WaveClips.Capture;
using WaveClips.Core;

namespace WaveClips.Views
{
    public sealed class Option
    {
        public Option(object value, string label) { Value = value; Label = label; }
        public object Value { get; }
        public string Label { get; }
        public override string ToString() => Label;
    }

    /// <summary>Row in the audio track editor.</summary>
    public sealed class TrackVm : ObservableObject
    {
        private double _level;
        private string _status = "";
        public AudioTrackConfig Config { get; }
        public Brush Brush { get; }
        public IReadOnlyList<Option> SourceOptions { get; } = new[]
        {
            new Option(AudioSourceKind.Game, "Game (detected game)"),
            new Option(AudioSourceKind.App, "App (e.g. Discord)"),
            new Option(AudioSourceKind.Microphone, "Microphone"),
            new Option(AudioSourceKind.SystemAll, "All desktop audio"),
            new Option(AudioSourceKind.SystemExceptApp, "All audio except an app"),
        };
        public IReadOnlyList<Option> Devices { get; }
        public double Level { get => _level; set => Set(ref _level, value); }
        public string Status { get => _status; set => Set(ref _status, value); }
        public string VolumeText => $"{Config.Volume * 100:0}%";

        public TrackVm(AudioTrackConfig cfg, IReadOnlyList<Option> devices)
        {
            Config = cfg;
            Brush = TrackRow.BrushFor(cfg.Color);
            Devices = devices;
            cfg.PropertyChanged += (_, e) => { if (e.PropertyName == nameof(AudioTrackConfig.Volume)) OnPropertyChanged(nameof(VolumeText)); };
        }
    }

    public partial class SettingsView : UserControl, IPage
    {
        public static SettingsView Current { get; private set; }

        private readonly AppSettings _s = AppHost.Settings;
        private readonly ObservableCollection<TrackVm> _tracks = new ObservableCollection<TrackVm>();
        private readonly DispatcherTimer _meterTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(40) };
        private IReadOnlyList<MonitorInfo> _monitors = Array.Empty<MonitorInfo>();
        private bool _testingAudio;
        private CancellationTokenSource _downloadCts;
        private static readonly string[] TrackColors = { "#00E5FF", "#8C9EFF", "#3DFFB0", "#FFC940", "#FF6E9C", "#B388FF" };

        public SettingsView()
        {
            InitializeComponent();
            Current = this;
            DataContext = _s;
            TracksList.ItemsSource = _tracks;
            _meterTimer.Tick += (_, __) => UpdateMeters();
            _s.PropertyChanged += OnSettingChanged;
            AppHost.State.PropertyChanged += (_, e) =>
            {
                if (e.PropertyName is nameof(AppState.FfmpegText) or nameof(AppState.EncodersReady)) { UpdateFfmpeg(); BuildEncoders(); }
            };
            BuildStaticChips();
        }

        public void OnShown()
        {
            RefreshMonitors();
            BuildEncoders();
            BuildTracks();
            UpdateFfmpeg();
            UpdateLabels();
            _meterTimer.Start();
        }

        public void OnHidden()
        {
            _meterTimer.Stop();
            if (_testingAudio) { _testingAudio = false; TestAudio.IsChecked = false; AppHost.Audio.Release(); }
        }

        public void ShowSection(string name)
        {
            var tab = name switch { "Audio" => TabAudio, "General" => TabGeneral, "Hotkeys" => TabHotkeys, "Clipping" => TabClipping, _ => TabCapture };
            tab.IsChecked = true;
        }

        private void OnTab(object sender, RoutedEventArgs e)
        {
            if (!IsInitialized) return;
            CapturePage.Visibility = TabCapture.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            ClippingPage.Visibility = TabClipping.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            HotkeysPage.Visibility = TabHotkeys.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            AudioPage.Visibility = TabAudio.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            GeneralPage.Visibility = TabGeneral.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            Scroller.ScrollToTop();
        }

        // ------------------------------------------------------------------ chips
        private static int _group;

        /// <summary>Fills a WrapPanel with single-choice chips.</summary>
        private static void Chips(Panel host, IEnumerable<Option> options, Func<object> get, Action<object> set)
        {
            host.Children.Clear();
            var group = "chips" + (++_group);
            var current = get();
            foreach (var o in options)
            {
                var rb = new RadioButton
                {
                    Content = o.Label,
                    Tag = o.Value,
                    GroupName = group,
                    Style = (Style)Application.Current.FindResource("Chip"),
                    IsChecked = Equals(o.Value, current),
                };
                rb.Checked += (_, __) => set(rb.Tag);
                host.Children.Add(rb);
            }
        }

        private static void SyncChips(Panel host, object value)
        {
            foreach (var rb in host.Children.OfType<RadioButton>())
                if (Equals(rb.Tag, value) && rb.IsChecked != true) rb.IsChecked = true;
                else if (!Equals(rb.Tag, value) && rb.IsChecked == true) rb.IsChecked = false;
        }

        private void BuildStaticChips()
        {
            Chips(PresetChips, new[]
            {
                new Option(1, "Low (small files)"), new Option(2, "Medium"), new Option(3, "High"), new Option(4, "Ultra"),
            }, () => null, v =>
            {
                switch ((int)v)
                {
                    case 1: _s.RateControl = RateControl.ConstantQuality; _s.Quality = 45; _s.EncoderSpeed = EncoderSpeed.Fast; break;
                    case 2: _s.RateControl = RateControl.ConstantQuality; _s.Quality = 62; _s.EncoderSpeed = EncoderSpeed.Balanced; break;
                    case 3: _s.RateControl = RateControl.ConstantQuality; _s.Quality = 75; _s.EncoderSpeed = EncoderSpeed.Balanced; break;
                    case 4: _s.RateControl = RateControl.ConstantQuality; _s.Quality = 88; _s.EncoderSpeed = EncoderSpeed.Quality; break;
                }
            });
            Chips(RateChips, new[] { new Option(RateControl.ConstantQuality, "Constant quality (recommended)"), new Option(RateControl.Bitrate, "Fixed bitrate") },
                () => _s.RateControl, v => _s.RateControl = (RateControl)v);
            Chips(SpeedChips, new[] { new Option(EncoderSpeed.Fast, "Fast (least GPU/CPU)"), new Option(EncoderSpeed.Balanced, "Balanced"), new Option(EncoderSpeed.Quality, "Best quality") },
                () => _s.EncoderSpeed, v => _s.EncoderSpeed = (EncoderSpeed)v);
            Chips(MethodChips, new[] { new Option(CaptureMethod.DesktopDuplication, "Desktop Duplication (GPU, recommended)"), new Option(CaptureMethod.Gdi, "GDI (compatibility)") },
                () => _s.CaptureMethod, v => _s.CaptureMethod = (CaptureMethod)v);
            Chips(ClipLenChips, new[] { 15, 30, 60, 90, 120, 180, 300 }.Select(x => new Option(x, x < 60 ? $"{x} s" : $"{x / 60.0:0.#} min")),
                () => _s.ClipSeconds, v => _s.ClipSeconds = (int)v);
            Chips(AutoStartChips, new[]
            {
                new Option(AutoStartMode.Always, "Always (when WaveClips starts)"),
                new Option(AutoStartMode.WhenGameRunning, "When a game is running"),
                new Option(AutoStartMode.Manual, "Only when I turn it on"),
            }, () => _s.AutoStart, v => _s.AutoStart = (AutoStartMode)v);
            Chips(CornerChips, new[]
            {
                new Option(OverlayCorner.TopLeft, "Top left"), new Option(OverlayCorner.TopRight, "Top right"),
                new Option(OverlayCorner.BottomLeft, "Bottom left"), new Option(OverlayCorner.BottomRight, "Bottom right"),
            }, () => _s.OverlayCorner, v => _s.OverlayCorner = (OverlayCorner)v);
            Chips(AudioBitrateChips, new[] { 128, 160, 192, 256, 320 }.Select(x => new Option(x, $"{x} kbps")),
                () => _s.AudioBitrateKbps, v => _s.AudioBitrateKbps = (int)v);
        }

        private void BuildMonitorDependentChips()
        {
            var mon = Monitors.Find(_monitors, _s.MonitorId);
            int hz = mon?.RefreshRate ?? 60;
            var fps = new List<int> { 30, 60, 90, 120, 144, 165, 240 }.Where(f => f <= Math.Max(hz, 60)).ToList();
            if (!fps.Contains(_s.Fps)) fps.Add(_s.Fps);
            Chips(FpsChips, fps.OrderBy(f => f).Select(f => new Option(f, f == hz ? $"{f} (monitor)" : f.ToString())), () => _s.Fps, v => _s.Fps = (int)v);

            var res = new List<Option> { new Option(0, mon != null ? $"Native ({mon.Height}p)" : "Native") };
            foreach (var h in new[] { 2160, 1440, 1080, 900, 720 })
                if (mon == null || h < mon.Height) res.Add(new Option(h, h + "p"));
            Chips(ResChips, res, () => _s.OutputHeight, v => _s.OutputHeight = (int)v);
        }

        // ------------------------------------------------------------------ capture
        private void RefreshMonitors()
        {
            _monitors = Monitors.Enumerate();
            MonitorPicker.Monitors = _monitors;
            if (string.IsNullOrEmpty(_s.MonitorId) || _monitors.All(m => m.DeviceName != _s.MonitorId))
            {
                var primary = _monitors.FirstOrDefault(m => m.IsPrimary) ?? _monitors.FirstOrDefault();
                if (primary != null) _s.MonitorId = primary.DeviceName;
            }
            UpdateMonitorText();
            BuildMonitorDependentChips();
        }

        private void OnRefreshMonitors(object sender, RoutedEventArgs e) => RefreshMonitors();

        private void UpdateMonitorText()
        {
            var m = Monitors.Find(_monitors, _s.MonitorId);
            MonitorTitle.Text = m?.Title ?? "No monitor found";
            MonitorDetails.Text = m?.Details ?? "";
            MonitorGpu.Text = m?.AdapterName ?? "";
        }

        private void BuildEncoders()
        {
            var items = new List<Option> { new Option("auto", $"Auto  ·  {Encoders.Resolve("auto").Label}") };
            items.AddRange(Encoders.Available.Where(e => e.CanCapture).Select(e => new Option(e.Id, e.Label)));
            EncoderBox.ItemsSource = items;
            EncoderBox.SelectedValue = items.Any(i => (string)i.Value == _s.Encoder) ? _s.Encoder : "auto";
            EncoderNote.Text = !FFmpeg.Available ? "Install FFmpeg first (General tab)."
                : !AppHost.State.EncodersReady ? "Testing which encoders your PC supports…"
                : $"{Encoders.Available.Count(e => e.IsHardware)} hardware encoder(s) found. GPU encoders barely affect your FPS in game.";
        }

        private void OnEncoderChanged(object sender, SelectionChangedEventArgs e)
        {
            if (EncoderBox.SelectedValue is string id && id != _s.Encoder) _s.Encoder = id;
        }

        private async void OnRetestEncoders(object sender, RoutedEventArgs e)
        {
            if (!FFmpeg.Available) return;
            EncoderNote.Text = "Testing encoders…";
            await Encoders.DetectAsync(_s, force: true);
            BuildEncoders();
        }

        private void OnQualityChanged(object sender, RoutedPropertyChangedEventArgs<double> e) => UpdateLabels();
        private void OnBitrateChanged(object sender, RoutedPropertyChangedEventArgs<double> e) => UpdateLabels();
        private void OnClipLenChanged(object sender, RoutedPropertyChangedEventArgs<double> e) => UpdateLabels();
        private void OnSyncChanged(object sender, RoutedPropertyChangedEventArgs<double> e) => UpdateLabels();

        private void UpdateLabels()
        {
            if (!IsInitialized) return;
            string word = _s.Quality >= 85 ? "Ultra" : _s.Quality >= 70 ? "High" : _s.Quality >= 50 ? "Medium" : "Low";
            QualityValue.Text = $"{_s.Quality}  ·  {word}";
            double mbPerMin = _s.BitrateMbps * 60 / 8.0;
            BitrateValue.Text = $"{_s.BitrateMbps} Mbps  ·  ~{mbPerMin:0} MB/min";
            QualityBox.Visibility = _s.RateControl == RateControl.ConstantQuality ? Visibility.Visible : Visibility.Collapsed;
            BitrateBox.Visibility = _s.RateControl == RateControl.Bitrate ? Visibility.Visible : Visibility.Collapsed;
            ClipLenValue.Text = _s.ClipSeconds < 60 ? $"{_s.ClipSeconds} seconds" : $"{_s.ClipSeconds / 60} min {(_s.ClipSeconds % 60 > 0 ? _s.ClipSeconds % 60 + " s" : "")}";
            SyncValue.Text = _s.AudioSyncOffsetMs == 0 ? "0 ms" : $"{_s.AudioSyncOffsetMs:+0;-0} ms ({(_s.AudioSyncOffsetMs > 0 ? "audio later" : "audio earlier")})";
        }

        private void OnSettingChanged(object sender, PropertyChangedEventArgs e)
        {
            switch (e.PropertyName)
            {
                case nameof(AppSettings.MonitorId): UpdateMonitorText(); BuildMonitorDependentChips(); break;
                case nameof(AppSettings.RateControl): SyncChips(RateChips, _s.RateControl); break;
                case nameof(AppSettings.EncoderSpeed): SyncChips(SpeedChips, _s.EncoderSpeed); break;
                case nameof(AppSettings.ClipSeconds): SyncChips(ClipLenChips, _s.ClipSeconds); break;
                case nameof(AppSettings.Fps): SyncChips(FpsChips, _s.Fps); break;
            }
            UpdateLabels();
        }

        // ------------------------------------------------------------------ clipping
        private static string PickFolder(string start)
        {
            using var dlg = new System.Windows.Forms.FolderBrowserDialog { SelectedPath = start, UseDescriptionForTitle = true, Description = "Choose a folder" };
            return dlg.ShowDialog() == System.Windows.Forms.DialogResult.OK ? dlg.SelectedPath : null;
        }

        private void OnBrowseClipFolder(object sender, RoutedEventArgs e)
        {
            var f = PickFolder(_s.ClipFolder);
            if (f != null) _s.ClipFolder = f;
        }

        private void OnBrowseBufferFolder(object sender, RoutedEventArgs e)
        {
            var f = PickFolder(_s.BufferFolder);
            if (f != null) _s.BufferFolder = f;
        }

        private void OnOpenClipFolder(object sender, RoutedEventArgs e) => OpenFolder(_s.ClipFolder);

        private static void OpenFolder(string path)
        {
            try { Directory.CreateDirectory(path); Process.Start("explorer.exe", $"\"{path}\""); } catch { }
        }

        private void OnTestToast(object sender, RoutedEventArgs e) =>
            AppHost.Notifier.Show(NotifyKind.Clip, "Clip saved!", $"{AppHost.State.GameName}  ·  0:{_s.ClipSeconds % 60:00}  ·  test popup");

        // ------------------------------------------------------------------ audio
        private IReadOnlyList<Option> _devices;

        private IReadOnlyList<Option> MicDevices()
        {
            if (_devices != null) return _devices;
            var list = new List<Option> { new Option("", "Windows default microphone") };
            try
            {
                using var en = new MMDeviceEnumerator();
                foreach (var d in en.EnumerateAudioEndPoints(DataFlow.Capture, DeviceState.Active))
                {
                    list.Add(new Option(d.ID, d.FriendlyName));
                    d.Dispose();
                }
            }
            catch (Exception ex) { Log.Warn("Mic list: " + ex.Message); }
            return _devices = list;
        }

        private void BuildTracks()
        {
            _devices = null;
            var devices = MicDevices();
            _tracks.Clear();
            foreach (var t in _s.AudioTracks) _tracks.Add(new TrackVm(t, devices));
        }

        private void OnAddTrack(object sender, RoutedEventArgs e)
        {
            if (_s.AudioTracks.Count >= 6) { MessageBox.Show("Up to 6 tracks are supported.", "WaveClips"); return; }
            var cfg = new AudioTrackConfig
            {
                Name = "New track",
                Source = AudioSourceKind.App,
                Color = TrackColors[_s.AudioTracks.Count % TrackColors.Length],
            };
            _s.AudioTracks.Add(cfg);
            _tracks.Add(new TrackVm(cfg, MicDevices()));
            AppHost.Audio.ReloadTracks();
        }

        private void OnRemoveTrack(object sender, RoutedEventArgs e)
        {
            if (((FrameworkElement)sender).Tag is not TrackVm vm) return;
            if (MessageBox.Show($"Remove the track \"{vm.Config.Name}\"?", "WaveClips", MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
            _s.AudioTracks.Remove(vm.Config);
            _tracks.Remove(vm);
            AppHost.Audio.ReloadTracks();
        }

        private void OnTrackToggled(object sender, RoutedEventArgs e) => AppHost.Audio.ReloadTracks();

        private void OnPickApp(object sender, RoutedEventArgs e)
        {
            if (((FrameworkElement)sender).Tag is not TrackVm vm) return;
            var menu = new ContextMenu { PlacementTarget = (UIElement)sender };
            var apps = new SortedDictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (var p in Process.GetProcesses())
            {
                try
                {
                    if (p.MainWindowHandle != IntPtr.Zero && p.Id != Environment.ProcessId && !apps.ContainsKey(p.ProcessName))
                        apps[p.ProcessName] = string.IsNullOrWhiteSpace(p.MainWindowTitle) ? p.ProcessName : p.MainWindowTitle;
                }
                catch { }
                finally { p.Dispose(); }
            }
            foreach (var preset in new[] { ("Discord", "Discord.exe, DiscordPTB.exe, DiscordCanary.exe"), ("Spotify", "Spotify.exe"), ("Chrome", "chrome.exe"), ("Firefox", "firefox.exe"), ("TeamSpeak", "ts3client_win64.exe, TeamSpeak.exe") })
            {
                var item = new MenuItem { Header = "★ " + preset.Item1 };
                item.Click += (_, __) => vm.Config.AppExe = preset.Item2;
                menu.Items.Add(item);
            }
            menu.Items.Add(new Separator());
            foreach (var kv in apps)
            {
                var title = kv.Value.Length > 50 ? kv.Value.Substring(0, 50) + "…" : kv.Value;
                var item = new MenuItem { Header = $"{kv.Key}.exe  —  {title}" };
                item.Click += (_, __) => vm.Config.AppExe = kv.Key + ".exe";
                menu.Items.Add(item);
            }
            menu.MaxHeight = 480;
            menu.IsOpen = true;
        }

        private void OnTestAudio(object sender, RoutedEventArgs e)
        {
            bool on = TestAudio.IsChecked == true;
            if (on == _testingAudio) return;
            _testingAudio = on;
            if (on) AppHost.Audio.Acquire(); else AppHost.Audio.Release();
        }

        private void UpdateMeters()
        {
            var sources = AppHost.Audio.Sources;
            foreach (var vm in _tracks)
            {
                var src = sources.FirstOrDefault(s => s.Config == vm.Config);
                vm.Level = src?.TakePeak() ?? 0;
                vm.Status = !vm.Config.Enabled ? "Off - not recorded"
                    : src != null ? src.Status
                    : "Idle - press 'Live test' or start the replay buffer to see levels";
            }
        }

        // ------------------------------------------------------------------ general
        private void UpdateFfmpeg()
        {
            bool ok = FFmpeg.Available;
            FfmpegDot.Fill = (Brush)FindResource(ok ? "Success" : "Danger");
            FfmpegStatus.Text = ok ? AppHost.State.FfmpegText : "FFmpeg not found - capture can't start until it's installed";
            InstallFfmpeg.Content = ok ? "Re-download FFmpeg" : "Download & install FFmpeg";
        }

        private async void OnInstallFfmpeg(object sender, RoutedEventArgs e)
        {
            if (_downloadCts != null) { _downloadCts.Cancel(); return; }
            _downloadCts = new CancellationTokenSource();
            InstallFfmpeg.Content = "Cancel";
            FfmpegProgress.Visibility = Visibility.Visible;
            var progress = new Progress<(double fraction, string status)>(p =>
            {
                FfmpegProgress.Value = p.fraction;
                FfmpegProgressText.Text = p.status;
            });
            try
            {
                await FFmpeg.DownloadAsync(progress, _downloadCts.Token);
                _s.FfmpegPath = "";
                await AppHost.OnFfmpegChangedAsync();
                FfmpegProgressText.Text = "Installed! Capture is ready.";
            }
            catch (OperationCanceledException) { FfmpegProgressText.Text = "Download cancelled."; }
            catch (Exception ex) { FfmpegProgressText.Text = ex.Message; }
            finally
            {
                _downloadCts = null;
                FfmpegProgress.Visibility = Visibility.Collapsed;
                UpdateFfmpeg();
                BuildEncoders();
            }
        }

        private async void OnBrowseFfmpeg(object sender, RoutedEventArgs e)
        {
            var dlg = new Microsoft.Win32.OpenFileDialog { Filter = "ffmpeg.exe|ffmpeg.exe|Programs|*.exe", Title = "Find ffmpeg.exe" };
            if (dlg.ShowDialog() != true) return;
            _s.FfmpegPath = dlg.FileName;
            await AppHost.OnFfmpegChangedAsync();
            UpdateFfmpeg();
            BuildEncoders();
        }

        private void OnOpenLog(object sender, RoutedEventArgs e)
        {
            try { Process.Start(new ProcessStartInfo(Paths.LogFile) { UseShellExecute = true }); } catch { OpenFolder(Paths.LocalData); }
        }

        private void OnOpenData(object sender, RoutedEventArgs e) => OpenFolder(Paths.LocalData);
        private void OnQuit(object sender, RoutedEventArgs e) => App.Quit();
    }
}
