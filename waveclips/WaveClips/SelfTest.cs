using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;
using WaveClips.Capture;
using WaveClips.Core;
using WaveClips.Views;

namespace WaveClips
{
    /// <summary>
    /// <c>WaveClips.exe --selftest &lt;outDir&gt;</c>: drives the real app unattended (used by CI on Windows).
    /// Screenshots every page, records with each capture method, saves a clip and checks its audio tracks,
    /// records, opens the clip in the editor and exports it. Writes report.txt; exit code = number of failures.
    /// Run it with WAVECLIPS_HOME set so it never touches real settings or clips.
    /// </summary>
    public static class SelfTest
    {
        private const double ToneHz = 1000;
        private static readonly List<string> Report = new List<string>();
        private static int _failures;
        private static string _out;

        private static void Line(string kind, string msg)
        {
            var l = $"{kind,-5} {msg}";
            Report.Add(l);
            Log.Info("[selftest] " + l);
        }

        private static void Check(bool ok, string msg)
        {
            Line(ok ? "PASS" : "FAIL", msg);
            if (!ok) _failures++;
        }

        private static void Info(string msg) => Line("INFO", msg);

        public static bool Active { get; private set; }

        /// <summary>Unhandled UI exceptions during the self-test count as failures.</summary>
        public static void RecordException(Exception ex) => Check(false, "unhandled UI exception: " + ex);

        public static async Task<int> RunAsync(MainWindow win, string outDir)
        {
            _out = Paths.Ensure(outDir);
            Active = true;
            Msg.Quiet = true;
            Msg.Suppressed += m => Info("dialog suppressed: " + m);
            using var watchdog = new CancellationTokenSource(TimeSpan.FromMinutes(9));
            try
            {
                var run = RunCoreAsync(win);
                var done = await Task.WhenAny(run, Task.Delay(Timeout.Infinite, watchdog.Token));
                if (done != run) { Check(false, "self-test timed out"); }
                else await run;
            }
            catch (Exception ex) { Check(false, "self-test crashed: " + ex); }
            Line("DONE", $"{_failures} failure(s)");
            File.WriteAllLines(Path.Combine(_out, "report.txt"), Report);
            return _failures;
        }

