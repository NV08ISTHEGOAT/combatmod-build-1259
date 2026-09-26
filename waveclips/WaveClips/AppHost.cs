using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Microsoft.Win32;
using WaveClips.Audio;
using WaveClips.Capture;
using WaveClips.Core;

namespace WaveClips
{
    /// <summary>App-wide state that the UI binds to.</summary>
    public sealed class AppState : ObservableObject
    {
        private GameMatch _game;
        private ImageSource _gameIcon;
        private bool _ffmpegOk;
        private string _ffmpegText = "";
        private bool _encodersReady;

        public GameMatch Game
        {
            get => _game;
            set
            {
                if (!Set(ref _game, value)) return;
                OnPropertyChanged(nameof(GameName));
                OnPropertyChanged(nameof(HasGame));
                GameIcon = LoadExeIcon(value?.ExePath);
            }
        }
        public string GameName => _game?.Profile.Name ?? "No game running";
        public bool HasGame => _game != null;
        public ImageSource GameIcon { get => _gameIcon; private set => Set(ref _gameIcon, value); }
        public bool FfmpegOk { get => _ffmpegOk; set => Set(ref _ffmpegOk, value); }
        public string FfmpegText { get => _ffmpegText; set => Set(ref _ffmpegText, value); }
        public bool EncodersReady { get => _encodersReady; set => Set(ref _encodersReady, value); }

        public void RefreshFfmpeg()
        {
            FfmpegOk = FFmpeg.Available;
            FfmpegText = FFmpeg.Available ? $"FFmpeg {FFmpeg.Version}  ·  {FFmpeg.FfmpegPath}" : "FFmpeg not found";
        }

        private static ImageSource LoadExeIcon(string path)
        {
            if (string.IsNullOrEmpty(path)) return null;
            try
            {
                using var ico = System.Drawing.Icon.ExtractAssociatedIcon(path);
                if (ico == null) return null;
                var src = Imaging.CreateBitmapSourceFromHIcon(ico.Handle, Int32Rect.Empty, BitmapSizeOptions.FromEmptyOptions());
                src.Freeze();
                return src;
            }
            catch { return null; }
        }
    }

    /// <summary>Creates and wires every service. One instance per app.</summary>
    public static class AppHost
    {
        public static AppSettings Settings { get; private set; }
        public static AppState State { get; private set; }
        public static AudioEngine Audio { get; private set; }
        public static GameDetector Games { get; private set; }
        public static ClipLibrary Library { get; private set; }
        public static CaptureEngine Capture { get; private set; }
        public static HotkeyManager Hotkeys { get; private set; }
        public static Notifier Notifier { get; private set; }
        public static TrayIcon Tray { get; private set; }

        private static bool _gameAutoStarted;

        public static void Initialize()
        {
            Settings = AppSettings.Load();
            State = new AppState();
            FFmpeg.Locate(Settings);
            State.RefreshFfmpeg();

            Audio = new AudioEngine(Settings);
            Games = new GameDetector(Settings);
            Library = new ClipLibrary(Settings);
            Capture = new CaptureEngine(Settings, Audio, Library, Games);
            Notifier = new Notifier(Settings);
            Capture.Notify += (kind, title, msg) => Application.Current.Dispatcher.BeginInvoke(new Action(() => Notifier.Show(kind, title, msg)));

            Hotkeys = new HotkeyManager();
            UpdateHotkeys();
            Hotkeys.Pressed += OnHotkey;
            Controls.HotkeyBox.CapturingChanged += capturing => Hotkeys.Suspended = capturing;

            Games.Changed += OnGameChanged;
            Settings.PropertyChanged += OnSettingChanged;
            Settings.Changed += (_, __) => _ = Capture.ApplySettingsAsync();
            FFmpeg.AvailabilityChanged += () => Application.Current?.Dispatcher.BeginInvoke(new Action(State.RefreshFfmpeg));

            Tray = new TrayIcon();
        }

