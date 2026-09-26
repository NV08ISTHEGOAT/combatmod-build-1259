using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;

namespace WaveClips.Capture
{
    public sealed class Segment
    {
        public string File { get; init; }
        public double Start { get; init; }  // stream seconds (0 = first video frame)
        public double End { get; init; }
        public double Duration => End - Start;
    }

    /// <summary>Tails ffmpeg's segment list (csv: file,start,end) and deletes segments nobody needs anymore.</summary>
    public sealed class SegmentIndex
    {
        private readonly string _dir, _list;
        private readonly List<Segment> _segments = new List<Segment>();
        private readonly object _gate = new object();
        private long _pos;
        private string _partial = "";

        public SegmentIndex(string dir, string listFile) { _dir = dir; _list = listFile; }

        public void Poll()
        {
            try
            {
                if (!File.Exists(_list)) return;
                using var fs = new FileStream(_list, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                if (fs.Length <= _pos) return;
                fs.Seek(_pos, SeekOrigin.Begin);
                var bytes = new byte[fs.Length - _pos];
                int read = fs.Read(bytes, 0, bytes.Length);
                _pos += read;
                var text = _partial + Encoding.UTF8.GetString(bytes, 0, read);
                int lastNl = text.LastIndexOf('\n');
                _partial = lastNl >= 0 ? text.Substring(lastNl + 1) : text;
                if (lastNl < 0) return;
                foreach (var raw in text.Substring(0, lastNl).Split('\n'))
                {
                    var line = raw.Trim();
                    if (line.Length == 0) continue;
                    // The file name may itself be quoted if it contains commas; ours never does.
                    var parts = line.Split(',');
                    if (parts.Length < 3) continue;
                    if (!double.TryParse(parts[parts.Length - 2], NumberStyles.Float, CultureInfo.InvariantCulture, out var s)) continue;
                    if (!double.TryParse(parts[parts.Length - 1], NumberStyles.Float, CultureInfo.InvariantCulture, out var e)) continue;
                    var name = string.Join(",", parts.Take(parts.Length - 2)).Trim('"');
                    lock (_gate) _segments.Add(new Segment { File = Path.Combine(_dir, Path.GetFileName(name)), Start = s, End = e });
                }
            }
            catch (IOException) { /* ffmpeg is writing; try again next poll */ }
        }

        public double LatestEnd { get { lock (_gate) return _segments.Count == 0 ? double.NegativeInfinity : _segments[^1].End; } }

        public List<Segment> Range(double from, double to)
        {
            lock (_gate) return _segments.Where(s => s.End > from + 0.01 && s.Start < to - 0.01).ToList();
        }

        /// <summary>Deletes segment files that end before <paramref name="keepFrom"/> (stream seconds).</summary>
        public void DeleteBefore(double keepFrom)
        {
            List<Segment> old;
            lock (_gate)
            {
                old = _segments.Where(s => s.End < keepFrom).ToList();
                if (old.Count == 0) return;
                _segments.RemoveAll(s => s.End < keepFrom);
            }
            foreach (var s in old)
            {
                try { File.Delete(s.File); } catch { /* retried when the session folder is removed */ }
            }
        }

        public long BytesOnDisk()
        {
            try { return new DirectoryInfo(_dir).EnumerateFiles("*.ts").Sum(f => f.Length); } catch { return 0; }
        }
    }
}