        private static async Task RunCoreAsync(MainWindow win)
        {
            var s = AppHost.Settings;
            Info($"Windows {Environment.OSVersion.Version}, .NET {Environment.Version}, {Environment.ProcessorCount} CPUs");
            win.Width = 1360;
            win.Height = 860;
            win.WindowState = WindowState.Normal;
            await Idle(800);

            // ---------------------------------------------------------------- environment
            Check(FFmpeg.Available, $"FFmpeg found: {FFmpeg.FfmpegPath} ({FFmpeg.Version})");
            if (!FFmpeg.Available) return;
            await Encoders.DetectAsync(s, force: true);
            AppHost.State.EncodersReady = true;
            Info("encoders: " + string.Join(", ", Encoders.Available.Select(e => e.Id)));
            var monitors = Monitors.Enumerate();
            Check(monitors.Count > 0, $"{monitors.Count} monitor(s)");
            foreach (var m in monitors)
                Info($"monitor {m.Number}: {m.DeviceName} '{m.FriendlyName}' {m.Width}x{m.Height}@{m.RefreshRate} adapter={m.AdapterIndex} output={m.OutputIndex} gpu='{m.AdapterName}' primary={m.IsPrimary}");

            // ---------------------------------------------------------------- pages before any clips
            await SnapPages(win, "empty");

            // ---------------------------------------------------------------- audio tracks for the test
            // Extra track that follows our own process: we play a tone below and check it lands only there.
            s.AudioTracks.First(t => t.Source == AudioSourceKind.SystemAll).Enabled = true;
            var toneTrack = new AudioTrackConfig { Name = "SelfTone", Source = AudioSourceKind.App, AppExe = "WaveClips.exe", Color = "#FF6E9C" };
            s.AudioTracks.Add(toneTrack);
            s.ClipSeconds = 6;
            s.Fps = 30;
            s.ClipSound = false;
            s.Save();
            var tone = StartTone();

            // ---------------------------------------------------------------- capture with each method
            string lastClip = null;
            foreach (var method in new[] { CaptureMethod.DesktopDuplication, CaptureMethod.Gdi })
            {
                s.CaptureMethod = method;
                await AppHost.Capture.SetBufferEnabledAsync(true);
                var c = AppHost.Capture;
                Info($"[{method}] state={c.State} pipeline='{c.PipelineText}' encoder='{c.EncoderName}' {c.DebugInfo}");
                if (!string.IsNullOrEmpty(c.ErrorText)) Info($"[{method}] note: {c.ErrorText}");
                Check(c.State == BufferState.Running, $"[{method}] replay buffer running");
                if (c.State != BufferState.Running) { await c.SetBufferEnabledAsync(false); continue; }

                await Task.Delay(7000);
                foreach (var src in AppHost.Audio.Sources) Info($"[{method}] audio '{src.Config.Name}': {src.Status}");
                Info($"[{method}] {c.DebugInfo} buffer={c.BufferSizeText}");
                if (method == CaptureMethod.DesktopDuplication || lastClip == null)
                {
                    win.Navigate("Home");
                    await Idle(600);
                    Snap(win, $"home_running_{method}");
                }

                int before = AppHost.Library.Items.Count;
                await c.SaveClipAsync();
                await WaitFor(() => AppHost.Library.Items.Count > before, 8000);
                var clip = AppHost.Library.Items.FirstOrDefault(i => !i.IsRecording);
                Check(AppHost.Library.Items.Count > before && clip != null, $"[{method}] clip saved: {clip?.Path}");
                if (clip != null)
                {
                    lastClip = clip.Path;
                    await CheckClip(clip.Path, method.ToString(), expectMin: 5, toneTrack.Name);
                }
                await c.SetBufferEnabledAsync(false);
                Check(c.State == BufferState.Off, $"[{method}] buffer stopped");
            }

            // ---------------------------------------------------------------- recording (no replay buffer)
            {
                var c = AppHost.Capture;
                int before = AppHost.Library.Items.Count;
                await c.StartRecordingAsync();
                Check(c.IsRecording, "recording started");
                await Task.Delay(5000);
                win.Navigate("Home");
                await Idle(500);
                Snap(win, "home_recording");
                await c.StopRecordingAsync();
                await WaitFor(() => AppHost.Library.Items.Count > before, 8000);
                var rec = AppHost.Library.Items.FirstOrDefault(i => i.IsRecording);
                Check(rec != null, $"recording saved: {rec?.Path}");
                if (rec != null) await CheckClip(rec.Path, "recording", expectMin: 3, toneTrack.Name);
                Check(c.State == BufferState.Off, "capture stopped after recording");
            }
            try { tone?.Dispose(); } catch { }

            // ---------------------------------------------------------------- toast
            AppHost.Notifier.Show(NotifyKind.Clip, "Clip saved!", "Minecraft  ·  0:30  ·  4 audio tracks");
            await Idle(700);
            var toast = Application.Current.Windows.OfType<ToastWindow>().FirstOrDefault();
            Check(toast != null && toast.IsVisible, "toast popup shown");
            if (toast != null) Snap(toast, "toast");
            TrySnapScreen("desktop_with_toast");

            // ---------------------------------------------------------------- pages with clips
            await AppHost.Library.RefreshAsync();
            await Task.Delay(2500); // thumbnails
            await SnapPages(win, "clips");

            // ---------------------------------------------------------------- editor
            if (lastClip != null)
            {
                var ed = win.Editor;
                win.Navigate("Editor");
                bool loaded = await ed.LoadAsync(lastClip);
                Check(loaded && ed.HasClip, "editor opened the clip");
                await WaitFor(() => ed.PreviewReady, 10000);
                Info("editor video preview " + (ed.PreviewReady ? "ready" : "NOT ready (Media Foundation missing on this machine?)"));
                await Idle(800);
                Snap(win, "editor_loaded");
                ed.ApplyDemoEdit();
                foreach (var tab in new[] { "Audio", "Speed", "Look", "Text", "Frame", "Export" })
                {
                    ed.ShowInspectorTab(tab);
                    await Idle(300);
                    Snap(win, "editor_" + tab.ToLowerInvariant());
                }
                TrySnapScreen("desktop_editor");
                var exported = await ed.ExportAsync("d10");
                Check(exported != null && File.Exists(exported), $"editor export (Discord 10 MB preset): {exported}");
                await Idle(500);
                Snap(win, "editor_export_done");
                if (exported != null)
                {
                    var info = await MediaProbe.ProbeAsync(exported);
                    long size = new FileInfo(exported).Length;
                    Check(info.Duration > 1 && info.Audio.Count == 1, $"export: {info.Width}x{info.Height} {info.Duration:F2}s audio={info.Audio.Count} size={size / 1024} KB");
                    Check(size < 10_000_000, "export fits 10 MB");
                }
                ed.CloseBusyOverlay();
            }
        }

