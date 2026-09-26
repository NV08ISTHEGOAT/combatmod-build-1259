using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace WaveClips.Core
{
    public sealed class FfResult
    {
        public int ExitCode;
        public string StdErrTail = "";
        public bool Success => ExitCode == 0;
    }

    /// <summary>Finds, downloads and runs ffmpeg.exe / ffprobe.exe.</summary>
    public static class FFmpeg
    {
        public static string FfmpegPath { get; private set; }
        public static string FfprobePath { get; private set; }
        public static string Version { get; private set; } = "";
        public static bool Available => FfmpegPath != null;

        public static event Action AvailabilityChanged;

        private static readonly string[] DownloadUrls =
        {
            "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip",
            "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip",
        };

        public static bool Locate(AppSettings settings)
        {
            var candidates = new List<string>();
            if (!string.IsNullOrWhiteSpace(settings.FfmpegPath)) candidates.Add(settings.FfmpegPath);
            candidates.Add(Path.Combine(Paths.AppDir, "ffmpeg.exe"));
            candidates.Add(Path.Combine(Paths.AppDir, "ffmpeg", "ffmpeg.exe"));
            candidates.Add(Path.Combine(Paths.AppDir, "ffmpeg", "bin", "ffmpeg.exe"));
            candidates.Add(Path.Combine(Paths.FfmpegDir, "ffmpeg.exe"));
            foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator))
            {
                if (string.IsNullOrWhiteSpace(dir)) continue;
                try { candidates.Add(Path.Combine(dir.Trim().Trim('"'), "ffmpeg.exe")); } catch { }
            }

            FfmpegPath = candidates.FirstOrDefault(File.Exists);
            FfprobePath = null;
            if (FfmpegPath != null)
            {
                var probe = Path.Combine(Path.GetDirectoryName(FfmpegPath)!, "ffprobe.exe");
                FfprobePath = File.Exists(probe) ? probe : null;
                Version = ReadVersion();
                Log.Info($"FFmpeg: {FfmpegPath} ({Version}), ffprobe: {FfprobePath ?? "missing"}");
            }
            AvailabilityChanged?.Invoke();
            return FfmpegPath != null;
        }

        /// <summary>Identifies the ffmpeg build (for caching encoder test results).</summary>
        public static string BuildKey()
        {
            if (FfmpegPath == null) return "";
            var fi = new FileInfo(FfmpegPath);
            return $"{FfmpegPath}|{fi.Length}|{fi.LastWriteTimeUtc.Ticks}";
        }

        private static string ReadVersion()
        {
            try
            {
                var psi = NewStartInfo(FfmpegPath, new[] { "-hide_banner", "-version" });
                using var p = Process.Start(psi)!;
                var first = p.StandardOutput.ReadLine() ?? "";
                p.WaitForExit(3000);
                // "ffmpeg version N-117000-g... Copyright ..."
                var parts = first.Split(' ');
                return parts.Length > 2 ? parts[2] : first;
            }
            catch { return "unknown"; }
        }

        public static ProcessStartInfo NewStartInfo(string exe, IEnumerable<string> args, bool redirectStdin = false)
        {
            var psi = new ProcessStartInfo(exe)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardError = true,
                RedirectStandardOutput = true,
                RedirectStandardInput = redirectStdin,
                StandardErrorEncoding = Encoding.UTF8,
                StandardOutputEncoding = Encoding.UTF8,
            };
            foreach (var a in args) psi.ArgumentList.Add(a);
            return psi;
        }

        /// <summary>Runs ffmpeg (or ffprobe) to completion. Cancelling kills the process.</summary>
        public static async Task<FfResult> RunAsync(IEnumerable<string> args, CancellationToken ct = default,
            Action<string> onStderr = null, Action<string> onStdout = null, bool probe = false, ProcessPriorityClass? priority = null,
            string workDir = null)
        {
            var exe = probe ? FfprobePath : FfmpegPath;
            if (exe == null) throw new InvalidOperationException(probe ? "ffprobe.exe was not found" : "ffmpeg.exe was not found");
            var argList = args.ToList();
            var tail = new LinkedList<string>();
            var psi = NewStartInfo(exe, argList);
            if (workDir != null) psi.WorkingDirectory = workDir;
            using var p = new Process { StartInfo = psi, EnableRaisingEvents = true };
            var done = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            p.ErrorDataReceived += (_, e) =>
            {
                if (e.Data == null) return;
                lock (tail) { tail.AddLast(e.Data); if (tail.Count > 30) tail.RemoveFirst(); }
                onStderr?.Invoke(e.Data);
            };
            p.OutputDataReceived += (_, e) => { if (e.Data != null) onStdout?.Invoke(e.Data); };
            p.Exited += (_, __) => done.TrySetResult(true);
            p.Start();
            Interop.Native.TieToLifetime(p);
            if (priority.HasValue) { try { p.PriorityClass = priority.Value; } catch { } }
            p.BeginErrorReadLine();
            p.BeginOutputReadLine();
            using (ct.Register(() => { try { if (!p.HasExited) p.Kill(true); } catch { } }))
                await done.Task.ConfigureAwait(false);
            p.WaitForExit(); // flush async readers
            ct.ThrowIfCancellationRequested();
            string t;
            lock (tail) t = string.Join(Environment.NewLine, tail);
            return new FfResult { ExitCode = p.ExitCode, StdErrTail = t };
        }

        /// <summary>Returns stdout of ffprobe as a string (or "" on failure).</summary>
        public static async Task<string> ProbeJsonAsync(string file, CancellationToken ct = default)
        {
            if (FfprobePath == null) return "";
            var sb = new StringBuilder();
            var r = await RunAsync(new[] { "-v", "error", "-print_format", "json", "-show_format", "-show_streams", file },
                ct, onStdout: l => { lock (sb) sb.AppendLine(l); }, probe: true).ConfigureAwait(false);
            return r.Success ? sb.ToString() : "";
        }

        /// <summary>Downloads a Windows FFmpeg build into %LocalAppData%\WaveClips\ffmpeg.</summary>
        public static async Task DownloadAsync(IProgress<(double fraction, string status)> progress, CancellationToken ct)
        {
            Directory.CreateDirectory(Paths.FfmpegDir);
            var zipPath = Path.Combine(Paths.Temp, "ffmpeg-download.zip");
            Exception last = null;
            using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(15) };
            http.DefaultRequestHeaders.UserAgent.ParseAdd("WaveClips/1.0");

            foreach (var url in DownloadUrls)
            {
                try
                {
                    progress?.Report((0, "Connecting…"));
                    using (var resp = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct))
                    {
                        resp.EnsureSuccessStatusCode();
                        long total = resp.Content.Headers.ContentLength ?? -1;
                        await using var src = await resp.Content.ReadAsStreamAsync(ct);
                        await using var dst = File.Create(zipPath);
                        var buf = new byte[1 << 16];
                        long read = 0;
                        int n;
                        while ((n = await src.ReadAsync(buf, ct)) > 0)
                        {
                            await dst.WriteAsync(buf.AsMemory(0, n), ct);
                            read += n;
                            if (total > 0) progress?.Report((read / (double)total * 0.9, $"Downloading… {read / 1048576} / {total / 1048576} MB"));
                            else progress?.Report((0.5, $"Downloading… {read / 1048576} MB"));
                        }
                    }

                    progress?.Report((0.92, "Extracting…"));
                    using (var zip = ZipFile.OpenRead(zipPath))
                    {
                        foreach (var name in new[] { "ffmpeg.exe", "ffprobe.exe" })
                        {
                            var entry = zip.Entries.FirstOrDefault(e => e.FullName.EndsWith("/bin/" + name, StringComparison.OrdinalIgnoreCase))
                                        ?? zip.Entries.FirstOrDefault(e => e.Name.Equals(name, StringComparison.OrdinalIgnoreCase));
                            if (entry == null) throw new InvalidDataException(name + " not found in download");
                            entry.ExtractToFile(Path.Combine(Paths.FfmpegDir, name), true);
                        }
                    }
                    try { File.Delete(zipPath); } catch { }
                    progress?.Report((1, "FFmpeg installed"));
                    return;
                }
                catch (OperationCanceledException) { throw; }
                catch (Exception ex)
                {
                    last = ex;
                    Log.Warn($"FFmpeg download from {url} failed: {ex.Message}");
                }
            }
            throw new Exception("Could not download FFmpeg: " + last?.Message, last);
        }
    }
}
