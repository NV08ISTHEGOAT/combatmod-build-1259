using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Collections.Specialized;
using System.ComponentModel;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Windows.Input;
using System.Windows.Threading;

namespace WaveClips.Core
{
    /// <summary>All user settings. Persisted as JSON in %AppData%\WaveClips\settings.json and saved automatically.</summary>
    public sealed class AppSettings : ObservableObject
    {
        // ---------------- Capture / monitor ----------------
        private string _monitorId = "";
        private CaptureMethod _captureMethod = CaptureMethod.DesktopDuplication;
        private int _fps = 60;
        private int _outputHeight;
        private string _encoder = "auto";
        private RateControl _rateControl = RateControl.ConstantQuality;
        private int _quality = 75;
        private int _bitrateMbps = 40;
        private EncoderSpeed _encoderSpeed = EncoderSpeed.Balanced;
        private bool _captureCursor = true;

        /// <summary>GDI device name of the monitor to record (e.g. \\.\DISPLAY1). Empty = primary monitor.</summary>
        public string MonitorId { get => _monitorId; set => Set(ref _monitorId, value); }
        public CaptureMethod CaptureMethod { get => _captureMethod; set => Set(ref _captureMethod, value); }
        public int Fps { get => _fps; set => Set(ref _fps, Math.Clamp(value, 10, 360)); }
        /// <summary>Output height in pixels, 0 = native monitor resolution.</summary>
        public int OutputHeight { get => _outputHeight; set => Set(ref _outputHeight, value); }
        /// <summary>FFmpeg encoder name or "auto".</summary>
        public string Encoder { get => _encoder; set => Set(ref _encoder, value); }
        public RateControl RateControl { get => _rateControl; set => Set(ref _rateControl, value); }
        /// <summary>1..100, higher = better. Used with ConstantQuality.</summary>
        public int Quality { get => _quality; set => Set(ref _quality, Math.Clamp(value, 1, 100)); }
        public int BitrateMbps { get => _bitrateMbps; set => Set(ref _bitrateMbps, Math.Clamp(value, 1, 300)); }
        public EncoderSpeed EncoderSpeed { get => _encoderSpeed; set => Set(ref _encoderSpeed, value); }
        public bool CaptureCursor { get => _captureCursor; set => Set(ref _captureCursor, value); }

        // ---------------- Clipping ----------------
        private int _clipSeconds = 60;
        private AutoStartMode _autoStart = AutoStartMode.Always;
        private string _clipFolder = Paths.DefaultClipFolder;
        private string _bufferFolder = Paths.DefaultBufferFolder;
        private bool _clipSound = true;
        private bool _clipOverlay = true;
        private OverlayCorner _overlayCorner = OverlayCorner.TopRight;
        private bool _organizeByGame = true;

        /// <summary>How many seconds before the hotkey press a clip contains.</summary>
        public int ClipSeconds { get => _clipSeconds; set => Set(ref _clipSeconds, Math.Clamp(value, 5, 600)); }
        public AutoStartMode AutoStart { get => _autoStart; set => Set(ref _autoStart, value); }
        public string ClipFolder { get => _clipFolder; set => Set(ref _clipFolder, value); }
        public string BufferFolder { get => _bufferFolder; set => Set(ref _bufferFolder, value); }
        public bool ClipSound { get => _clipSound; set => Set(ref _clipSound, value); }
        public bool ClipOverlay { get => _clipOverlay; set => Set(ref _clipOverlay, value); }
        public OverlayCorner OverlayCorner { get => _overlayCorner; set => Set(ref _overlayCorner, value); }
        public bool OrganizeByGame { get => _organizeByGame; set => Set(ref _organizeByGame, value); }

        // ---------------- Hotkeys ----------------
        private Hotkey _clipHotkey = Hotkey.Key(Key.F8);
        private Hotkey _recordHotkey = Hotkey.Key(Key.F9);
        private Hotkey _bufferHotkey = Hotkey.Key(Key.F10, alt: true);

        public Hotkey ClipHotkey { get => _clipHotkey; set => Set(ref _clipHotkey, value); }
        public Hotkey RecordHotkey { get => _recordHotkey; set => Set(ref _recordHotkey, value); }
        public Hotkey BufferHotkey { get => _bufferHotkey; set => Set(ref _bufferHotkey, value); }

        // ---------------- Audio ----------------
        private bool _mixTrack = true;
        private int _audioBitrateKbps = 192;
        private int _audioSyncOffsetMs;

        public ObservableCollection<AudioTrackConfig> AudioTracks { get; set; } = new ObservableCollection<AudioTrackConfig>();
        /// <summary>Adds a first "Mix" track containing all tracks so normal players (and Discord) hear everything.</summary>
        public bool MixTrack { get => _mixTrack; set => Set(ref _mixTrack, value); }
        public int AudioBitrateKbps { get => _audioBitrateKbps; set => Set(ref _audioBitrateKbps, value); }
        /// <summary>Positive = delay audio relative to video.</summary>
        public int AudioSyncOffsetMs { get => _audioSyncOffsetMs; set => Set(ref _audioSyncOffsetMs, Math.Clamp(value, -1000, 1000)); }

