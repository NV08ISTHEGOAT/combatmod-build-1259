using System;
using System.Buffers;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using WaveClips.Core;
using WaveClips.Interop;

namespace WaveClips.Audio
{
    /// <summary>
    /// Runs every enabled audio track in lock-step on one clock:
    /// capture (per app / mic) -> jitter buffer -> pacer (48 kHz, driven by AppClock) -> mix + per-track pipes -> ffmpeg.
    /// Because every track is produced from the same clock, the tracks can never drift apart.
    /// </summary>
    public sealed class AudioEngine : IDisposable
    {
        public const int Rate = 48000;
        /// <summary>Approximate capture + jitter-buffer latency, compensated when syncing to video.</summary>
        public const double CaptureLatency = 0.040;

        private readonly AppSettings _settings;
        private readonly object _gate = new object();
        private List<TrackSource> _sources = new List<TrackSource>();
        private PipeAudioWriter[] _writers = Array.Empty<PipeAudioWriter>();
        private Thread _pacer, _maint;
        private volatile bool _running;
        private volatile int _generation;
        private int _users;
        private double _startSeconds;
        private long _produced;
        private float _mixPeak;
        private volatile GameMatch _game;

        public AudioEngine(AppSettings settings) { _settings = settings; }

        public bool IsRunning => _running;

        /// <summary>Snapshot of live sources for meters / status text.</summary>
        internal IReadOnlyList<TrackSource> Sources { get { lock (_gate) return _sources.ToArray(); } }

        public GameMatch Game { get => _game; set => _game = value; }

        public float TakeMixPeak() { var p = _mixPeak; _mixPeak = 0; return p; }

        /// <summary>Start capturing (ref counted: recording + the audio settings page can both hold it).</summary>
        public void Acquire()
        {
            lock (_gate)
            {
                if (_users++ > 0) return;
                RebuildSources();
                _running = true;
                int gen = ++_generation; // an old pacer still winding down sees a stale generation and exits
                _startSeconds = AppClock.Seconds;
                _produced = 0;
                Native.timeBeginPeriod(1);
                _pacer = new Thread(() => PacerLoop(gen)) { IsBackground = true, Name = "Audio pacer", Priority = ThreadPriority.Highest };
                _maint = new Thread(() => MaintenanceLoop(gen)) { IsBackground = true, Name = "Audio maintenance" };
                _pacer.Start();
                _maint.Start();
            }
        }

        public void Release()
        {
            Thread p, m;
            lock (_gate)
            {
                if (_users == 0 || --_users > 0) return;
                _running = false;
                p = _pacer; m = _maint;
            }
            p?.Join(1000);
            m?.Join(2000);
            Native.timeEndPeriod(1);
            lock (_gate)
            {
                foreach (var s in _sources) s.Dispose();
                _sources.Clear();
            }
        }

        /// <summary>Re-reads the enabled track list (call after the user adds/removes/toggles tracks).</summary>
        public void ReloadTracks()
        {
            lock (_gate)
            {
                if (!_running) return;
                RebuildSources();
            }
        }

        private void RebuildSources()
        {
            var wanted = AppSettings.Snapshot(_settings.AudioTracks).Where(t => t.Enabled).ToList();
            var keep = new List<TrackSource>();
            foreach (var s in _sources)
            {
                if (wanted.Contains(s.Config)) keep.Add(s);
                else s.Dispose();
            }
            foreach (var cfg in wanted)
                if (keep.All(k => k.Config != cfg)) keep.Add(new TrackSource(cfg));
            _sources = wanted.Select(cfg => keep.First(k => k.Config == cfg)).ToList();
        }

        // ------------------------------------------------------------ session outputs
        /// <summary>
        /// Creates the named pipes ffmpeg will read: optional mix first, then every enabled track.
        /// Must be called while the engine is acquired and before ffmpeg starts.
        /// </summary>
        public PipeSet OpenSessionPipes(string sessionTag)
        {
            lock (_gate)
            {
                var list = new List<PipeAudioWriter>();
                int n = 0;
                if (_settings.MixTrack && _sources.Count > 1)
                    list.Add(new PipeAudioWriter($"WaveClips_{sessionTag}_{n++}", null, "Mix (all)", FrameTime));
                foreach (var s in _sources)
                    list.Add(new PipeAudioWriter($"WaveClips_{sessionTag}_{n++}", s.Config.Id, s.Config.Name, FrameTime));
                var set = new PipeSet(list.ToArray());
                _writers = _writers.Concat(set.Writers).ToArray();
                return set;
            }
        }

        /// <summary>Tell a session's pipes when its first video frame was grabbed (AppClock seconds).</summary>
        public void SetVideoStart(PipeSet set, double videoStartSeconds)
        {
            double shift = CaptureLatency - _settings.AudioSyncOffsetMs / 1000.0;
            foreach (var w in set.Writers) w.SetVideoStart(videoStartSeconds, shift);
        }

