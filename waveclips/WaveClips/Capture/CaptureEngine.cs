using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using WaveClips.Audio;
using WaveClips.Core;

namespace WaveClips.Capture
{
    public enum BufferState { Off, Starting, Running, Error }
    public enum NotifyKind { Clip, Recording, Info, Error }

    /// <summary>
    /// Owns the ffmpeg capture session and everything done with it: replay buffer, saving clips, recording.
    /// Public methods may be called from any thread; UI-facing properties change on the UI thread.
    /// </summary>
    public sealed class CaptureEngine : ObservableObject, IDisposable
    {
        private readonly AppSettings _settings;
        private readonly AudioEngine _audio;
        private readonly ClipLibrary _library;
        private readonly GameDetector _games;
        private readonly SemaphoreSlim _op = new SemaphoreSlim(1, 1);
        private readonly Dictionary<string, PipelineLevel> _workingLevel = new Dictionary<string, PipelineLevel>();
        private readonly List<double> _pins = new List<double>();
        private readonly List<Task> _pendingSaves = new List<Task>();
        private readonly Timer _tick;

        private CaptureSession _session;
        private bool _audioHeld;
        private bool _wantBuffer;
        private bool _recording;
        private double _recStart;
        private DateTime _recStartedAt;
        private GameMatch _recGame;
        private readonly List<double> _crashTimes = new List<double>();

        public CaptureEngine(AppSettings settings, AudioEngine audio, ClipLibrary library, GameDetector games)
        {
            _settings = settings;
            _audio = audio;
            _library = library;
            _games = games;
            _tick = new Timer(_ => Tick(), null, 250, 250);
        }

        /// <summary>Clip saved, recording saved, errors - shown as overlay toasts.</summary>
        public event Action<NotifyKind, string, string> Notify;

        // ------------------------------------------------------------------ UI state
        private BufferState _state = BufferState.Off;
        private string _status = "Replay buffer is off";
        private string _error = "";
        private bool _isRecording;
        private string _recordingTime = "00:00";
        private double _fps;
        private long _dropped;
        private string _pipeline = "";
        private string _encoderName = "";
        private string _bufferSize = "0 MB";
        private int _saving;
        private bool _bufferEnabled;

        public BufferState State { get => _state; private set { if (Set(ref _state, value)) OnPropertyChanged(nameof(IsActive)); } }
        public bool IsActive => State == BufferState.Running || State == BufferState.Starting;
        public string StatusText { get => _status; private set => Set(ref _status, value); }
        public string ErrorText { get => _error; private set => Set(ref _error, value); }
        public bool IsRecording { get => _isRecording; private set => Set(ref _isRecording, value); }
        public string RecordingTime { get => _recordingTime; private set => Set(ref _recordingTime, value); }
        public double Fps { get => _fps; private set => Set(ref _fps, value); }
        public long DroppedFrames { get => _dropped; private set => Set(ref _dropped, value); }
        public string PipelineText { get => _pipeline; private set => Set(ref _pipeline, value); }
        public string EncoderName { get => _encoderName; private set => Set(ref _encoderName, value); }
        public string BufferSizeText { get => _bufferSize; private set => Set(ref _bufferSize, value); }
        public int SavingCount { get => _saving; private set { if (Set(ref _saving, value)) OnPropertyChanged(nameof(IsSaving)); } }
        public bool IsSaving => SavingCount > 0;
        /// <summary>Whether the replay buffer is wanted (vs. only running for a recording).</summary>
        public bool BufferEnabled { get => _bufferEnabled; private set => Set(ref _bufferEnabled, value); }

        /// <summary>Diagnostics for the self-test / log.</summary>
        public string DebugInfo
        {
            get
            {
                var s = _session;
                return s == null ? "no session" : $"level={s.Level} marker={s.MarkerKind} encoder={s.EffectiveEncoder.Id} fps={s.EncodeFps:0.#} dropped={s.DroppedFrames}";
            }
        }

        private static void Ui(Action a)
        {
            var d = Application.Current?.Dispatcher;
            if (d == null || d.CheckAccess()) a();
            else d.BeginInvoke(a);
        }

        private void SetStatus(BufferState state, string status, string error = null)
        {
            Ui(() =>
            {
                State = state;
                StatusText = status;
                if (error != null) ErrorText = error;
            });
        }

        // ------------------------------------------------------------------ public operations
        public async Task SetBufferEnabledAsync(bool enabled)
        {
            await _op.WaitAsync();
            try
            {
                _wantBuffer = enabled;
                Ui(() => BufferEnabled = enabled);
                if (enabled)
                {
                    _crashTimes.Clear();
                    await EnsureSessionAsync();
                }
                else if (!_recording) await StopSessionAsync();
            }
            finally { _op.Release(); }
        }