        // ---------------- Games ----------------
        public ObservableCollection<GameProfile> Games { get; set; } = new ObservableCollection<GameProfile>();

        // ---------------- General ----------------
        private bool _startWithWindows;
        private bool _startMinimized;
        private bool _closeToTray = true;
        private string _ffmpegPath = "";

        public bool StartWithWindows { get => _startWithWindows; set => Set(ref _startWithWindows, value); }
        public bool StartMinimized { get => _startMinimized; set => Set(ref _startMinimized, value); }
        public bool CloseToTray { get => _closeToTray; set => Set(ref _closeToTray, value); }
        /// <summary>Custom ffmpeg.exe path ("" = auto detect).</summary>
        public string FfmpegPath { get => _ffmpegPath; set => Set(ref _ffmpegPath, value); }

        /// <summary>Encoders that passed the self test, cached per FFmpeg build.</summary>
        public string[] EncoderCache { get; set; } = Array.Empty<string>();
        public string EncoderCacheKey { get; set; } = "";

        // ======================================================================
        private static readonly JsonSerializerOptions JsonOpts = new JsonSerializerOptions
        {
            WriteIndented = true,
            Converters = { new JsonStringEnumConverter() },
        };

        [JsonIgnore] private DispatcherTimer _saveTimer;

        public static AppSettings Load()
        {
            AppSettings s = null;
            try
            {
                if (File.Exists(Paths.SettingsFile))
                    s = JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(Paths.SettingsFile), JsonOpts);
            }
            catch (Exception ex)
            {
                Log.Error("Settings file unreadable, using defaults", ex);
                try { File.Copy(Paths.SettingsFile, Paths.SettingsFile + ".broken", true); } catch { }
            }
            s ??= new AppSettings();
            s.ApplyDefaults();
            s.HookAutoSave();
            return s;
        }

        private void ApplyDefaults()
        {
            if (AudioTracks == null || AudioTracks.Count == 0)
                AudioTracks = new ObservableCollection<AudioTrackConfig>(AudioTrackConfig.Defaults());
            if (Games == null || Games.Count == 0)
                Games = new ObservableCollection<GameProfile>(GameProfile.Defaults());
            if (string.IsNullOrWhiteSpace(ClipFolder)) ClipFolder = Paths.DefaultClipFolder;
            if (string.IsNullOrWhiteSpace(BufferFolder)) BufferFolder = Paths.DefaultBufferFolder;
            ClipHotkey ??= Hotkey.Key(Key.F8);
            RecordHotkey ??= Hotkey.Key(Key.F9);
            BufferHotkey ??= new Hotkey();
            EncoderCache ??= Array.Empty<string>();
        }

        private void HookAutoSave()
        {
            PropertyChanged += (_, __) => SaveSoon();
            HookCollection(AudioTracks);
            HookCollection(Games);
        }

        private void HookCollection<T>(ObservableCollection<T> col) where T : INotifyPropertyChanged
        {
            foreach (var item in col) item.PropertyChanged += (_, __) => SaveSoon();
            col.CollectionChanged += (_, e) =>
            {
                if (e.NewItems != null)
                    foreach (INotifyPropertyChanged item in e.NewItems) item.PropertyChanged += (_, __) => SaveSoon();
                SaveSoon();
            };
        }

        /// <summary>Raised (debounced) whenever anything in the settings changed.</summary>
        public event EventHandler Changed;

        public void SaveSoon()
        {
            var app = System.Windows.Application.Current;
            if (app == null) { Save(); return; }
            if (_saveTimer == null)
            {
                _saveTimer = new DispatcherTimer(DispatcherPriority.Background, app.Dispatcher) { Interval = TimeSpan.FromMilliseconds(600) };
                _saveTimer.Tick += (_, __) => { _saveTimer.Stop(); Save(); Changed?.Invoke(this, EventArgs.Empty); };
            }
            _saveTimer.Stop();
            _saveTimer.Start();
        }

        public void Save()
        {
            try
            {
                var tmp = Paths.SettingsFile + ".tmp";
                File.WriteAllText(tmp, JsonSerializer.Serialize(this, JsonOpts));
                File.Move(tmp, Paths.SettingsFile, true);
            }
            catch (Exception ex) { Log.Error("Could not save settings", ex); }
        }

        public GameProfile FindGame(string id) => Games.FirstOrDefault(g => g.Id == id);

        /// <summary>Copy of a settings collection that is safe to take from background threads
        /// (the UI thread may be adding/removing items at the same moment).</summary>
        public static T[] Snapshot<T>(IEnumerable<T> items)
        {
            for (int i = 0; i < 10; i++)
            {
                try { return items.ToArray(); }
                catch (InvalidOperationException) { System.Threading.Thread.Sleep(2); }
            }
            return Array.Empty<T>();
        }
    }
}
