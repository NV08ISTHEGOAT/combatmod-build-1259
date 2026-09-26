using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using WaveClips.Audio;
using WaveClips.Capture;
using WaveClips.Core;
using WaveClips.Editor;

/// <summary>
/// End-to-end checks of the FFmpeg pipelines WaveClips generates:
///  * capture: the real capture command (ddagrab swapped for testsrc2, named pipes for FIFOs) -> 1 s segments
///    -> clip save -> every audio track must contain only its own tone;
///  * editor export: cuts, speed ramps, per-track volume/offset, text, crop, looks, size targets, GIF.
/// </summary>
static class Program
{
    static int failures;

    /// <summary>
    /// FFmpeg comes from $FFMPEG_EXE (or PATH). WaveClips looks for "ffmpeg.exe", so on Linux we point it at a
    /// temp folder with ffmpeg.exe / ffprobe.exe symlinks.
    /// </summary>
    static string PrepareFfmpeg()
    {
        var exe = Environment.GetEnvironmentVariable("FFMPEG_EXE");
        if (string.IsNullOrEmpty(exe))
            exe = (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator)
                .Select(d => Path.Combine(d, "ffmpeg")).FirstOrDefault(File.Exists);
        if (exe == null) throw new Exception("Set FFMPEG_EXE to a Linux ffmpeg binary (ffprobe must sit next to it).");
        var dir = Directory.CreateDirectory(Path.Combine(Path.GetTempPath(), "wc_ffmpeg_bin")).FullName;
        foreach (var name in new[] { "ffmpeg", "ffprobe" })
        {
            var link = Path.Combine(dir, name + ".exe");
            if (File.Exists(link)) File.Delete(link);
            File.CreateSymbolicLink(link, Path.Combine(Path.GetDirectoryName(Path.GetFullPath(exe))!, name));
        }
        return Path.Combine(dir, "ffmpeg.exe");
    }

    static string FindFont() =>
        new[] { Environment.GetEnvironmentVariable("TEST_FONT"), "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/truetype/freefont/FreeSansBold.ttf" }
            .FirstOrDefault(f => !string.IsNullOrEmpty(f) && File.Exists(f));

    static void Check(bool ok, string what)
    {
        Console.WriteLine((ok ? "  PASS  " : "  FAIL  ") + what);
        if (!ok) failures++;
    }

    static async Task<int> Main()
    {
        var settings = new AppSettings { FfmpegPath = PrepareFfmpeg() };
        Check(FFmpeg.Locate(settings), "FFmpeg located: " + FFmpeg.FfmpegPath + " / " + FFmpeg.FfprobePath);
        await Encoders.DetectAsync(settings, force: true);
        Console.WriteLine("  encoders: " + string.Join(", ", Encoders.Available.Select(e => e.Id)));
        ExportBuilder.FontOverride = FindFont();
        Check(ExportBuilder.FontOverride != null, "font for drawtext found: " + ExportBuilder.FontOverride);

        var work = Path.Combine(Path.GetTempPath(), "wc_harness");
        if (Directory.Exists(work)) Directory.Delete(work, true);
        Directory.CreateDirectory(work);

        string clip = await CaptureTest(work);
        if (clip != null) await ExportTests(clip, work);

        Console.WriteLine(failures == 0 ? "\nALL PASSED" : $"\n{failures} FAILURE(S)");
        return failures == 0 ? 0 : 1;
    }