        public Task ToggleBufferAsync() => SetBufferEnabledAsync(!_wantBuffer);

        public Task ToggleRecordingAsync() => _recording ? StopRecordingAsync() : StartRecordingAsync();

        public async Task StartRecordingAsync()
        {
            await _op.WaitAsync();
            try
            {
                if (_recording) return;
                if (!await EnsureSessionAsync()) return;
                var s = _session;
                _recStart = s.Started && _wantBuffer ? Math.Max(0, AppClock.Seconds - s.VideoStart) : 0;
                _recording = true;
                _recStartedAt = DateTime.Now;
                _recGame = _games.Current;
                lock (_pins) _pins.Add(_recStart - 1);
                Ui(() => { IsRecording = true; RecordingTime = "00:00"; });
                Notify?.Invoke(NotifyKind.Recording, "Recording started", Hint(_settings.RecordHotkey, "to stop"));
            }
            finally { _op.Release(); }
        }

        public async Task StopRecordingAsync()
        {
            Task save;
            await _op.WaitAsync();
            try
            {
                if (!_recording) return;
                var s = _session;
                double start = _recStart;
                double end = s != null && s.Started ? AppClock.Seconds - s.VideoStart : double.PositiveInfinity;
                _recording = false;
                Ui(() => IsRecording = false);
                save = TrackSave(SaveRangeAsync(s, start, end, _recGame, isRecording: true, pin: start - 1));
            }
            finally { _op.Release(); }

            await save;
            await _op.WaitAsync();
            try
            {
                if (!_wantBuffer && !_recording) await StopSessionAsync();
            }
            finally { _op.Release(); }
        }

        public Task SaveClipAsync()
        {
            double press = AppClock.Seconds;
            var s = _session;
            if (s == null || !s.Started || !_wantBuffer && !_recording)
            {
                Notify?.Invoke(NotifyKind.Error, "Replay buffer is off", "Turn on the replay buffer to save clips");
                return Task.CompletedTask;
            }
            double end = press - s.VideoStart;
            double start = Math.Max(0, end - _settings.ClipSeconds);
            lock (_pins) _pins.Add(start);
            return TrackSave(SaveRangeAsync(s, start, end, _games.Current, isRecording: false, pin: start));
        }

        private Task TrackSave(Task t)
        {
            lock (_pendingSaves) _pendingSaves.Add(t);
            Ui(() => SavingCount++);
            return t.ContinueWith(_ =>
            {
                lock (_pendingSaves) _pendingSaves.Remove(t);
                Ui(() => SavingCount--);
            });
        }

        private static readonly HashSet<string> Reserved = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        /// <summary>Unique output name, also unique against saves still in progress (two clips in one second).</summary>
        private static string ReserveOutput(string folder, string name)
        {
            lock (Reserved)
            {
                var path = Path.Combine(folder, name + ".mp4");
                for (int i = 2; File.Exists(path) || Reserved.Contains(path); i++)
                    path = Path.Combine(folder, $"{name} ({i}).mp4");
                Reserved.Add(path);
                return path;
            }
        }

        private async Task SaveRangeAsync(CaptureSession s, double start, double end, GameMatch game, bool isRecording, double pin)
        {
            string output = null;
            try
            {
                if (s == null) throw new InvalidOperationException("Capture isn't running");
                // Wait for ffmpeg to close the segment that contains the end point (up to ~1 s + encoder delay).
                double deadline = AppClock.Seconds + 4;
                s.Segments.Poll();
                while (!double.IsInfinity(end) && s.Segments.LatestEnd < end - 0.02 && AppClock.Seconds < deadline && !s.HasExited)
                {
                    await Task.Delay(100);
                    s.Segments.Poll();
                }
                var segs = s.Segments.Range(start, end + 0.05);
                if (segs.Count == 0) throw new Exception("Nothing recorded yet - the buffer only just started");

                string gameName = game?.Profile.Name ?? "Desktop";
                string folder = _settings.OrganizeByGame ? Path.Combine(_settings.ClipFolder, Paths.SafeFileName(gameName)) : _settings.ClipFolder;
                string stamp = DateTime.Now.ToString("yyyy-MM-dd HH-mm-ss");
                string name = isRecording ? $"{gameName} Recording {stamp}" : $"{gameName} {stamp}";
                output = ReserveOutput(folder, Paths.SafeFileName(name));

                if (isRecording) Notify?.Invoke(NotifyKind.Info, "Saving recording…", $"{TimeSpan.FromSeconds(segs.Sum(x => x.Duration)):hh\\:mm\\:ss} of video");
                await ClipWriter.WriteAsync(segs, s.AudioStreams, output, name);

                double duration = segs.Sum(x => x.Duration);
                _library.Add(output, new ClipMeta
                {
                    Game = gameName,
                    IsRecording = isRecording,
                    Duration = duration,
                    Tracks = s.AudioStreams.Select(a => a.Title).ToArray(),
                    Created = DateTime.Now,
                });
                Log.Info($"Saved {(isRecording ? "recording" : "clip")}: {output} ({duration:F1}s)");
                Notify?.Invoke(isRecording ? NotifyKind.Recording : NotifyKind.Clip,
                    isRecording ? "Recording saved" : "Clip saved!",
                    $"{gameName}  ·  {TimeSpan.FromSeconds(duration):m\\:ss}  ·  {s.AudioStreams.Count} audio tracks");
            }
            catch (Exception ex)
            {
                Log.Error("Saving failed", ex);
                Notify?.Invoke(NotifyKind.Error, isRecording ? "Recording failed" : "Clip failed", ex.Message);
            }
            finally
            {
                lock (_pins) _pins.Remove(pin);
                if (output != null) lock (Reserved) Reserved.Remove(output);
            }
        }