        /// <summary>Closes one session's pipes (safe to call more than once).</summary>
        public void ClosePipes(PipeSet set)
        {
            if (set == null) return;
            lock (_gate) _writers = _writers.Where(w => !set.Writers.Contains(w)).ToArray();
            foreach (var w in set.Writers) w.Dispose();
        }

        private void CloseAllPipes()
        {
            PipeAudioWriter[] old;
            lock (_gate) { old = _writers; _writers = Array.Empty<PipeAudioWriter>(); }
            foreach (var w in old) w.Dispose();
        }

        private double FrameTime(long frameIndex) => _startSeconds + frameIndex / (double)Rate;

        // ------------------------------------------------------------ threads
        private void MaintenanceLoop(int gen)
        {
            while (_running && gen == _generation)
            {
                try
                {
                    var procs = Native.SnapshotProcesses();
                    TrackSource[] sources;
                    lock (_gate) sources = _sources.ToArray();
                    foreach (var s in sources) s.Update(_game, procs);
                }
                catch (Exception ex) { Log.Error("Audio maintenance", ex); }
                for (int i = 0; i < 15 && _running && gen == _generation; i++) Thread.Sleep(100);
            }
        }

        private void PacerLoop(int gen)
        {
            const int MaxChunk = 4800; // 100 ms
            var trackBufs = new List<float[]>();
            var mix = new float[MaxChunk * 2];

            while (_running && gen == _generation)
            {
                long target = (long)((AppClock.Seconds - _startSeconds) * Rate);
                long due = target - _produced;
                if (due < 96) { Thread.Sleep(2); continue; }
                int frames = (int)Math.Min(due, MaxChunk);
                int samples = frames * 2;

                TrackSource[] sources;
                PipeAudioWriter[] writers;
                lock (_gate) { sources = _sources.ToArray(); writers = _writers; }
                while (trackBufs.Count < sources.Length) trackBufs.Add(new float[MaxChunk * 2]);

                Array.Clear(mix, 0, samples);
                float mixPeak = 0;
                for (int t = 0; t < sources.Length; t++)
                {
                    var src = sources[t];
                    var buf = trackBufs[t];
                    src.Read(buf, frames);
                    float vol = (float)src.Config.Volume;
                    bool inMix = src.Config.IncludeInMix;
                    float peak = 0;
                    for (int i = 0; i < samples; i++)
                    {
                        float v = buf[i] * vol;
                        buf[i] = v;
                        float a = v < 0 ? -v : v;
                        if (a > peak) peak = a;
                        if (inMix) mix[i] += v;
                    }
                    src.ReportPeak(peak);
                }
                for (int i = 0; i < samples; i++)
                {
                    float v = SoftClip(mix[i]);
                    mix[i] = v;
                    float a = v < 0 ? -v : v;
                    if (a > mixPeak) mixPeak = a;
                }
                if (mixPeak > _mixPeak) _mixPeak = mixPeak;

                foreach (var w in writers)
                {
                    if (!w.Connected) continue;
                    float[] srcBuf;
                    if (w.TrackId == null) srcBuf = mix;
                    else
                    {
                        int idx = Array.FindIndex(sources, s => s.Config.Id == w.TrackId);
                        srcBuf = idx >= 0 ? trackBufs[idx] : null;
                    }
                    var copy = ArrayPool<float>.Shared.Rent(samples);
                    if (srcBuf != null) Array.Copy(srcBuf, copy, samples);
                    else Array.Clear(copy, 0, samples);
                    w.Push(copy, frames, _produced);
                }
                _produced += frames;
            }
        }

        private static float SoftClip(float x)
        {
            float a = x < 0 ? -x : x;
            if (a <= 0.9f) return x;
            float y = 0.9f + 0.1f * MathF.Tanh((a - 0.9f) / 0.1f);
            return x < 0 ? -y : y;
        }

        public void Dispose()
        {
            CloseAllPipes();
            while (_users > 0) Release();
        }
    }

    /// <summary>The pipes belonging to one capture session.</summary>
    public sealed class PipeSet
    {
        internal PipeSet(PipeAudioWriter[] writers)
        {
            Writers = writers;
            Streams = writers.Select(w => new PipeAudioWriterInfo(w.PipePath, w.Title, w.TrackId)).ToArray();
        }
        internal PipeAudioWriter[] Writers { get; }
        public IReadOnlyList<PipeAudioWriterInfo> Streams { get; }
    }

    public sealed class PipeAudioWriterInfo
    {
        public PipeAudioWriterInfo(string path, string title, string trackId) { Path = path; Title = title; TrackId = trackId; }
        public string Path { get; }
        public string Title { get; }
        public string TrackId { get; }
        public bool IsMix => TrackId == null;
    }
}
