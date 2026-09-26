using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace WaveClips.Core
{
    public sealed class EncoderInfo
    {
        public EncoderInfo(string id, string label, string vendor, string codec) { Id = id; Label = label; Vendor = vendor; Codec = codec; }
        public string Id { get; }
        public string Label { get; }
        public string Vendor { get; }
        public string Codec { get; }
        public bool IsHardware => Vendor != "CPU";
        public bool IsNvenc => Id.EndsWith("_nvenc");
        public bool IsAmf => Id.EndsWith("_amf");
        public bool IsQsv => Id.EndsWith("_qsv");
        /// <summary>AV1 can't go into the MPEG-TS replay segments reliably, so it's offered for export only.</summary>
        public bool CanCapture => Codec != "AV1";
        public override string ToString() => Label;
    }

    public static class Encoders
    {
        public static readonly EncoderInfo[] All =
        {
            new EncoderInfo("h264_nvenc", "NVIDIA NVENC  ·  H.264 (best compatibility)", "NVIDIA", "H.264"),
            new EncoderInfo("hevc_nvenc", "NVIDIA NVENC  ·  HEVC / H.265", "NVIDIA", "HEVC"),
            new EncoderInfo("av1_nvenc",  "NVIDIA NVENC  ·  AV1 (RTX 40+)", "NVIDIA", "AV1"),
            new EncoderInfo("h264_amf",   "AMD AMF  ·  H.264", "AMD", "H.264"),
            new EncoderInfo("hevc_amf",   "AMD AMF  ·  HEVC / H.265", "AMD", "HEVC"),
            new EncoderInfo("av1_amf",    "AMD AMF  ·  AV1 (RX 7000+)", "AMD", "AV1"),
            new EncoderInfo("h264_qsv",   "Intel Quick Sync  ·  H.264", "Intel", "H.264"),
            new EncoderInfo("hevc_qsv",   "Intel Quick Sync  ·  HEVC / H.265", "Intel", "HEVC"),
            new EncoderInfo("av1_qsv",    "Intel Quick Sync  ·  AV1 (Arc)", "Intel", "AV1"),
            new EncoderInfo("libx264",    "CPU  ·  x264 H.264 (slowest, always works)", "CPU", "H.264"),
        };

        private static readonly string[] AutoOrder = { "h264_nvenc", "h264_amf", "h264_qsv", "libx264" };

        /// <summary>Encoders that passed the self test on this PC.</summary>
        public static IReadOnlyList<EncoderInfo> Available { get; private set; } = new[] { Get("libx264") };

        public static EncoderInfo Get(string id) => All.FirstOrDefault(e => e.Id == id) ?? All.Last();

        public static EncoderInfo Resolve(string setting)
        {
            if (!string.IsNullOrEmpty(setting) && setting != "auto" && Available.Any(e => e.Id == setting) && Get(setting).CanCapture) return Get(setting);
            foreach (var id in AutoOrder)
                if (Available.Any(e => e.Id == id)) return Get(id);
            return Get("libx264");
        }

        /// <summary>Test-encodes a few frames with every candidate encoder (cached per FFmpeg build).</summary>
        public static async Task DetectAsync(AppSettings settings, bool force = false)
        {
            if (!FFmpeg.Available) return;
            var key = FFmpeg.BuildKey();
            if (!force && settings.EncoderCacheKey == key && settings.EncoderCache.Length > 0)
            {
                Available = All.Where(e => settings.EncoderCache.Contains(e.Id)).ToArray();
                return;
            }

            var ok = new List<string>();
            var gate = new SemaphoreSlim(3);
            var tasks = All.Select(async enc =>
            {
                await gate.WaitAsync();
                try
                {
                    var pix = enc.Id == "libx264" ? "yuv420p" : "nv12";
                    using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(15));
                    var r = await FFmpeg.RunAsync(new[]
                    {
                        "-hide_banner", "-v", "error", "-f", "lavfi", "-i", "color=c=black:s=1280x720:r=30",
                        "-frames:v", "5", "-pix_fmt", pix, "-c:v", enc.Id, "-f", "null", "-",
                    }, cts.Token);
                    if (r.Success) lock (ok) ok.Add(enc.Id);
                }
                catch { /* not available */ }
                finally { gate.Release(); }
            });
            await Task.WhenAll(tasks);
            if (!ok.Contains("libx264")) ok.Add("libx264");
            Available = All.Where(e => ok.Contains(e.Id)).ToArray();
            settings.EncoderCache = Available.Select(e => e.Id).ToArray();
            settings.EncoderCacheKey = key;
            settings.Save();
            Log.Info("Encoders available: " + string.Join(", ", settings.EncoderCache));
        }

        /// <summary>Maps the 1..100 quality slider to a CRF/CQ value (lower = better).</summary>
        public static int QualityToCq(int quality) => (int)Math.Round(38 - quality * 0.22);

        /// <summary>FFmpeg encoder arguments for the given settings.</summary>
        public static List<string> VideoArgs(EncoderInfo enc, RateControl rc, int quality, double bitrateMbps,
                                             EncoderSpeed speed, int fps, bool keyframeEverySecond)
        {
            var a = new List<string> { "-c:v", enc.Id };
            int cq = QualityToCq(quality);
            string br = bitrateMbps.ToString("0.###", CultureInfo.InvariantCulture) + "M";
            string max = (bitrateMbps * 1.5).ToString("0.###", CultureInfo.InvariantCulture) + "M";
            string buf = (bitrateMbps * 2).ToString("0.###", CultureInfo.InvariantCulture) + "M";

            if (enc.IsNvenc)
            {
                a.AddRange(new[] { "-preset", speed switch { EncoderSpeed.Fast => "p1", EncoderSpeed.Quality => "p6", _ => "p4" }, "-tune", "hq" });
                if (rc == RateControl.ConstantQuality) a.AddRange(new[] { "-rc", "vbr", "-cq", cq.ToString(), "-b:v", "0" });
                else a.AddRange(new[] { "-rc", "vbr", "-b:v", br, "-maxrate", max, "-bufsize", buf });
                if (keyframeEverySecond) a.AddRange(new[] { "-forced-idr", "1" });
            }
            else if (enc.IsAmf)
            {
                a.AddRange(new[] { "-quality", speed switch { EncoderSpeed.Fast => "speed", EncoderSpeed.Quality => "quality", _ => "balanced" } });
                if (rc == RateControl.ConstantQuality)
                    a.AddRange(new[] { "-rc", "cqp", "-qp_i", cq.ToString(), "-qp_p", (cq + 2).ToString() });
                else a.AddRange(new[] { "-rc", "vbr_peak", "-b:v", br, "-maxrate", max, "-bufsize", buf });
            }
            else if (enc.IsQsv)
            {
                a.AddRange(new[] { "-preset", speed switch { EncoderSpeed.Fast => "veryfast", EncoderSpeed.Quality => "slower", _ => "medium" } });
                if (rc == RateControl.ConstantQuality) a.AddRange(new[] { "-global_quality", cq.ToString() });
                else a.AddRange(new[] { "-b:v", br, "-maxrate", max, "-bufsize", buf });
            }
            else
            {
                a.AddRange(new[] { "-preset", speed switch { EncoderSpeed.Fast => "ultrafast", EncoderSpeed.Quality => "medium", _ => "veryfast" } });
                if (rc == RateControl.ConstantQuality) a.AddRange(new[] { "-crf", cq.ToString() });
                else a.AddRange(new[] { "-b:v", br, "-maxrate", max, "-bufsize", buf });
                if (keyframeEverySecond) a.AddRange(new[] { "-keyint_min", fps.ToString(), "-sc_threshold", "0" });
            }

            if (keyframeEverySecond)
            {
                a.AddRange(new[] { "-g", fps.ToString(), "-force_key_frames", "expr:gte(t,n_forced*1)" });
            }
            return a;
        }
    }
}