        /// <summary>Restarts capture if settings that affect it changed (skipped while recording).</summary>
        public async Task ApplySettingsAsync()
        {
            await _op.WaitAsync();
            try
            {
                var s = _session;
                if (s == null || _recording) return;
                var cfg = CaptureConfig.FromSettings(_settings, Monitors.Enumerate());
                if (cfg.Signature == s.Config.Signature) return;
                Log.Info("Capture settings changed, restarting buffer");
                await StopSessionAsync();
                _audio.ReloadTracks();
                if (_wantBuffer) await EnsureSessionAsync();
            }
            finally { _op.Release(); }
        }

        // ------------------------------------------------------------------ session management
        private async Task<bool> EnsureSessionAsync()
        {
            if (_session != null && !_session.HasExited) return true;
            if (!FFmpeg.Available)
            {
                SetStatus(BufferState.Error, "FFmpeg is missing", "Install FFmpeg from Settings → General (one click).");
                return false;
            }

            var monitors = Monitors.Enumerate();
            var cfg = CaptureConfig.FromSettings(_settings, monitors);
            if (cfg.Monitor == null)
            {
                SetStatus(BufferState.Error, "No monitor found", "Windows reported no displays to capture.");
                return false;
            }

            if (!_audioHeld) { _audio.Acquire(); _audioHeld = true; }
            else _audio.ReloadTracks();

            var bufferRoot = Paths.Ensure(_settings.BufferFolder);
            CleanupOldSessions(bufferRoot);

            var levels = cfg.Levels().ToList();
            if (_workingLevel.TryGetValue(cfg.Signature, out var known) && levels.Contains(known))
                levels = levels.SkipWhile(l => l != known).ToList();

            string lastError = "";
            foreach (var level in levels)
            {
                SetStatus(BufferState.Starting, $"Starting capture ({cfg.Monitor.Width}x{cfg.Monitor.Height} @ {cfg.Fps} fps)…");
                var session = new CaptureSession(cfg, level, _audio, bufferRoot);
                session.Start();

                double deadline = AppClock.Seconds + 12;
                while (!session.HasExited && !session.Started && AppClock.Seconds < deadline) await Task.Delay(100);

                if (session.Started && !session.HasExited)
                {
                    _session = session;
                    session.Exited += OnSessionExited;
                    if (session.HasExited) { OnSessionExited(session); return false; }
                    _workingLevel[cfg.Signature] = level;
                    Ui(() =>
                    {
                        PipelineText = session.LevelLabel;
                        EncoderName = session.EffectiveEncoder.Label;
                        ErrorText = level == levels[0] || level == known ? "" :
                            "Using a fallback capture mode because the preferred one failed: " + lastError;
                    });
                    SetStatus(BufferState.Running, StatusFor(cfg));
                    return true;
                }

                lastError = session.HasExited ? session.ErrorSummary : "ffmpeg didn't produce any frames";
                Log.Warn($"Capture level {level} failed: {lastError}");
                await session.StopAsync();
                session.Dispose();
                TryDeleteDir(session.Directory);
            }

            ReleaseAudio();
            SetStatus(BufferState.Error, "Capture failed to start", lastError);
            Notify?.Invoke(NotifyKind.Error, "Capture failed to start", lastError);
            return false;
        }

        private string StatusFor(CaptureConfig cfg) =>
            _wantBuffer ? $"Replay buffer running  ·  last {_settings.ClipSeconds}s ready  ·  {Hint(_settings.ClipHotkey, "to clip")}"
                        : "Recording only (replay buffer off)";

