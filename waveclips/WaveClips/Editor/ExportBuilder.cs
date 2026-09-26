using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using WaveClips.Core;

namespace WaveClips.Editor
{
    public sealed class ExportOptions
    {
        /// <summary>Output height (0 = keep the cropped source size).</summary>
        public int Height { get; set; }
        /// <summary>Allow Height above the cropped source (vertical presets: 608x1080 crop -> 1080x1920).</summary>
        public bool AllowUpscale { get; set; }
        /// <summary>0 = source frame rate.</summary>
        public double Fps { get; set; }
        public int Quality { get; set; } = 80;
        /// <summary>Target file size in MB (0 = quality based).</summary>
        public double TargetMB { get; set; }
        public bool MixDown { get; set; } = true;
        public bool Gif { get; set; }
        public int AudioKbps { get; set; } = 192;
        public string Encoder { get; set; } = "auto";
        public string OutputPath { get; set; }
    }

    public sealed class ExportPlan
    {
        public List<string> Args { get; } = new List<string>();
        public string WorkDir { get; init; }
        public double OutputDuration { get; init; }
        public string Notes { get; set; } = "";
    }

    /// <summary>Builds the ffmpeg command for an edit.</summary>
    public static class ExportBuilder
    {
        /// <summary>Font used for text overlays instead of the Windows fonts (portable installs / tests).</summary>
        public static string FontOverride { get; set; }

        private static readonly CultureInfo Inv = CultureInfo.InvariantCulture;
        private static string F(double v) => v.ToString("0.######", Inv);

