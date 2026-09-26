using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace WaveClips.Core
{
    public sealed class AudioStreamInfo
    {
        public int Index { get; set; }          // audio-relative index (0:a:N)
        public string Title { get; set; } = "";
        public int Channels { get; set; } = 2;
    }

    public sealed class MediaInfo
    {
        public double Duration { get; set; }
        public int Width { get; set; }
        public int Height { get; set; }
        public double Fps { get; set; } = 60;
        public string VideoCodec { get; set; } = "";
        public List<AudioStreamInfo> Audio { get; } = new List<AudioStreamInfo>();
    }

    public static class MediaProbe
    {
        public static async Task<MediaInfo> ProbeAsync(string path, CancellationToken ct = default)
        {
            if (FFmpeg.FfprobePath != null)
            {
                var json = await FFmpeg.ProbeJsonAsync(path, ct);
                if (!string.IsNullOrWhiteSpace(json))
                {
                    try { return ParseJson(json); } catch (Exception ex) { Log.Warn("ffprobe parse: " + ex.Message); }
                }
            }
            return await ProbeWithFfmpeg(path, ct);
        }

        private static MediaInfo ParseJson(string json)
        {
            var info = new MediaInfo();
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            if (root.TryGetProperty("format", out var fmt) && fmt.TryGetProperty("duration", out var d))
                info.Duration = ParseDouble(d.GetString());
            int a = 0;
            if (root.TryGetProperty("streams", out var streams))
            {
                foreach (var s in streams.EnumerateArray())
                {
                    var type = s.TryGetProperty("codec_type", out var t) ? t.GetString() : "";
                    if (type == "video" && info.Width == 0)
                    {
                        if (s.TryGetProperty("disposition", out var disp) && disp.TryGetProperty("attached_pic", out var ap) && ap.GetInt32() == 1) continue;
                        info.Width = s.TryGetProperty("width", out var w) ? w.GetInt32() : 0;
                        info.Height = s.TryGetProperty("height", out var h) ? h.GetInt32() : 0;
                        info.VideoCodec = s.TryGetProperty("codec_name", out var cn) ? cn.GetString() : "";
                        // r_frame_rate is the nominal rate (60/1) - avg_frame_rate of joined segments reads like 59.11.
                        double r = ParseRate(s.TryGetProperty("r_frame_rate", out var rr) ? rr.GetString() : null);
                        double avg = ParseRate(s.TryGetProperty("avg_frame_rate", out var ar) ? ar.GetString() : null);
                        info.Fps = r > 0 && r <= 360 ? r : avg;
                        if (info.Duration <= 0 && s.TryGetProperty("duration", out var sd)) info.Duration = ParseDouble(sd.GetString());
                    }
                    else if (type == "audio")
                    {
                        string title = "";
                        if (s.TryGetProperty("tags", out var tags))
                        {
                            if (tags.TryGetProperty("title", out var tt)) title = tt.GetString();
                            else if (tags.TryGetProperty("handler_name", out var hn)) title = hn.GetString();
                        }
                        if (IsGenericHandler(title)) title = "";
                        info.Audio.Add(new AudioStreamInfo
                        {
                            Index = a++,
                            Title = string.IsNullOrWhiteSpace(title) ? $"Track {a}" : title,
                            Channels = s.TryGetProperty("channels", out var ch) ? ch.GetInt32() : 2,
                        });
                    }
                }
            }
            return info;
        }

        private static bool IsGenericHandler(string t) =>
            string.IsNullOrWhiteSpace(t) || t.Equals("SoundHandler", StringComparison.OrdinalIgnoreCase) ||
            t.Equals("Sound Media Handler", StringComparison.OrdinalIgnoreCase) || t.StartsWith("Core Media Audio", StringComparison.OrdinalIgnoreCase);

        private static async Task<MediaInfo> ProbeWithFfmpeg(string path, CancellationToken ct)
        {
            var info = new MediaInfo();
            var sb = new StringBuilder();
            await FFmpeg.RunAsync(new[] { "-hide_banner", "-i", path }, ct, onStderr: l => { lock (sb) sb.AppendLine(l); });
            var text = sb.ToString();
            var m = Regex.Match(text, @"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)");
            if (m.Success)
                info.Duration = int.Parse(m.Groups[1].Value) * 3600 + int.Parse(m.Groups[2].Value) * 60 + ParseDouble(m.Groups[3].Value);
            var v = Regex.Match(text, @"Video:\s*(\w+).*?,\s*(\d{2,5})x(\d{2,5})");
            if (v.Success)
            {
                info.VideoCodec = v.Groups[1].Value;
                info.Width = int.Parse(v.Groups[2].Value);
                info.Height = int.Parse(v.Groups[3].Value);
            }
            var f = Regex.Match(text, @"(\d+(?:\.\d+)?)\s*fps");
            if (f.Success) info.Fps = ParseDouble(f.Groups[1].Value);

            var lines = text.Split('\n');
            int a = 0;
            for (int i = 0; i < lines.Length; i++)
            {
                if (!Regex.IsMatch(lines[i], @"Stream #\d+:\d+.*Audio:")) continue;
                string title = "";
                for (int j = i + 1; j < lines.Length && j < i + 6; j++)
                {
                    if (lines[j].Contains("Stream #")) break;
                    var tm = Regex.Match(lines[j], @"^\s*(title|handler_name)\s*:\s*(.+)$");
                    if (tm.Success && (title == "" || tm.Groups[1].Value == "title")) title = tm.Groups[2].Value.Trim();
                }
                if (IsGenericHandler(title)) title = "";
                a++;
                info.Audio.Add(new AudioStreamInfo { Index = a - 1, Title = title == "" ? $"Track {a}" : title });
            }
            return info;
        }

        private static double ParseDouble(string s) =>
            double.TryParse(s, NumberStyles.Float, CultureInfo.InvariantCulture, out var d) ? d : 0;

        private static double ParseRate(string r)
        {
            if (string.IsNullOrEmpty(r) || r == "0/0") return 0;
            var p = r.Split('/');
            if (p.Length == 2 && double.TryParse(p[0], NumberStyles.Float, CultureInfo.InvariantCulture, out var n) &&
                double.TryParse(p[1], NumberStyles.Float, CultureInfo.InvariantCulture, out var d) && d > 0)
                return n / d;
            return ParseDouble(r);
        }
    }
}