        private static string Hint(Hotkey h, string what) => h == null || h.IsEmpty ? "" : $"press {h} {what}";

        private void OnSessionExited(CaptureSession s)
        {
            _ = Task.Run(async () =>
            {
                await _op.WaitAsync();
                try
                {
                    if (s != _session) return; // deliberate stop
                    _session = null;
                    Log.Warn("Capture stopped unexpectedly: " + s.ErrorSummary);

                    if (_recording)
                    {
                        // Keep what was recorded so far.
                        _recording = false;
                        Ui(() => IsRecording = false);
                        _ = TrackSave(SaveRangeAsync(s, _recStart, double.PositiveInfinity, _recGame, true, _recStart - 1));
                    }
                    Task[] saves;
                    lock (_pendingSaves) saves = _pendingSaves.ToArray();
                    _ = Task.WhenAll(saves).ContinueWith(_ => { s.Dispose(); TryDeleteDir(s.Directory); });

                    double now = AppClock.Seconds;
                    _crashTimes.RemoveAll(t => now - t > 60);
                    _crashTimes.Add(now);
                    if (_wantBuffer && _crashTimes.Count <= 3)
                    {
                        SetStatus(BufferState.Starting, "Capture stopped - restarting…");
                        await EnsureSessionAsync();
                    }
                    else
                    {
                        ReleaseAudio();
                        SetStatus(BufferState.Error, "Capture stopped", s.ErrorSummary);
                        Notify?.Invoke(NotifyKind.Error, "Capture stopped", s.ErrorSummary);
                    }
                }
                finally { _op.Release(); }
            });
        }

        private async Task StopSessionAsync()
        {
            var s = _session;
            _session = null;
            if (s != null)
            {
                await s.StopAsync();
                Task[] saves;
                lock (_pendingSaves) saves = _pendingSaves.ToArray();
                await Task.WhenAll(saves);
                s.Dispose();
                TryDeleteDir(s.Directory);
            }
            ReleaseAudio();
            SetStatus(BufferState.Off, _wantBuffer ? "Stopped" : "Replay buffer is off", "");
            Ui(() => { Fps = 0; DroppedFrames = 0; BufferSizeText = "0 MB"; });
        }

        private void ReleaseAudio()
        {
            if (_audioHeld) { _audio.Release(); _audioHeld = false; }
        }

        private static void CleanupOldSessions(string root)
        {
            try
            {
                foreach (var d in Directory.GetDirectories(root, "session_*"))
                    if (!d.Contains($"session_{Environment.ProcessId}_")) TryDeleteDir(d);
            }
            catch { }
        }

        private static void TryDeleteDir(string dir)
        {
            try { if (Directory.Exists(dir)) Directory.Delete(dir, true); } catch { }
        }

        private int _tickCount;
        private void Tick()
        {
            var s = _session;
            if (s == null) return;
            try
            {
                s.Segments.Poll();
                if (s.Started)
                {
                    double now = AppClock.Seconds - s.VideoStart;
                    double keepFrom = now - (_settings.ClipSeconds + 15);
                    lock (_pins) if (_pins.Count > 0) keepFrom = Math.Min(keepFrom, _pins.Min());
                    if (_recording) keepFrom = Math.Min(keepFrom, _recStart - 1);
                    s.Segments.DeleteBefore(keepFrom);
                }
                if (++_tickCount % 4 == 0)
                {
                    long bytes = s.Segments.BytesOnDisk();
                    Ui(() =>
                    {
                        Fps = s.EncodeFps;
                        DroppedFrames = s.DroppedFrames;
                        BufferSizeText = bytes >= 1 << 30 ? $"{bytes / (double)(1 << 30):0.00} GB" : $"{bytes >> 20} MB";
                        if (_recording)
                        {
                            var el = DateTime.Now - _recStartedAt;
                            RecordingTime = el.TotalHours >= 1 ? el.ToString(@"h\:mm\:ss") : el.ToString(@"mm\:ss");
                        }
                        if (State == BufferState.Running) StatusText = StatusFor(s.Config);
                    });
                }
            }
            catch (Exception ex) { Log.Error("Capture tick", ex); }
        }

        public async Task ShutdownAsync()
        {
            _tick.Dispose();
            if (_recording) await StopRecordingAsync();
            await _op.WaitAsync();
            try
            {
                _wantBuffer = false;
                await StopSessionAsync();
            }
            finally { _op.Release(); }
        }

        public void Dispose()
        {
            _tick.Dispose();
            _session?.Dispose();
        }
    }
}
