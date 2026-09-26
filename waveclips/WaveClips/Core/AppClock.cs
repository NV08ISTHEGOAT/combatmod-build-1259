using System.Diagnostics;

namespace WaveClips.Core
{
    /// <summary>One monotonic clock for everything that needs to line up (audio pacer, video start, hotkey presses).</summary>
    public static class AppClock
    {
        private static readonly Stopwatch Sw = Stopwatch.StartNew();
        public static double Seconds => Sw.ElapsedTicks / (double)Stopwatch.Frequency;
    }
}
