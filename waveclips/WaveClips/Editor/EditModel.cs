using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace WaveClips.Editor
{
    /// <summary>A piece of the source clip. Segments always cover the whole clip back-to-back;
    /// cut parts are kept but flagged <see cref="Removed"/> so they can be restored.</summary>
    public sealed class ClipSegment
    {
        public double Start { get; set; }
        public double End { get; set; }
        public double Speed { get; set; } = 1;
        public bool Removed { get; set; }
        [JsonIgnore] public double Length => End - Start;
        [JsonIgnore] public double OutputLength => Length / Speed;
    }

    public sealed class TrackMix
    {
        public int StreamIndex { get; set; }
        public string Name { get; set; } = "";
        public string Color { get; set; } = "#00E5FF";
        public double Volume { get; set; } = 1;
        public bool Muted { get; set; }
        public bool Solo { get; set; }
        public bool IsMix { get; set; }
        public double Offset { get; set; } // seconds, + = later
    }

    public enum TextStyle { WaveGlow, Outline, Plain, Box }

    public sealed class TextOverlay
    {
        public string Text { get; set; } = "INSANE CLUTCH";
        public double Start { get; set; }
        public double End { get; set; }
        public double X { get; set; } = 0.5;  // centre, 0..1 of the output frame
        public double Y { get; set; } = 0.15;
        public double Size { get; set; } = 0.08; // fraction of output height
        public string Color { get; set; } = "#FFFFFF";
        public TextStyle Style { get; set; } = TextStyle.WaveGlow;
    }

    public enum ColorLook { None, Vibrant, Cinematic, Wave, Warm, BlackWhite }

    public sealed class EditProject
    {
        public string SourcePath { get; set; }
        public double Duration { get; set; }
        public int Width { get; set; }
        public int Height { get; set; }
        public double Fps { get; set; } = 60;

        public List<ClipSegment> Segments { get; set; } = new List<ClipSegment>();
        public List<TrackMix> Tracks { get; set; } = new List<TrackMix>();
        public List<TextOverlay> Texts { get; set; } = new List<TextOverlay>();

        // Format / crop
        public string Aspect { get; set; } = "source"; // source | 16:9 | 9:16 | 1:1 | 4:5
        public double Zoom { get; set; } = 1;
        public double CropX { get; set; } = 0.5;
        public double CropY { get; set; } = 0.5;

        // Look
        public ColorLook Look { get; set; } = ColorLook.None;
        public double Brightness { get; set; }
        public double Contrast { get; set; } = 1;
        public double Saturation { get; set; } = 1;
        public bool Vignette { get; set; }
        public bool Sharpen { get; set; }
        public double FadeIn { get; set; }
        public double FadeOut { get; set; }

        public static EditProject Create(string path, Core.MediaInfo info, Func<int, string> trackColor)
        {
            var p = new EditProject
            {
                SourcePath = path,
                Duration = Math.Max(0.1, info.Duration),
                Width = info.Width,
                Height = info.Height,
                Fps = info.Fps > 0 ? info.Fps : 60,
            };
            p.Segments.Add(new ClipSegment { Start = 0, End = p.Duration });
            bool hasSeparate = info.Audio.Count > 1;
            foreach (var a in info.Audio)
            {
                bool isMix = hasSeparate && a.Index == 0 && a.Title.StartsWith("Mix", StringComparison.OrdinalIgnoreCase);
                p.Tracks.Add(new TrackMix
                {
                    StreamIndex = a.Index,
                    Name = a.Title,
                    IsMix = isMix,
                    Muted = isMix, // individual tracks are used instead; the recorded mix is there as a fallback
                    Color = isMix ? "#80DEEA" : trackColor(a.Index),
                });
            }
            return p;
        }

        // ------------------------------------------------------------ helpers
        [JsonIgnore] public IEnumerable<ClipSegment> Kept => Segments.Where(s => !s.Removed && s.Length > 0.01);
        [JsonIgnore] public double OutputDuration => Kept.Sum(s => s.OutputLength);

        public ClipSegment SegmentAt(double t) =>
            Segments.FirstOrDefault(s => t >= s.Start && t < s.End) ?? Segments.LastOrDefault();

        /// <summary>Source time -> time in the exported video.</summary>
        public double MapToOutput(double t)
        {
            double acc = 0;
            foreach (var s in Kept)
            {
                if (t < s.Start) return acc;
                if (t <= s.End) return acc + (t - s.Start) / s.Speed;
                acc += s.OutputLength;
            }
            return acc;
        }

        /// <summary>First kept time at or after t (null if nothing is kept after it).</summary>
        public double? NextKept(double t)
        {
            foreach (var s in Segments)
            {
                if (s.Removed || s.End <= t + 0.001) continue;
                return Math.Max(s.Start, t);
            }
            return null;
        }

        public void Split(double t)
        {
            var s = SegmentAt(t);
            if (s == null || t - s.Start < 0.05 || s.End - t < 0.05) return;
            int i = Segments.IndexOf(s);
            Segments.Insert(i + 1, new ClipSegment { Start = t, End = s.End, Speed = s.Speed, Removed = s.Removed });
            s.End = t;
        }

        /// <summary>Moves the boundary between segment i and i+1 (or the outer edges, which splits off a removed piece).</summary>
        public void MoveBoundary(int index, double t)
        {
            if (index < 0 || index >= Segments.Count - 1) return;
            var a = Segments[index];
            var b = Segments[index + 1];
            t = Math.Clamp(t, a.Start + 0.05, b.End - 0.05);
            a.End = t;
            b.Start = t;
        }

        /// <summary>Merges neighbours that ended up identical (keeps the list tidy).</summary>
        public void Normalize()
        {
            for (int i = Segments.Count - 2; i >= 0; i--)
            {
                var a = Segments[i];
                var b = Segments[i + 1];
                // Only neighbouring cut pieces are merged; kept pieces stay split so each can have its own speed.
                if (a.Removed && b.Removed)
                {
                    a.End = b.End;
                    Segments.RemoveAt(i + 1);
                }
            }
            Segments.RemoveAll(s => s.Length <= 0.001);
            if (Segments.Count == 0) Segments.Add(new ClipSegment { Start = 0, End = Duration });
        }

        /// <summary>Output frame size (before the export resolution) after crop/zoom.</summary>
        /// <summary>Width / height of the exported frame.</summary>
        [JsonIgnore]
        public double AspectRatio => Aspect switch
        {
            "16:9" => 16.0 / 9, "9:16" => 9.0 / 16, "1:1" => 1, "4:5" => 4.0 / 5,
            _ => Height > 0 ? Width / (double)Height : 16.0 / 9,
        };

        /// <summary>Output width for a given output height (exact aspect, even pixels).</summary>
        public int OutputWidthFor(int height) => Even(height * AspectRatio);

        public (int w, int h, int x, int y) CropRect()
        {
            double sw = Width, sh = Height;
            double ar = AspectRatio;
            double cw, ch;
            if (sw / sh > ar) { ch = sh; cw = sh * ar; } else { cw = sw; ch = sw / ar; }
            cw /= Math.Max(1, Zoom);
            ch /= Math.Max(1, Zoom);
            int w = Even(cw), h = Even(ch);
            int x = (int)Math.Round(Math.Clamp(CropX * sw - w / 2.0, 0, sw - w));
            int y = (int)Math.Round(Math.Clamp(CropY * sh - h / 2.0, 0, sh - h));
            return (w, h, x, y);
        }

        public bool HasCrop => Aspect != "source" || Zoom > 1.001;

        public IEnumerable<TrackMix> AudibleTracks()
        {
            bool anySolo = Tracks.Any(t => t.Solo);
            return Tracks.Where(t => anySolo ? t.Solo : !t.Muted).Where(t => t.Volume > 0.001);
        }

        public static int Even(double v) => Math.Max(2, (int)Math.Round(v / 2) * 2);

        // ------------------------------------------------------------ persistence
        private static readonly JsonSerializerOptions Json = new JsonSerializerOptions { Converters = { new JsonStringEnumConverter() } };
        public string Serialize() => JsonSerializer.Serialize(this, Json);
        public static EditProject Deserialize(string json) => JsonSerializer.Deserialize<EditProject>(json, Json);
    }

    /// <summary>Snapshot based undo/redo.</summary>
    public sealed class UndoStack
    {
        private readonly List<string> _undo = new List<string>();
        private readonly List<string> _redo = new List<string>();

        public bool CanUndo => _undo.Count > 0;
        public bool CanRedo => _redo.Count > 0;

        /// <summary>Call *before* changing the project.</summary>
        public void Push(EditProject current)
        {
            var snap = current.Serialize();
            if (_undo.Count > 0 && _undo[^1] == snap) return;
            _undo.Add(snap);
            if (_undo.Count > 200) _undo.RemoveAt(0);
            _redo.Clear();
        }

        public EditProject Undo(EditProject current)
        {
            if (!CanUndo) return current;
            _redo.Add(current.Serialize());
            var s = _undo[^1];
            _undo.RemoveAt(_undo.Count - 1);
            return EditProject.Deserialize(s);
        }

        public EditProject Redo(EditProject current)
        {
            if (!CanRedo) return current;
            _undo.Add(current.Serialize());
            var s = _redo[^1];
            _redo.RemoveAt(_redo.Count - 1);
            return EditProject.Deserialize(s);
        }

        public void Clear() { _undo.Clear(); _redo.Clear(); }
    }
}