    // ------------------------------------------------------------------ capture simulation
    static async Task<string> CaptureTest(string work)
    {
        Console.WriteLine("\n== Capture pipeline (testsrc2 in place of ddagrab, FIFOs in place of named pipes)");
        var dir = Directory.CreateDirectory(Path.Combine(work, "session")).FullName;
        var titles = new[] { "Mix (all)", "Minecraft", "Discord" };
        var freqs = new[] { 330.0, 440.0, 880.0 };
        var fifos = titles.Select((_, i) => Path.Combine(work, $"pipe{i}")).ToList();
        foreach (var f in fifos) Process.Start("mkfifo", f).WaitForExit();

        var cfg = new CaptureConfig
        {
            Monitor = new MonitorInfo { DeviceName = "test", Width = 1280, Height = 720, AdapterIndex = 0, OutputIndex = 0, RefreshRate = 60 },
            Fps = 60, OutputHeight = 0, Encoder = Encoders.Get("libx264"), RateControl = RateControl.ConstantQuality,
            Quality = 75, BitrateMbps = 20, Speed = EncoderSpeed.Fast, Cursor = true, Method = CaptureMethod.DesktopDuplication, AudioBitrateKbps = 160,
        };
        var levels = cfg.Levels().ToList();
        Check(string.Join(",", levels) == "DdaDownload,Gdi", "libx264 levels = DdaDownload,Gdi (got " + string.Join(",", levels) + ")");

        var list = Path.Combine(dir, "segments.csv");
        var args = CaptureArgs.Build(cfg, PipelineLevel.DdaDownload, fifos, dir, list);
        int fc = args.IndexOf("-filter_complex");
        Console.WriteLine("  graph: " + args[fc + 1]);
        Check(args[fc + 1] == "ddagrab=output_idx=0:framerate=60:draw_mouse=1,hwdownload,format=bgra,format=yuv420p[v]", "ddagrab graph as expected");
        args[fc + 1] = "testsrc2=size=1280x720:rate=60,format=bgra,format=yuv420p[v]";

        // Scaled NVENC config to eyeball the GPU path string too.
        var nv = new CaptureConfig { Monitor = new MonitorInfo { Width = 2560, Height = 1440, AdapterIndex = 1, OutputIndex = 2 }, Fps = 144, OutputHeight = 1080,
            Encoder = Encoders.Get("h264_nvenc"), RateControl = RateControl.Bitrate, BitrateMbps = 50, Speed = EncoderSpeed.Balanced, Method = CaptureMethod.DesktopDuplication, AudioBitrateKbps = 192 };
        Check(string.Join(",", nv.Levels()) == "DdaDownload,Gdi,GdiX264", "scaled nvenc skips zero-copy level");
        var nvArgs = CaptureArgs.Build(nv, PipelineLevel.DdaDownload, new[] { "\\\\.\\pipe\\a" }, "C:\\buf", "C:\\buf\\segments.csv");
        Console.WriteLine("  nvenc cmd: ffmpeg " + string.Join(" ", nvArgs));
        var nvGpu = CaptureArgs.Build(new CaptureConfig { Monitor = nv.Monitor, Fps = 60, Encoder = Encoders.Get("h264_qsv"), Method = CaptureMethod.DesktopDuplication, AudioBitrateKbps = 192 },
            PipelineLevel.DdaGpu, new string[0], "C:\\buf", "C:\\buf\\l.csv");
        Check(nvGpu.Contains("ddagrab=output_idx=2:framerate=60:draw_mouse=0,hwmap=derive_device=qsv,format=qsv[v]") && nvGpu.Contains("d3d11va=wcdx:1"), "qsv zero-copy graph + adapter selection");

        var psi = FFmpeg.NewStartInfo(FFmpeg.FfmpegPath, args, redirectStdin: true);
        var proc = new Process { StartInfo = psi };
        var err = new List<string>();
        proc.ErrorDataReceived += (_, e) => { if (e.Data != null) lock (err) err.Add(e.Data); };
        long frames = 0;
        proc.OutputDataReceived += (_, e) => { if (e.Data != null && e.Data.StartsWith("frame=")) long.TryParse(e.Data.Substring(6), out frames); };
        proc.Start();
        proc.BeginErrorReadLine();
        proc.BeginOutputReadLine();

        var cts = new CancellationTokenSource();
        var writers = fifos.Select((f, i) => Task.Run(() => WriteTone(f, freqs[i], cts.Token))).ToArray();

        var idx = new SegmentIndex(dir, list);
        var sw = Stopwatch.StartNew();
        while (sw.Elapsed.TotalSeconds < 7 && !proc.HasExited) { await Task.Delay(250); idx.Poll(); }
        idx.Poll();
        Check(!proc.HasExited, "ffmpeg still running after 7s");
        lock (err) Check(err.Any(l => l.Contains("testsrc2")), "verbose log visible (marker parsing relies on -loglevel verbose)");
        if (proc.HasExited) lock (err) Console.WriteLine("  ffmpeg stderr tail:\n    " + string.Join("\n    ", err.TakeLast(25)));
        Check(frames > 200, $"progress reports frames ({frames})");
        Check(idx.LatestEnd > 4, $"segments listed, latest end {idx.LatestEnd:F2}s");
        var all = idx.Range(double.NegativeInfinity, double.PositiveInfinity);
        Console.WriteLine("  segments: " + string.Join(" ", all.Select(s => $"[{s.Start:F2}-{s.End:F2}]")));
        Check(all.All(s => s.Duration > 0.9 && s.Duration < 1.1), "every segment is ~1s (keyframe each second)");

        // Save a 3 s "clip" ending at the latest closed segment, like SaveClipAsync does.
        double end = idx.LatestEnd;
        var segs = idx.Range(end - 3, end + 0.05);
        var infos = titles.Select(t => new PipeAudioWriterInfo("", t, null)).ToList();
        var outClip = Path.Combine(work, "clip.mp4");
        await ClipWriter.WriteAsync(segs, infos, outClip, "Harness clip");
        Check(File.Exists(outClip), "clip written");

        // Deleting old segments like the cleanup timer.
        idx.DeleteBefore(end - 2);
        Check(!File.Exists(all[0].File), "old segment deleted by cleanup");

        // Stop like StopAsync: 'q' on stdin.
        await proc.StandardInput.WriteAsync("q");
        await proc.StandardInput.FlushAsync();
        bool exited = proc.WaitForExit(8000);
        cts.Cancel();
        Check(exited, $"ffmpeg exits on 'q' (code {(exited ? proc.ExitCode : -1)})");
        if (!exited) proc.Kill(true);
        lock (err) if (err.Any(l => l.Contains("Error") || l.Contains("error"))) Console.WriteLine("  stderr errors:\n    " + string.Join("\n    ", err.Where(l => l.Contains("rror")).Take(10)));

        var info = await MediaProbe.ProbeAsync(outClip);
        Console.WriteLine($"  clip: {info.Width}x{info.Height} {info.Fps:F1}fps {info.Duration:F2}s audio=[{string.Join(" | ", info.Audio.Select(a => a.Title))}]");
        Check(info.Width == 1280 && info.Height == 720, "clip resolution");
        Check(info.Duration >= 2.9 && info.Duration <= 4.2, "clip duration ~3-4s");
        Check(info.Audio.Count == 3 && info.Audio.Select(a => a.Title).SequenceEqual(titles), "3 audio tracks with their names");
        await CheckToneTracks(outClip, freqs);
        return outClip;
    }

