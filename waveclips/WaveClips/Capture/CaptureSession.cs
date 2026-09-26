using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using WaveClips.Audio;
using WaveClips.Core;

namespace WaveClips.Capture
{
    public sealed partial class CaptureConfig
    {
        public static CaptureConfig FromSettings(AppSettings s, IReadOnlyList<MonitorInfo> monitors) => new CaptureConfig
        {
            Monitor = Monitors.Find(monitors, s.MonitorId),
            Fps = s.Fps,
            OutputHeight = s.OutputHeight,
            Encoder = Encoders.Resolve(s.Encoder),
            RateControl = s.RateControl,
            Quality = s.Quality,
            BitrateMbps = s.BitrateMbps,
            Speed = s.EncoderSpeed,
            Cursor = s.CaptureCursor,
            Method = s.CaptureMethod,
            AudioBitrateKbps = s.AudioBitrateKbps,
            TrackSignature = (s.MixTrack ? "mix;" : "") + string.Join(";", AppSettings.Snapshot(s.AudioTracks).Where(t => t.Enabled).Select(t => t.Id)),
        };
    }

    /// <summary>
    /// One running ffmpeg process: screen capture + N audio pipes -> encoder -> 1-second MPEG-TS segments.
    /// </summary>
    public sealed class CaptureSession : IDisposable
    {
        public CaptureConfig Config { get; }
        public PipelineLevel Level { get; }
        public string Directory { get; }
        public string ListFile => Path.Combine(Directory, "segments.csv");
        public IReadOnlyList<PipeAudioWriterInfo> AudioStreams => _pipes.Streams;
        public SegmentIndex Segments { get; }

        /// <summary>AppClock time of the first video frame (stream time 0). NaN until known.</summary>
        public double VideoStart { get; private set; } = double.NaN;
        public bool Started => !double.IsNaN(VideoStart) && FramesEncoded > 0;
        public long FramesEncoded { get; private set; }
        public double EncodeFps { get; private set; }
        public long DroppedFrames { get; private set; }
        public double Speed { get; private set; }
        public bool HasExited => _proc == null || _proc.HasExited;
        public int ExitCode => _proc != null && _proc.HasExited ? _proc.ExitCode : 0;
        public string ErrorSummary { get; private set; } = "";
        /// <summary>Which log line fixed the video start ("ddagrab", "gdigrab" or "timeout").</summary>
        public string MarkerKind { get; private set; } = "";

        public event Action<CaptureSession> Exited;
        public event Action<CaptureSession> VideoStarted;

        private readonly AudioEngine _audio;
        private readonly PipeSet _pipes;
        private Process _proc;
        private readonly LinkedList<string> _errTail = new LinkedList<string>();
        private readonly List<string> _errorLines = new List<string>();
        private double _openedMarker = double.NaN;

        public CaptureSession(CaptureConfig config, PipelineLevel level, AudioEngine audio, string bufferRoot)
        {
            Config = config;
            Level = level;
            _audio = audio;
            var tag = $"{Environment.ProcessId}_{DateTime.Now:HHmmssfff}";
            Directory = Paths.Ensure(Path.Combine(bufferRoot, "session_" + tag));
            _pipes = audio.OpenSessionPipes(tag);
            Segments = new SegmentIndex(Directory, ListFile);
        }

        public string LevelLabel => Level switch
        {
            PipelineLevel.DdaGpu => "Desktop Duplication → GPU encode (zero-copy)",
            PipelineLevel.DdaDownload => "Desktop Duplication → " + (EffectiveEncoder.IsHardware ? "GPU encode" : "CPU encode"),
            PipelineLevel.Gdi => "GDI capture (compatibility) → " + (EffectiveEncoder.IsHardware ? "GPU encode" : "CPU encode"),
            _ => "GDI capture → CPU x264 (safe mode)",
        };

        public EncoderInfo EffectiveEncoder => CaptureArgs.EffectiveEncoder(Config, Level);

        public List<string> BuildArgs() =>
            CaptureArgs.Build(Config, Level, AudioStreams.Select(a => a.Path).ToList(), Directory, ListFile);

