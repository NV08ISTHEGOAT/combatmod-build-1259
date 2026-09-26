using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using WaveClips.Audio;
using WaveClips.Core;

namespace WaveClips.Capture
{
    /// <summary>Joins replay-buffer segments into a finished MP4 with every audio track labelled.</summary>
    public static class ClipWriter
    {
        public static async Task WriteAsync(IReadOnlyList<Segment> segments, IReadOnlyList<PipeAudioWriterInfo> audio,
                                            string outputPath, string title, IProgress<double> progress = null,
                                            CancellationToken ct = default)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(outputPath)!);
            var joined = Path.Combine(Paths.Temp, $"join_{Guid.NewGuid():N}.ts");
            try
            {
                // MPEG-TS segments from one ffmpeg run have continuous timestamps, so a byte-level join is a valid stream.
                long total = segments.Sum(s => SafeLength(s.File)), done = 0;
                await using (var dst = new FileStream(joined, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 20, true))
                {
                    var buf = new byte[1 << 20];
                    foreach (var seg in segments)
                    {
                        ct.ThrowIfCancellationRequested();
                        if (!File.Exists(seg.File)) continue;
                        await using var src = new FileStream(seg.File, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 1 << 20, true);
                        int n;
                        while ((n = await src.ReadAsync(buf, ct)) > 0)
                        {
                            await dst.WriteAsync(buf.AsMemory(0, n), ct);
                            done += n;
                            if (total > 0) progress?.Report(0.8 * done / total);
                        }
                    }
                }

                var tmpOut = outputPath + ".part.mp4";
                var args = new List<string> { "-hide_banner", "-v", "error", "-y", "-fflags", "+genpts+discardcorrupt", "-i", joined, "-map", "0:v:0" };
                for (int i = 0; i < audio.Count; i++) args.AddRange(new[] { "-map", $"0:a:{i}?" });
                args.AddRange(new[] { "-c", "copy" });
                for (int i = 0; i < audio.Count; i++)
                {
                    var name = audio[i].Title;
                    args.AddRange(new[]
                    {
                        $"-metadata:s:a:{i}", $"title={name}",
                        $"-metadata:s:a:{i}", $"handler_name={name}",
                        $"-disposition:a:{i}", i == 0 ? "default" : "0",
                    });
                }
                args.AddRange(new[]
                {
                    "-metadata", $"title={title}",
                    "-metadata", "comment=Recorded with WaveClips",
                    "-movflags", "+faststart",
                    tmpOut,
                });
                var r = await FFmpeg.RunAsync(args, ct);
                if (!r.Success) throw new Exception("Saving the clip failed: " + r.StdErrTail);
                File.Move(tmpOut, outputPath, true);
                progress?.Report(1);
            }
            finally
            {
                try { File.Delete(joined); } catch { }
            }
        }

        private static long SafeLength(string f) { try { return new FileInfo(f).Length; } catch { return 0; } }
    }
}
