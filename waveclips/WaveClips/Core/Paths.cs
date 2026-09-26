using System;
using System.IO;

namespace WaveClips.Core
{
    public static class Paths
    {
        /// <summary>Optional override (portable installs / the self-test): keeps all data under one folder.</summary>
        private static readonly string Home = Environment.GetEnvironmentVariable("WAVECLIPS_HOME");

        public static string AppData { get; } = Ensure(Home != null ? Path.Combine(Home, "roaming")
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "WaveClips"));
        public static string LocalData { get; } = Ensure(Home != null ? Path.Combine(Home, "local")
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "WaveClips"));
        public static string SettingsFile => Path.Combine(AppData, "settings.json");
        public static string LibraryFile => Path.Combine(AppData, "library.json");
        public static string LogFile => Path.Combine(LocalData, "waveclips.log");
        public static string ThumbCache => Ensure(Path.Combine(LocalData, "thumbs"));
        public static string EditorCache => Ensure(Path.Combine(LocalData, "editor-cache"));
        public static string Temp => Ensure(Path.Combine(LocalData, "temp"));
        public static string FfmpegDir => Path.Combine(LocalData, "ffmpeg");
        public static string DefaultBufferFolder => Path.Combine(LocalData, "buffer");
        public static string DefaultClipFolder => Home != null ? Path.Combine(Home, "clips")
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyVideos), "WaveClips");
        public static string AppDir => AppContext.BaseDirectory;

        public static string Ensure(string dir)
        {
            try { Directory.CreateDirectory(dir); } catch { /* reported by callers when used */ }
            return dir;
        }

        public static string SafeFileName(string name)
        {
            foreach (var c in Path.GetInvalidFileNameChars()) name = name.Replace(c, '_');
            return name.Trim().TrimEnd('.');
        }
    }
}