        public static ExportPlan Build(EditProject p, ExportOptions o)
        {
            var kept = p.Kept.ToList();
            if (kept.Count == 0) throw new InvalidOperationException("Everything is cut - nothing left to export.");
            double outDur = kept.Sum(s => s.OutputLength);
            var work = Paths.Ensure(Path.Combine(Paths.Temp, "export_" + Guid.NewGuid().ToString("N").Substring(0, 8)));
            var plan = new ExportPlan { WorkDir = work, OutputDuration = outDur };
            var a = plan.Args;

            // Seek close to the first kept frame so long recordings export quickly.
            double seek = Math.Max(0, kept[0].Start - 2);
            double inputLen = kept[^1].End - seek + 1;
            a.AddRange(new[] { "-hide_banner", "-y", "-nostats", "-progress", "pipe:1" });
            if (seek > 0) a.AddRange(new[] { "-ss", F(seek) });
            a.AddRange(new[] { "-t", F(inputLen), "-i", p.SourcePath });

            var g = new List<string>();
            int n = kept.Count;

            // ---------------- video segments ----------------
            g.Add(n == 1 ? "[0:v]null[vs0]" : $"[0:v]split={n}" + string.Concat(Enumerable.Range(0, n).Select(i => $"[vs{i}]")));
            for (int i = 0; i < n; i++)
            {
                var s = kept[i];
                string pts = Math.Abs(s.Speed - 1) < 1e-6 ? "setpts=PTS-STARTPTS" : $"setpts=(PTS-STARTPTS)/{F(s.Speed)}";
                g.Add($"[vs{i}]trim=start={F(s.Start - seek)}:end={F(s.End - seek)},{pts}[v{i}]");
            }

            // ---------------- audio ----------------
            var audible = o.Gif ? new List<TrackMix>() : p.AudibleTracks().ToList();
            bool hasAudio = audible.Count > 0;
            int outTracks = 0;
            for (int k = 0; k < audible.Count; k++)
            {
                var t = audible[k];
                var chain = new StringBuilder($"[0:a:{t.StreamIndex}]aresample=48000,aformat=channel_layouts=stereo");
                if (t.Offset > 0.001) chain.Append($",adelay=delays={(int)(t.Offset * 1000)}:all=1");
                else if (t.Offset < -0.001) chain.Append($",atrim=start={F(-t.Offset)},asetpts=PTS-STARTPTS");
                chain.Append($",volume={F(t.Volume)}[t{k}]");
                g.Add(chain.ToString());
            }

            string concatInputs;
            if (hasAudio && o.MixDown)
            {
                g.Add(audible.Count == 1 ? "[t0]anull[amixed]"
                    : string.Concat(Enumerable.Range(0, audible.Count).Select(k => $"[t{k}]")) +
                      $"amix=inputs={audible.Count}:normalize=0:duration=longest:dropout_transition=0[amixed]");
                g.Add(n == 1 ? "[amixed]anull[as0]" : $"[amixed]asplit={n}" + string.Concat(Enumerable.Range(0, n).Select(i => $"[as{i}]")));
                for (int i = 0; i < n; i++)
                    g.Add($"[as{i}]{ATrim(kept[i], seek)}[a{i}]");
                concatInputs = string.Concat(Enumerable.Range(0, n).Select(i => $"[v{i}][a{i}]"));
                g.Add($"{concatInputs}concat=n={n}:v=1:a=1[vc][ac]");
                outTracks = 1;
            }
            else if (hasAudio)
            {
                int K = audible.Count;
                for (int k = 0; k < K; k++)
                {
                    g.Add(n == 1 ? $"[t{k}]anull[t{k}s0]" : $"[t{k}]asplit={n}" + string.Concat(Enumerable.Range(0, n).Select(i => $"[t{k}s{i}]")));
                    for (int i = 0; i < n; i++) g.Add($"[t{k}s{i}]{ATrim(kept[i], seek)}[a{i}_{k}]");
                }
                var sb = new StringBuilder();
                for (int i = 0; i < n; i++)
                {
                    sb.Append($"[v{i}]");
                    for (int k = 0; k < K; k++) sb.Append($"[a{i}_{k}]");
                }
                g.Add($"{sb}concat=n={n}:v=1:a={K}[vc]" + string.Concat(Enumerable.Range(0, K).Select(k => $"[ac{k}]")));
                outTracks = K;
            }
            else
            {
                g.Add(string.Concat(Enumerable.Range(0, n).Select(i => $"[v{i}]")) + $"concat=n={n}:v=1:a=0[vc]");
            }

            // ---------------- size / bitrate decisions ----------------
            var crop = p.CropRect();
            int outH = o.Height > 0 ? (o.AllowUpscale ? o.Height : Math.Min(o.Height, crop.h)) : crop.h;
            int audioKbps = o.AudioKbps;
            double videoMbps = 0;
            if (o.TargetMB > 0 && !o.Gif)
            {
                if (o.TargetMB <= 12) audioKbps = Math.Min(audioKbps, 96);
                double totalBits = o.TargetMB * 8 * 1000 * 1000 * 0.92; // leave room for container overhead
                double audioBits = hasAudio ? audioKbps * 1000.0 * outTracks * outDur : 0;
                videoMbps = Math.Max(0.15, (totalBits - audioBits) / outDur / 1_000_000);
                if (videoMbps < 0.7 && outH > 480) { outH = 480; plan.Notes = "Lowered to 480p so it fits the size limit."; }
                else if (videoMbps < 1.6 && outH > 720) { outH = 720; plan.Notes = "Lowered to 720p so it fits the size limit."; }
            }
            outH = EditProject.Even(outH);
            int outW = p.OutputWidthFor(outH);
            double fps = o.Fps > 0 ? Math.Min(o.Fps, p.Fps > 0 ? p.Fps : o.Fps) : p.Fps;
            if (o.TargetMB > 0 && o.TargetMB <= 12 && fps > 30 && videoMbps < 1.2) { fps = 30; plan.Notes += " 30 fps for smoother small files."; }

            // ---------------- post video chain ----------------
            var v = new List<string>();
            if (p.HasCrop) v.Add($"crop={crop.w}:{crop.h}:{crop.x}:{crop.y}");
            if (outH != crop.h || outW != crop.w) v.Add($"scale={outW}:{outH}:flags=lanczos");
            v.Add($"fps={F(fps)}");
            v.AddRange(LookFilters(p));
            if (p.FadeIn > 0.01) v.Add($"fade=t=in:st=0:d={F(p.FadeIn)}");
            if (p.FadeOut > 0.01) v.Add($"fade=t=out:st={F(Math.Max(0, outDur - p.FadeOut))}:d={F(p.FadeOut)}");
            v.AddRange(TextFilters(p, work, outH, t => p.MapToOutput(t), plan));
            v.Add("format=yuv420p");
            g.Add("[vc]" + string.Join(",", v) + "[vout]");

            if (hasAudio && o.MixDown && (p.FadeIn > 0.01 || p.FadeOut > 0.01))
            {
                var af = new List<string>();
                if (p.FadeIn > 0.01) af.Add($"afade=t=in:st=0:d={F(p.FadeIn)}");
                if (p.FadeOut > 0.01) af.Add($"afade=t=out:st={F(Math.Max(0, outDur - p.FadeOut))}:d={F(p.FadeOut)}");
                g.Add("[ac]" + string.Join(",", af) + "[aout]");
            }
            else if (hasAudio && o.MixDown) g.Add("[ac]anull[aout]");

            if (o.Gif)
            {
                int gh = Math.Min(outH, o.Height > 0 ? o.Height : 360);
                g.Add($"[vout]fps={F(Math.Min(fps, 20))},scale=-2:{gh}:flags=lanczos,split[g1][g2];[g1]palettegen=stats_mode=diff[pal];[g2][pal]paletteuse=dither=bayer:bayer_scale=4[gif]");
            }

            a.AddRange(new[] { "-filter_complex", string.Join(";", g) });

            // ---------------- outputs ----------------
            if (o.Gif)
            {
                a.AddRange(new[] { "-map", "[gif]", "-loop", "0", o.OutputPath });
                return plan;
            }

            a.AddRange(new[] { "-map", "[vout]" });
            var enc = ResolveExportEncoder(o.Encoder);
            if (videoMbps > 0)
                a.AddRange(Encoders.VideoArgs(enc, RateControl.Bitrate, o.Quality, Math.Round(videoMbps, 3), EncoderSpeed.Balanced, (int)Math.Round(fps), false));
            else
                a.AddRange(Encoders.VideoArgs(enc, RateControl.ConstantQuality, o.Quality, 0, EncoderSpeed.Balanced, (int)Math.Round(fps), false));
            if (enc.Id == "libx264") a.AddRange(new[] { "-profile:v", "high" });

            if (hasAudio && o.MixDown)
            {
                a.AddRange(new[] { "-map", "[aout]", "-c:a", "aac", "-b:a", $"{audioKbps}k",
                                   "-metadata:s:a:0", "title=Mix", "-metadata:s:a:0", "handler_name=Mix" });
            }
            else if (hasAudio)
            {
                for (int k = 0; k < outTracks; k++)
                {
                    a.AddRange(new[] { "-map", $"[ac{k}]" });
                    var name = audible[k].Name;
                    a.AddRange(new[] { $"-metadata:s:a:{k}", $"title={name}", $"-metadata:s:a:{k}", $"handler_name={name}",
                                       $"-disposition:a:{k}", k == 0 ? "default" : "0" });
                }
                a.AddRange(new[] { "-c:a", "aac", "-b:a", $"{audioKbps}k" });
            }
            a.AddRange(new[] { "-metadata", "comment=Edited with WaveClips", "-movflags", "+faststart", o.OutputPath });
            return plan;
        }