        // -------------------------------------------------------------------- clip checks
        private static async Task CheckClip(string path, string label, double expectMin, string toneTrackName)
        {
            var info = await MediaProbe.ProbeAsync(path);
            var expected = new List<string>();
            var enabled = AppHost.Settings.AudioTracks.Where(t => t.Enabled).Select(t => t.Name).ToList();
            if (AppHost.Settings.MixTrack && enabled.Count > 1) expected.Add("Mix (all)");
            expected.AddRange(enabled);
            var titles = info.Audio.Select(a => a.Title).ToList();
            Info($"[{label}] {info.Width}x{info.Height} {info.Fps:0.#}fps {info.Duration:F2}s codec={info.VideoCodec} tracks=[{string.Join(" | ", titles)}]");
            Check(info.Width > 0 && info.Duration >= expectMin - 0.5, $"[{label}] video duration {info.Duration:F2}s >= {expectMin - 0.5}");
            Check(titles.SequenceEqual(expected), $"[{label}] audio tracks = {string.Join(", ", expected)}");

            // Tone analysis: only meaningful if this machine can play audio at all.
            int toneIdx = titles.IndexOf(toneTrackName);
            int sysIdx = titles.FindIndex(t => t.StartsWith("System"));
            if (toneIdx < 0) return;
            double toneLevel = await ToneRatio(path, toneIdx);
            double sysLevel = sysIdx >= 0 ? await ToneRatio(path, sysIdx) : 0;
            Info($"[{label}] {ToneHz} Hz tone: SelfTone track ratio={toneLevel:E1}, System track ratio={sysLevel:E1}");
            if (_toneStarted)
            {
                Check(toneLevel > 50, $"[{label}] per-app capture: our own tone is on the SelfTone track");
                Check(sysLevel < toneLevel / 50 || sysLevel < 5, $"[{label}] 'everything except WaveClips' track does not contain our tone");
            }
        }

        /// <summary>Energy at the test tone vs a neighbouring frequency (Goertzel).</summary>
        private static async Task<double> ToneRatio(string file, int audioIndex)
        {
            var raw = Path.Combine(Paths.Temp, $"selftest_a{audioIndex}.raw");
            var r = await FFmpeg.RunAsync(new[] { "-v", "error", "-y", "-i", file, "-map", $"0:a:{audioIndex}", "-ac", "1", "-ar", "48000", "-f", "f32le", raw });
            if (!r.Success || !File.Exists(raw)) return 0;
            var bytes = File.ReadAllBytes(raw);
            var x = new float[bytes.Length / 4];
            Buffer.BlockCopy(bytes, 0, x, 0, x.Length * 4);
            double P(double f)
            {
                double k = 2 * Math.Cos(2 * Math.PI * f / 48000), q1 = 0, q2 = 0;
                foreach (var v in x) { double q0 = k * q1 - q2 + v; q2 = q1; q1 = q0; }
                return q1 * q1 + q2 * q2 - k * q1 * q2;
            }
            return P(ToneHz) / Math.Max(1e-9, P(ToneHz * 1.37));
        }

