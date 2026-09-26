using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Media;
using NAudio.Wave;
using WaveClips.Core;

namespace WaveClips.Editor
{
    /// <summary>Everything the editor pre-computes for a clip: per-track WAVs, waveform peaks, thumbnails.</summary>
    public sealed class EditorMedia
    {
        public const int PeaksPerSecond = 100;

        public string CacheDir { get; private set; }
        public string[] TrackWavs { get; private set; } = Array.Empty<string>();
        public float[][] Peaks { get; private set; } = Array.Empty<float[]>();
        public List<(double time, ImageSource image)> Thumbs { get; } = new List<(double, ImageSource)>();

        private static readonly CultureInfo Inv = CultureInfo.InvariantCulture;

        public static string CacheFor(string path)
        {
            var fi = new FileInfo(path);
            var key = $"{path}|{fi.Length}|{fi.LastWriteTimeUtc.Ticks}";
            var hash = Convert.ToHexString(SHA1.HashData(Encoding.UTF8.GetBytes(key))).Substring(0, 16);
            return Paths.Ensure(Path.Combine(Paths.EditorCache, hash));
        }

        public static string ProjectFileFor(string path) => Path.Combine(CacheFor(path), "project.json");

        public static async Task<EditorMedia> PrepareAsync(string path, MediaInfo info, IProgress<string> status, CancellationToken ct)
        {
            var m = new EditorMedia { CacheDir = CacheFor(path) };
            PruneOldCaches(m.CacheDir);

            // ---- audio tracks -> wav (for multi-track preview) ----
            int n = info.Audio.Count;
            m.TrackWavs = Enumerable.Range(0, n).Select(i => Path.Combine(m.CacheDir, $"track{i}.wav")).ToArray();
            if (n > 0 && m.TrackWavs.Any(f => !File.Exists(f)))
            {
                status?.Report($"Separating {n} audio track{(n == 1 ? "" : "s")}…");
                var args = new List<string> { "-hide_banner", "-v", "error", "-y", "-i", path };
                for (int i = 0; i < n; i++)
                    args.AddRange(new[] { "-map", $"0:a:{i}", "-ac", "2", "-ar", "48000", "-c:a", "pcm_s16le", m.TrackWavs[i] + ".part.wav" });
                var r = await FFmpeg.RunAsync(args, ct);
                if (!r.Success) throw new Exception("Couldn't read the clip's audio: " + r.StdErrTail);
                for (int i = 0; i < n; i++) File.Move(m.TrackWavs[i] + ".part.wav", m.TrackWavs[i], true);
            }

            // ---- waveforms ----
            status?.Report("Drawing waveforms…");
            m.Peaks = await Task.Run(() => m.TrackWavs.Select(LoadPeaks).ToArray(), ct);

            // ---- thumbnails ----
            status?.Report("Making thumbnails…");
            double dur = Math.Max(0.5, info.Duration);
            int count = (int)Math.Clamp(dur / 1.5, 8, 160);
            var thumbDir = Paths.Ensure(Path.Combine(m.CacheDir, "thumbs"));
            if (!File.Exists(Path.Combine(thumbDir, "done")))
            {
                var r = await FFmpeg.RunAsync(new[]
                {
                    "-hide_banner", "-v", "error", "-y", "-i", path, "-map", "0:v:0",
                    "-vf", $"fps={(count / dur).ToString("0.######", Inv)},scale=-2:80", "-q:v", "6",
                    Path.Combine(thumbDir, "th_%04d.jpg"),
                }, ct);
                if (r.Success) File.WriteAllText(Path.Combine(thumbDir, "done"), "");
            }
            var files = Directory.GetFiles(thumbDir, "th_*.jpg").OrderBy(f => f).ToArray();
            double step = dur / Math.Max(1, files.Length);
            for (int i = 0; i < files.Length; i++)
            {
                var img = ClipLibrary.LoadImage(files[i]);
                if (img != null) m.Thumbs.Add((i * step, img));
            }
            return m;
        }

        /// <summary>H.264 copy of the video for previewing codecs Windows can't play (HEVC/AV1 without extensions).</summary>
        public static async Task<string> MakeProxyAsync(string path, IProgress<string> status, CancellationToken ct)
        {
            var proxy = Path.Combine(CacheFor(path), "proxy.mp4");
            if (File.Exists(proxy)) return proxy;
            status?.Report("Making a preview copy (this clip's codec can't be previewed directly)…");
            var r = await FFmpeg.RunAsync(new[]
            {
                "-hide_banner", "-v", "error", "-y", "-i", path, "-map", "0:v:0", "-an",
                "-vf", "scale=-2:'min(720,ih)'", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "24", "-pix_fmt", "yuv420p",
                proxy + ".part.mp4",
            }, ct);
            if (!r.Success) throw new Exception("Preview conversion failed: " + r.StdErrTail);
            File.Move(proxy + ".part.mp4", proxy, true);
            return proxy;
        }

        private static float[] LoadPeaks(string wav)
        {
            var cache = wav + ".peaks";
            try
            {
                if (File.Exists(cache))
                {
                    var bytes = File.ReadAllBytes(cache);
                    var arr = new float[bytes.Length / 4];
                    Buffer.BlockCopy(bytes, 0, arr, 0, arr.Length * 4);
                    return arr;
                }
                using var reader = new WaveFileReader(wav);
                int bucketFrames = reader.WaveFormat.SampleRate / PeaksPerSecond;
                int ch = reader.WaveFormat.Channels;
                var peaks = new List<float>((int)(reader.TotalTime.TotalSeconds * PeaksPerSecond) + 1);
                var buf = new byte[bucketFrames * ch * 2 * 50];
                float cur = 0; int inBucket = 0;
                int read;
                while ((read = reader.Read(buf, 0, buf.Length)) > 0)
                {
                    for (int i = 0; i + 1 < read; i += 2 * ch)
                    {
                        float v = 0;
                        for (int c = 0; c < ch; c++)
                        {
                            float s = Math.Abs(BitConverter.ToInt16(buf, i + c * 2) / 32768f);
                            if (s > v) v = s;
                        }
                        if (v > cur) cur = v;
                        if (++inBucket >= bucketFrames) { peaks.Add(cur); cur = 0; inBucket = 0; }
                    }
                }
                if (inBucket > 0) peaks.Add(cur);
                var result = peaks.ToArray();
                var outBytes = new byte[result.Length * 4];
                Buffer.BlockCopy(result, 0, outBytes, 0, outBytes.Length);
                File.WriteAllBytes(cache, outBytes);
                return result;
            }
            catch (Exception ex)
            {
                Log.Warn("Waveform: " + ex.Message);
                return Array.Empty<float>();
            }
        }

        /// <summary>Keeps the editor cache from growing forever (keeps the 12 most recent clips).</summary>
        private static void PruneOldCaches(string keep)
        {
            try
            {
                var dirs = new DirectoryInfo(Paths.EditorCache).GetDirectories().OrderByDescending(d => d.LastWriteTimeUtc).ToList();
                foreach (var d in dirs.Skip(12))
                    if (!string.Equals(d.FullName.TrimEnd('\\', '/'), keep.TrimEnd('\\', '/'), StringComparison.OrdinalIgnoreCase))
                        d.Delete(true);
                Directory.SetLastWriteTimeUtc(keep, DateTime.UtcNow);
            }
            catch { }
        }
    }
}