    static void WriteTone(string fifo, double freq, CancellationToken ct)
    {
        try
        {
            using var fs = new FileStream(fifo, FileMode.Open, FileAccess.Write);
            var sw = Stopwatch.StartNew();
            long written = 0;
            var buf = new float[480 * 2];
            var bytes = new byte[buf.Length * 4];
            while (!ct.IsCancellationRequested)
            {
                long due = (long)(sw.Elapsed.TotalSeconds * 48000);
                while (written + 480 <= due)
                {
                    for (int i = 0; i < 480; i++)
                    {
                        float v = (float)(0.3 * Math.Sin(2 * Math.PI * freq * (written + i) / 48000.0));
                        buf[i * 2] = v; buf[i * 2 + 1] = v;
                    }
                    Buffer.BlockCopy(buf, 0, bytes, 0, bytes.Length);
                    fs.Write(bytes, 0, bytes.Length);
                    written += 480;
                }
                Thread.Sleep(5);
            }
        }
        catch (IOException) { }
    }

    /// <summary>Each track should carry its own tone - proves the tracks stay separate.</summary>
    static async Task CheckToneTracks(string file, double[] freqs)
    {
        for (int i = 1; i < freqs.Length; i++)
        {
            var raw = Path.Combine(Path.GetDirectoryName(file)!, $"t{i}.raw");
            await FFmpeg.RunAsync(new[] { "-v", "error", "-y", "-i", file, "-map", $"0:a:{i}", "-ac", "1", "-ar", "48000", "-f", "f32le", raw });
            var bytes = File.ReadAllBytes(raw);
            var s = new float[bytes.Length / 4];
            Buffer.BlockCopy(bytes, 0, s, 0, s.Length * 4);
            // Goertzel power at expected vs other frequency
            double P(double f)
            {
                int n = Math.Min(s.Length, 48000);
                double k = 2 * Math.Cos(2 * Math.PI * f / 48000), q1 = 0, q2 = 0;
                for (int j = s.Length - n; j < s.Length; j++) { double q0 = k * q1 - q2 + s[j]; q2 = q1; q1 = q0; }
                return q1 * q1 + q2 * q2 - k * q1 * q2;
            }
            double own = P(freqs[i]), other = P(freqs[i == 1 ? 2 : 1]);
            Check(own > other * 100, $"track {i} contains only its own {freqs[i]} Hz tone (ratio {own / Math.Max(other, 1e-9):E1})");
        }
    }