        public void Start()
        {
            var args = BuildArgs();
            Log.Info($"Capture start [{Level}] ffmpeg " + string.Join(" ", args.Select(x => x.Contains(' ') ? $"\"{x}\"" : x)));
            _proc = new Process { StartInfo = FFmpeg.NewStartInfo(FFmpeg.FfmpegPath, args, redirectStdin: true), EnableRaisingEvents = true };
            _proc.ErrorDataReceived += (_, e) => { if (e.Data != null) OnStderr(e.Data); };
            _proc.OutputDataReceived += (_, e) => { if (e.Data != null) OnProgress(e.Data); };
            _proc.Exited += (_, __) =>
            {
                string tail; List<string> errors;
                lock (_errTail) { tail = string.Join("\n", _errTail); errors = _errorLines.ToList(); }
                ErrorSummary = Summarize(errors, tail);
                Log.Info($"Capture ffmpeg exited ({SafeExitCode()}). Last output:\n{tail}");
                _audio.ClosePipes(_pipes);
                Exited?.Invoke(this);
            };
            _proc.Start();
            Interop.Native.TieToLifetime(_proc);
            try { _proc.PriorityClass = ProcessPriorityClass.AboveNormal; } catch { }
            _proc.BeginErrorReadLine();
            _proc.BeginOutputReadLine();

            // If the marker line never shows up (unusual builds), fall back to "roughly now".
            Task.Delay(6000).ContinueWith(_ =>
            {
                if (double.IsNaN(VideoStart) && !HasExited)
                    MarkVideoStart(double.IsNaN(_openedMarker) ? AppClock.Seconds - 5.5 : _openedMarker + 0.03, "timeout");
            });
        }

        private int SafeExitCode() { try { return _proc.ExitCode; } catch { return -1; } }

        private void OnStderr(string line)
        {
            lock (_errTail)
            {
                _errTail.AddLast(line);
                if (_errTail.Count > 60) _errTail.RemoveFirst();
                if ((line.Contains("[error]") || line.Contains("[fatal]")) && _errorLines.Count < 12) _errorLines.Add(line);
            }
            if (!double.IsNaN(VideoStart)) return;
            double now = AppClock.Seconds;
            // ddagrab logs these (verbose) while configuring, right before its first frame.
            if (line.Contains("Opened dxgi output")) _openedMarker = now;
            else if (line.Contains("Probed") && line.Contains("frame format")) MarkVideoStart(now + 1.0 / Config.Fps, "ddagrab");
            else if (line.Contains("Capturing whole desktop")) MarkVideoStart(now, "gdigrab");
        }

        private void MarkVideoStart(double t, string how)
        {
            if (!double.IsNaN(VideoStart)) return;
            VideoStart = t;
            MarkerKind = how;
            Log.Info($"Video start marker ({how}) at {t:F3}");
            _audio.SetVideoStart(_pipes, t);
            VideoStarted?.Invoke(this);
        }

        private void OnProgress(string line)
        {
            int eq = line.IndexOf('=');
            if (eq <= 0) return;
            var key = line.Substring(0, eq);
            var val = line.Substring(eq + 1).Trim();
            var inv = CultureInfo.InvariantCulture;
            switch (key)
            {
                case "frame": if (long.TryParse(val, out var f)) FramesEncoded = f; break;
                case "fps": if (double.TryParse(val, NumberStyles.Float, inv, out var fps)) EncodeFps = fps; break;
                case "drop_frames": if (long.TryParse(val, out var d)) DroppedFrames = d; break;
                case "speed": if (double.TryParse(val.TrimEnd('x'), NumberStyles.Float, inv, out var sp)) Speed = sp; break;
            }
        }

        /// <summary>Short human readable reason: the first real [error] lines, without ffmpeg's context prefixes.</summary>
        internal static string Summarize(IReadOnlyList<string> errorLines, string tail)
        {
            static string Clean(string l) =>
                System.Text.RegularExpressions.Regex.Replace(l, @"\[[^\]]*@ ?[0-9a-fA-Fx]+\]\s*|\[(error|fatal|warning|info|verbose)\]\s*", "").Trim();
            var picked = errorLines.Select(Clean)
                .Where(l => l.Length > 0 && !l.StartsWith("Conversion failed") && !l.StartsWith("Terminating thread") &&
                            !l.StartsWith("Task finished with error") && !l.Contains("Could not open encoder before EOF") &&
                            !l.StartsWith("Error while filtering") && !l.StartsWith("Nothing was written"))
                .Distinct().Take(2).ToList();
            if (picked.Count == 0)
                picked = tail.Split('\n').Select(Clean).Where(l => l.Length > 0).TakeLast(2).ToList();
            var s = string.Join(" · ", picked);
            return s.Length > 300 ? s.Substring(0, 300) + "…" : s;
        }

        /// <summary>Asks ffmpeg to finish the current segment and exit ('q'), killing it if it hangs.</summary>
        public async Task StopAsync()
        {
            var p = _proc;
            if (p == null) return;
            try
            {
                if (!p.HasExited)
                {
                    try { await p.StandardInput.WriteAsync("q"); await p.StandardInput.FlushAsync(); } catch { }
                    var exited = await Task.Run(() => p.WaitForExit(6000));
                    if (!exited) { try { p.Kill(true); } catch { } }
                }
            }
            catch (Exception ex) { Log.Error("Stopping capture", ex); }
            _audio.ClosePipes(_pipes);
            Segments.Poll();
        }

        public void Dispose()
        {
            try { if (_proc != null && !_proc.HasExited) _proc.Kill(true); } catch { }
            _audio.ClosePipes(_pipes);
            _proc?.Dispose();
        }
    }
}