        private static EncoderInfo ResolveExportEncoder(string id)
        {
            if (!string.IsNullOrEmpty(id) && id != "auto" && Encoders.Available.Any(e => e.Id == id)) return Encoders.Get(id);
            foreach (var pref in new[] { "h264_nvenc", "h264_amf", "h264_qsv", "libx264" })
                if (Encoders.Available.Any(e => e.Id == pref)) return Encoders.Get(pref);
            return Encoders.Get("libx264");
        }

        private static string ATrim(ClipSegment s, double seek)
        {
            var sb = new StringBuilder($"atrim=start={F(s.Start - seek)}:end={F(s.End - seek)},asetpts=PTS-STARTPTS");
            if (Math.Abs(s.Speed - 1) > 1e-6)
            {
                double r = s.Speed;
                while (r > 2.0) { sb.Append(",atempo=2.0"); r /= 2; }
                while (r < 0.5) { sb.Append(",atempo=0.5"); r /= 0.5; }
                sb.Append($",atempo={F(r)}");
            }
            return sb.ToString();
        }

        public static IEnumerable<string> LookFilters(EditProject p)
        {
            double b = p.Brightness, c = p.Contrast, s = p.Saturation;
            switch (p.Look)
            {
                case ColorLook.Vibrant: s *= 1.35; c *= 1.06; break;
                case ColorLook.Cinematic: c *= 1.12; s *= 0.85; b -= 0.02; break;
                case ColorLook.Wave: s *= 1.12; c *= 1.05; break;
                case ColorLook.Warm: s *= 1.08; break;
                case ColorLook.BlackWhite: s = 0; c *= 1.1; break;
            }
            if (Math.Abs(b) > 0.001 || Math.Abs(c - 1) > 0.001 || Math.Abs(s - 1) > 0.001)
                yield return $"eq=brightness={F(b)}:contrast={F(c)}:saturation={F(s)}";
            if (p.Look == ColorLook.Wave) yield return "colorbalance=rs=-0.06:gs=0.02:bs=0.12:rm=-0.04:bm=0.06";
            if (p.Look == ColorLook.Warm) yield return "colorbalance=rs=0.08:bs=-0.08:rm=0.05:bm=-0.05";
            if (p.Vignette || p.Look == ColorLook.Cinematic) yield return "vignette=angle=PI/5";
            if (p.Sharpen) yield return "unsharp=5:5:0.7";
        }