    // ------------------------------------------------------------------ export
    static async Task ExportTests(string clip, string work)
    {
        Console.WriteLine("\n== Editor export");
        // Make a longer 3-track source (10 s) from the clip for more interesting edits.
        var src = Path.Combine(work, "src.mp4");
        var r0 = await FFmpeg.RunAsync(new[] { "-v", "error", "-y", "-stream_loop", "3", "-i", clip, "-map", "0", "-t", "10", "-c", "copy",
            "-metadata:s:a:0", "title=Mix (all)", "-metadata:s:a:1", "title=Minecraft", "-metadata:s:a:2", "title=Discord", src });
        Check(r0.Success, "10s source built " + r0.StdErrTail);
        var info = await MediaProbe.ProbeAsync(src);
        Console.WriteLine($"  source {info.Duration:F2}s, {info.Audio.Count} audio: {string.Join(", ", info.Audio.Select(a => a.Title))}");

        var p = EditProject.Create(src, info, i => "#00E5FF");
        Check(p.Tracks[0].IsMix && p.Tracks[0].Muted, "recorded mix track detected and muted by default");
        p.Split(2); p.Split(4); p.Split(6); p.Split(8);
        Check(p.Segments.Count == 5, "5 segments after 4 splits");
        p.Segments[1].Removed = true;            // cut 2-4
        p.Segments[2].Speed = 0.5;               // slow-mo 4-6 -> 4s
        p.Segments[3].Speed = 2;                 // 6-8 -> 1s
        p.Segments[4].Speed = 0.25;              // 8-10 -> 8s (atempo chain)
        double expected = 2 + 4 + 1 + (info.Duration - 8) * 4;
        Check(Math.Abs(p.OutputDuration - expected) < 0.01, $"output duration model = {p.OutputDuration}");
        Check(Math.Abs(p.MapToOutput(5) - 4) < 0.001 && Math.Abs(p.MapToOutput(3) - 2) < 0.001, "MapToOutput across cut + slow-mo");
        p.Tracks[1].Volume = 1.4; p.Tracks[1].Offset = 0.12;
        p.Tracks[2].Volume = 0.5; p.Tracks[2].Offset = -0.08;
        p.Texts.Add(new TextOverlay { Text = "INSANE: 1v4 'clutch' 100%", Start = 0.5, End = 5, Style = TextStyle.WaveGlow });
        p.Texts.Add(new TextOverlay { Text = "gg", Start = 6, End = 9, Style = TextStyle.Box, Color = "#FFC940", Y = 0.8 });
        p.Texts.Add(new TextOverlay { Text = "outline", Start = 1, End = 2, Style = TextStyle.Outline });
        p.Texts.Add(new TextOverlay { Text = "shadow", Start = 1, End = 2, Style = TextStyle.Plain });
        p.Look = ColorLook.Wave; p.Vignette = true; p.Sharpen = true; p.Brightness = 0.02;
        p.FadeIn = 0.5; p.FadeOut = 1;

        // Round trip through JSON like undo/redo and the autosave.
        var rt = EditProject.Deserialize(p.Serialize());
        Check(rt.Segments.Count == 5 && rt.Texts.Count == 4 && rt.Look == ColorLook.Wave, "project JSON round-trip");

        await Export(p, new ExportOptions { MixDown = true, Quality = 70 }, "mix.mp4", expected, 1, work);
        await Export(p, new ExportOptions { MixDown = false, Quality = 70 }, "separate.mp4", expected, 2, work);

        var v = EditProject.Deserialize(p.Serialize());
        v.Aspect = "9:16"; v.Zoom = 1.2; v.CropX = 0.3;
        var crop = v.CropRect();
        Console.WriteLine($"  9:16 crop = {crop.w}x{crop.h}+{crop.x}+{crop.y}");
        Check(crop.w % 2 == 0 && crop.h % 2 == 0 && crop.x >= 0 && crop.x + crop.w <= 1280, "crop rect even and inside frame");
        var outV = await Export(v, new ExportOptions { Height = 1920, AllowUpscale = true, Fps = 30 }, "shorts.mp4", expected, 1, work);
        if (outV != null) Check(outV.Height == 1920 && outV.Width == 1080, $"shorts output 1080x1920 (got {outV.Width}x{outV.Height})");

        var small = EditProject.Deserialize(p.Serialize());
        var outS = await Export(small, new ExportOptions { TargetMB = 1, Height = 1080 }, "discord.mp4", expected, 1, work);
        long size = new FileInfo(Path.Combine(work, "discord.mp4")).Length;
        Check(size < 1_100_000, $"1 MB target respected ({size / 1024} KB)");

        await Export(p, new ExportOptions { Gif = true, Height = 240 }, "clip.gif", expected, 0, work);

        // Single piece, no audio tracks audible
        var q = EditProject.Deserialize(p.Serialize());
        foreach (var t in q.Tracks) t.Muted = true;
        q.Texts.Clear();
        await Export(q, new ExportOptions(), "silent.mp4", expected, 0, work);

        // FX frame preview
        var png = Path.Combine(work, "fx.png");
        var plan = ExportBuilder.BuildFramePreview(p, 1.5, png);
        var r = await FFmpeg.RunAsync(plan.Args, workDir: plan.WorkDir);
        Check(r.Success && File.Exists(png), "FX frame preview renders " + (r.Success ? "" : r.StdErrTail));
    }

