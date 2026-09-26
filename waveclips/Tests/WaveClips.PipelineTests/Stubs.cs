using System;
using System.Diagnostics;
namespace WaveClips.Core
{
    public sealed class AppSettings
    {
        public string FfmpegPath { get; set; } = "";
        public string[] EncoderCache { get; set; } = Array.Empty<string>();
        public string EncoderCacheKey { get; set; } = "";
        public void Save() { }
    }
}
namespace WaveClips.Interop { internal static class Native { public static void TieToLifetime(Process p) { } } }
namespace WaveClips.Audio
{
    public sealed class PipeAudioWriterInfo
    {
        public PipeAudioWriterInfo(string path, string title, string trackId) { Path = path; Title = title; TrackId = trackId; }
        public string Path { get; } public string Title { get; } public string TrackId { get; }
    }
}
