using System;
using System.IO;

namespace WaveClips.Core
{
    /// <summary>Tiny append-only log file (rotated at 2 MB) for diagnosing capture problems.</summary>
    public static class Log
    {
        private static readonly object Gate = new object();

        public static void Info(string msg) => Write("INFO", msg);
        public static void Warn(string msg) => Write("WARN", msg);
        public static void Error(string msg, Exception ex = null) => Write("ERROR", ex == null ? msg : msg + ": " + ex);

        private static void Write(string level, string msg)
        {
            try
            {
                lock (Gate)
                {
                    var f = Paths.LogFile;
                    var fi = new FileInfo(f);
                    if (fi.Exists && fi.Length > 2 * 1024 * 1024)
                    {
                        File.Copy(f, f + ".old", true);
                        File.Delete(f);
                    }
                    File.AppendAllText(f, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} [{level}] {msg}{Environment.NewLine}");
                }
            }
            catch { /* logging must never crash the app */ }
            System.Diagnostics.Debug.WriteLine($"[{level}] {msg}");
        }
    }
}