    static async Task<MediaInfo> Export(EditProject p, ExportOptions o, string name, double expected, int audioTracks, string work)
    {
        o.OutputPath = Path.Combine(work, name);
        var plan = ExportBuilder.Build(p, o);
        double last = 0;
        var r = await FFmpeg.RunAsync(plan.Args, onStdout: l => { if (l.StartsWith("out_time_us=") && long.TryParse(l.Substring(12), out var us)) last = us / 1e6; }, workDir: plan.WorkDir);
        if (!r.Success)
        {
            Check(false, $"{name} export failed:\n{r.StdErrTail}\n  graph: {plan.Args[plan.Args.IndexOf("-filter_complex") + 1]}");
            return null;
        }
        var info = await MediaProbe.ProbeAsync(o.OutputPath);
        Console.WriteLine($"  {name}: {info.Width}x{info.Height} {info.Fps:F1}fps {info.Duration:F2}s audio={info.Audio.Count} [{string.Join(", ", info.Audio.Select(a => a.Title))}] progress={last:F1}s {plan.Notes}");
        Check(Math.Abs(info.Duration - expected) < 0.35, $"{name} duration {info.Duration:F2} ≈ {expected}");
        Check(info.Audio.Count == audioTracks, $"{name} has {audioTracks} audio track(s)");
        return info;
    }
}