        public static async Task StartAsync()
        {
            try
            {
                await Library.RefreshAsync();
                if (FFmpeg.Available)
                {
                    await Encoders.DetectAsync(Settings);
                    State.EncodersReady = true;
                    if (Settings.AutoStart == AutoStartMode.Always) await Capture.SetBufferEnabledAsync(true);
                }
                else
                {
                    Notifier.Show(NotifyKind.Info, "One more step", "WaveClips needs FFmpeg - install it from Settings → General");
                }
                Games.PollNow();
            }
            catch (Exception ex) { Log.Error("Startup", ex); }
        }

        /// <summary>Call after FFmpeg was installed or its path changed.</summary>
        public static async Task OnFfmpegChangedAsync()
        {
            FFmpeg.Locate(Settings);
            State.RefreshFfmpeg();
            if (!FFmpeg.Available) return;
            await Encoders.DetectAsync(Settings, force: true);
            State.EncodersReady = true;
            await Library.RefreshAsync();
            if (Settings.AutoStart == AutoStartMode.Always && !Capture.BufferEnabled) await Capture.SetBufferEnabledAsync(true);
        }

        private static void OnSettingChanged(object sender, PropertyChangedEventArgs e)
        {
            switch (e.PropertyName)
            {
                case nameof(AppSettings.ClipHotkey):
                case nameof(AppSettings.RecordHotkey):
                case nameof(AppSettings.BufferHotkey):
                    UpdateHotkeys();
                    break;
                case nameof(AppSettings.StartWithWindows):
                    SetStartWithWindows(Settings.StartWithWindows);
                    break;
                case nameof(AppSettings.ClipFolder):
                    _ = Library.RefreshAsync();
                    break;
            }
        }

        private static void UpdateHotkeys()
        {
            Hotkeys.SetBindings(new[]
            {
                new KeyValuePair<Hotkey, HotkeyAction>(Settings.ClipHotkey, HotkeyAction.SaveClip),
                new KeyValuePair<Hotkey, HotkeyAction>(Settings.RecordHotkey, HotkeyAction.ToggleRecording),
                new KeyValuePair<Hotkey, HotkeyAction>(Settings.BufferHotkey, HotkeyAction.ToggleBuffer),
            });
        }

        private static void OnHotkey(HotkeyAction action)
        {
            switch (action)
            {
                case HotkeyAction.SaveClip: _ = Capture.SaveClipAsync(); break;
                case HotkeyAction.ToggleRecording: _ = Capture.ToggleRecordingAsync(); break;
                case HotkeyAction.ToggleBuffer:
                    _ = Capture.ToggleBufferAsync().ContinueWith(_ =>
                        Application.Current.Dispatcher.BeginInvoke(new Action(() =>
                            Notifier.Show(NotifyKind.Info, Capture.BufferEnabled ? "Replay buffer ON" : "Replay buffer OFF", ""))));
                    break;
            }
        }

        private static void OnGameChanged(GameMatch match)
        {
            Audio.Game = match;
            Application.Current?.Dispatcher.BeginInvoke(new Action(() =>
            {
                State.Game = match;
                if (Settings.AutoStart != AutoStartMode.WhenGameRunning || !FFmpeg.Available) return;
                if (match != null && !Capture.BufferEnabled)
                {
                    _gameAutoStarted = true;
                    _ = Capture.SetBufferEnabledAsync(true);
                    Notifier.Show(NotifyKind.Info, $"{match.Profile.Name} detected", "Replay buffer is on");
                }
                else if (match == null && _gameAutoStarted)
                {
                    _gameAutoStarted = false;
                    _ = Capture.SetBufferEnabledAsync(false);
                }
            }));
        }

        private static void SetStartWithWindows(bool on)
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", true);
                if (key == null) return;
                if (on) key.SetValue("WaveClips", $"\"{Environment.ProcessPath}\" --minimized");
                else key.DeleteValue("WaveClips", false);
            }
            catch (Exception ex) { Log.Error("Start with Windows", ex); }
        }

        public static async Task ShutdownAsync()
        {
            Log.Info("Shutting down");
            Hotkeys?.Dispose();
            Games?.Dispose();
            if (Capture != null) await Capture.ShutdownAsync();
            Audio?.Dispose();
            Tray?.Dispose();
            Settings?.Save();
        }
    }
}