        /// <summary>drawtext filters. Font and text go into files in the working directory so no path escaping is needed.</summary>
        public static IEnumerable<string> TextFilters(EditProject p, string work, int outH, Func<double, double> map, ExportPlan plan,
                                                     bool alwaysOn = false)
        {
            if (p.Texts.Count == 0) yield break;
            var fontSrc = FontOverride ?? new[] { "bahnschrift.ttf", "segoeuib.ttf", "arialbd.ttf", "arial.ttf" }
                .Select(f => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Fonts), f))
                .FirstOrDefault(File.Exists);
            if (fontSrc == null) { if (plan != null) plan.Notes += " No font found - text skipped."; yield break; }
            var font = Path.Combine(work, "font.ttf");
            if (!File.Exists(font)) File.Copy(fontSrc, font, true);

            for (int i = 0; i < p.Texts.Count; i++)
            {
                var t = p.Texts[i];
                double a = map(t.Start), b = map(t.End);
                if (!alwaysOn && b - a < 0.05) continue;
                File.WriteAllText(Path.Combine(work, $"text{i}.txt"), t.Text ?? "", new UTF8Encoding(false));
                int size = Math.Max(8, (int)Math.Round(t.Size * outH));
                string color = "0x" + (t.Color ?? "#FFFFFF").TrimStart('#');
                string pos = $"x=(w*{F(t.X)}-text_w/2):y=(h*{F(t.Y)}-text_h/2)";
                string enable = alwaysOn ? "" : $":enable='between(t,{F(a)},{F(b)})'";
                string baseOpt = $"fontfile=font.ttf:textfile=text{i}.txt:expansion=none:fontsize={size}:{pos}{enable}";
                switch (t.Style)
                {
                    case TextStyle.WaveGlow:
                        yield return $"drawtext={baseOpt}:fontcolor=0x00E5FF@0.35:borderw={Math.Max(4, size / 7)}:bordercolor=0x00E5FF@0.35";
                        yield return $"drawtext={baseOpt}:fontcolor={color}:borderw={Math.Max(2, size / 22)}:bordercolor=0x00E5FF";
                        break;
                    case TextStyle.Outline:
                        yield return $"drawtext={baseOpt}:fontcolor={color}:borderw={Math.Max(2, size / 14)}:bordercolor=black";
                        break;
                    case TextStyle.Box:
                        yield return $"drawtext={baseOpt}:fontcolor={color}:box=1:boxcolor=0x071B24@0.85:boxborderw={Math.Max(6, size / 3)}";
                        break;
                    default:
                        yield return $"drawtext={baseOpt}:fontcolor={color}:shadowx=2:shadowy=2:shadowcolor=black@0.6";
                        break;
                }
            }
        }

        /// <summary>Renders one frame with crop, colour and text so the user can check the look.</summary>
        public static ExportPlan BuildFramePreview(EditProject p, double sourceTime, string outPng)
        {
            var work = Paths.Ensure(Path.Combine(Paths.Temp, "preview"));
            var plan = new ExportPlan { WorkDir = work, OutputDuration = 0 };
            var crop = p.CropRect();
            int outH = EditProject.Even(Math.Min(crop.h, 720));
            int outW = p.OutputWidthFor(outH);
            var v = new List<string>();
            if (p.HasCrop) v.Add($"crop={crop.w}:{crop.h}:{crop.x}:{crop.y}");
            v.Add($"scale={outW}:{outH}:flags=bicubic");
            v.AddRange(LookFilters(p));
            var active = new EditProject { Texts = p.Texts.Where(t => sourceTime >= t.Start && sourceTime <= t.End).ToList() };
            v.AddRange(TextFilters(active, work, outH, x => x, plan, alwaysOn: true));
            plan.Args.AddRange(new[]
            {
                "-hide_banner", "-v", "error", "-y", "-ss", F(sourceTime), "-i", p.SourcePath,
                "-frames:v", "1", "-vf", string.Join(",", v), outPng,
            });
            return plan;
        }
    }
}