        private static bool _toneStarted;

        private static IDisposable StartTone()
        {
            try
            {
                var gen = new SignalGenerator(48000, 2) { Frequency = ToneHz, Gain = 0.2, Type = SignalGeneratorType.Sin };
                var outp = new WaveOutEvent();
                outp.Init(gen);
                outp.Play();
                _toneStarted = true;
                Info("playing a test tone from this process");
                return outp;
            }
            catch (Exception ex)
            {
                Info("no audio output on this machine (" + ex.Message + ") - tone checks skipped");
                return null;
            }
        }

        // -------------------------------------------------------------------- screenshots
        private static async Task SnapPages(MainWindow win, string tag)
        {
            foreach (var page in new[] { "Home", "Clips", "Editor", "Games", "Settings" })
            {
                win.Navigate(page);
                await Idle(700);
                Snap(win, $"{tag}_{page.ToLowerInvariant()}");
            }
            if (tag == "empty")
            {
                foreach (var section in new[] { "Capture", "Clipping", "Hotkeys", "Audio", "General" })
                {
                    win.Navigate("Settings");
                    SettingsView.Current?.ShowSection(section);
                    await Idle(500);
                    Snap(win, $"settings_{section.ToLowerInvariant()}");
                }
            }
        }

        private static void Snap(Window w, string name)
        {
            try
            {
                var el = (FrameworkElement)w.Content;
                el.UpdateLayout();
                int width = Math.Max(1, (int)Math.Ceiling(el.ActualWidth)), height = Math.Max(1, (int)Math.Ceiling(el.ActualHeight));
                var rtb = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
                var dv = new DrawingVisual();
                using (var dc = dv.RenderOpen())
                {
                    dc.DrawRectangle(w.Background ?? Brushes.Black, null, new Rect(0, 0, width, height));
                    dc.DrawRectangle(new VisualBrush(el), null, new Rect(0, 0, width, height));
                }
                rtb.Render(dv);
                var enc = new PngBitmapEncoder();
                enc.Frames.Add(BitmapFrame.Create(rtb));
                using var fs = File.Create(Path.Combine(_out, name + ".png"));
                enc.Save(fs);
            }
            catch (Exception ex) { Info($"screenshot {name} failed: {ex.Message}"); }
        }

        private static void TrySnapScreen(string name)
        {
            try
            {
                var b = System.Windows.Forms.Screen.PrimaryScreen.Bounds;
                using var bmp = new System.Drawing.Bitmap(b.Width, b.Height);
                using (var g = System.Drawing.Graphics.FromImage(bmp)) g.CopyFromScreen(b.Location, System.Drawing.Point.Empty, b.Size);
                bmp.Save(Path.Combine(_out, name + ".png"), System.Drawing.Imaging.ImageFormat.Png);
            }
            catch (Exception ex) { Info($"desktop screenshot {name} failed: {ex.Message}"); }
        }

        // -------------------------------------------------------------------- helpers
        private static async Task Idle(int extraMs)
        {
            await Dispatcher.Yield(DispatcherPriority.ApplicationIdle);
            await Task.Delay(extraMs);
            await Dispatcher.Yield(DispatcherPriority.ApplicationIdle);
        }

        private static async Task WaitFor(Func<bool> cond, int timeoutMs)
        {
            var until = DateTime.UtcNow.AddMilliseconds(timeoutMs);
            while (!cond() && DateTime.UtcNow < until) await Task.Delay(100);
        }
    }
}
