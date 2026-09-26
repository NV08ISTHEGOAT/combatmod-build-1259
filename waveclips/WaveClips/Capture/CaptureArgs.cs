using System.Collections.Generic;
using System.Globalization;
using System.IO;
using WaveClips.Core;

namespace WaveClips.Capture
{
    /// <summary>How the screen gets from the GPU into the encoder. Tried in order until one works.</summary>
    public enum PipelineLevel
    {
        /// <summary>Desktop Duplication, frames stay on the GPU all the way into NVENC/AMF/QSV.</summary>
        DdaGpu,
        /// <summary>Desktop Duplication, frames downloaded to system memory (works with any encoder + scaling).</summary>
        DdaDownload,
        /// <summary>GDI screen grab with the chosen encoder.</summary>
        Gdi,
        /// <summary>GDI + CPU x264 - last resort.</summary>
        GdiX264,
    }

    /// <summary>Immutable snapshot of everything that defines a capture session.</summary>
    public sealed partial class CaptureConfig
    {
        public MonitorInfo Monitor { get; init; }
        public int Fps { get; init; }
        public int OutputHeight { get; init; }
        public EncoderInfo Encoder { get; init; }
        public RateControl RateControl { get; init; }
        public int Quality { get; init; }
        public int BitrateMbps { get; init; }
        public EncoderSpeed Speed { get; init; }
        public bool Cursor { get; init; }
        public CaptureMethod Method { get; init; }
        public int AudioBitrateKbps { get; init; }
        public string TrackSignature { get; init; }

        public bool Scales => OutputHeight > 0 && Monitor != null && OutputHeight < Monitor.Height;

        public string Signature =>
            $"{Monitor?.DeviceName}|{Monitor?.Width}x{Monitor?.Height}|{Fps}|{OutputHeight}|{Encoder?.Id}|{RateControl}|{Quality}|{BitrateMbps}|{Speed}|{Cursor}|{Method}|{AudioBitrateKbps}|{TrackSignature}";

        public IEnumerable<PipelineLevel> Levels()
        {
            if (Method == CaptureMethod.DesktopDuplication)
            {
                if (Encoder.IsHardware && !Scales) yield return PipelineLevel.DdaGpu;
                yield return PipelineLevel.DdaDownload;
            }
            yield return PipelineLevel.Gdi;
            if (Encoder.Id != "libx264") yield return PipelineLevel.GdiX264;
        }
    }

    /// <summary>Builds the ffmpeg command line for a capture session (pure function, unit-testable).</summary>
    public static class CaptureArgs
    {
        public static EncoderInfo EffectiveEncoder(CaptureConfig c, PipelineLevel level) =>
            level == PipelineLevel.GdiX264 ? Encoders.Get("libx264") : c.Encoder;

        public static List<string> Build(CaptureConfig c, PipelineLevel level, IReadOnlyList<string> audioPipes, string dir, string listFile)
        {
            var m = c.Monitor;
            var enc = EffectiveEncoder(c, level);
            var inv = CultureInfo.InvariantCulture;
            // level+ prefixes every log line with [error]/[warning]/... so real errors can be picked out.
            var a = new List<string> { "-hide_banner", "-loglevel", "level+verbose", "-nostats", "-progress", "pipe:1", "-stats_period", "1", "-y" };

            // Audio inputs first: they connect before the video source starts, see AudioEngine sync.
            // (No -thread_queue_size: current FFmpeg only accepts it as an output option.)
            foreach (var pipe in audioPipes)
                a.AddRange(new[] { "-f", "f32le", "-ar", "48000", "-ac", "2", "-i", pipe });

            string pixFmt = enc.Id == "libx264" ? "yuv420p" : "nv12";
            string scale = c.Scales ? $",scale=-2:{c.OutputHeight}:flags=bicubic" : "";
            string videoLabel;

            if (level == PipelineLevel.DdaGpu || level == PipelineLevel.DdaDownload)
            {
                if (m.AdapterIndex != 0)
                    a.AddRange(new[] { "-init_hw_device", $"d3d11va=wcdx:{m.AdapterIndex}", "-filter_hw_device", "wcdx" });
                var graph = $"ddagrab=output_idx={m.OutputIndex}:framerate={c.Fps}:draw_mouse={(c.Cursor ? 1 : 0)}";
                if (level == PipelineLevel.DdaGpu)
                {
                    if (enc.IsQsv) graph += ",hwmap=derive_device=qsv,format=qsv";
                }
                else graph += $",hwdownload,format=bgra{scale},format={pixFmt}";
                a.AddRange(new[] { "-filter_complex", graph + "[v]" });
                videoLabel = "[v]";
            }
            else
            {
                int vIn = audioPipes.Count;
                a.AddRange(new[]
                {
                    "-f", "gdigrab", "-framerate", c.Fps.ToString(inv),
                    "-draw_mouse", c.Cursor ? "1" : "0",
                    "-offset_x", m.X.ToString(inv), "-offset_y", m.Y.ToString(inv),
                    "-video_size", $"{m.Width}x{m.Height}", "-i", "desktop",
                    "-filter_complex", $"[{vIn}:v]format=bgra{scale},format={pixFmt}[v]",
                });
                videoLabel = "[v]";
            }

            a.AddRange(new[] { "-map", videoLabel });
            for (int i = 0; i < audioPipes.Count; i++) a.AddRange(new[] { "-map", $"{i}:a" });

            a.AddRange(Encoders.VideoArgs(enc, c.RateControl, c.Quality, c.BitrateMbps, c.Speed, c.Fps, keyframeEverySecond: true));
            if (audioPipes.Count > 0)
                a.AddRange(new[] { "-c:a", "aac", "-b:a", $"{c.AudioBitrateKbps}k" });

            a.AddRange(new[]
            {
                "-max_muxing_queue_size", "9999",
                "-f", "segment",
                "-segment_time", "1",
                "-segment_time_delta", (0.5 / c.Fps).ToString("0.#####", inv),
                "-segment_format", "mpegts",
                "-segment_list", listFile,
                "-segment_list_type", "csv",
                Path.Combine(dir, "seg_%06d.ts"),
            });
            return a;
        }
    }
}
