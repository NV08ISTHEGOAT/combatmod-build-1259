using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Threading;
using WaveClips.Interop;

namespace WaveClips.Core
{
    public sealed class GameMatch
    {
        public GameProfile Profile { get; init; }
        public int ProcessId { get; init; }
        public string ExePath { get; init; }
        public string WindowTitle { get; init; }
    }

    /// <summary>Polls running processes every 2 s and reports which configured game is running.</summary>
    public sealed class GameDetector : IDisposable
    {
        private readonly AppSettings _settings;
        private readonly Timer _timer;
        private int _busy;

        public GameMatch Current { get; private set; }
        /// <summary>Raised on a thread-pool thread when the running game changes (null = none).</summary>
        public event Action<GameMatch> Changed;

        public GameDetector(AppSettings settings)
        {
            _settings = settings;
            _timer = new Timer(_ => Poll(), null, 500, 2000);
        }

        public void PollNow() => ThreadPool.QueueUserWorkItem(_ => Poll());

        private void Poll()
        {
            if (Interlocked.Exchange(ref _busy, 1) == 1) return;
            try
            {
                var match = Detect();
                var cur = Current;
                bool same = (match == null && cur == null) ||
                            (match != null && cur != null && match.ProcessId == cur.ProcessId && match.Profile == cur.Profile);
                if (!same)
                {
                    Current = match;
                    Log.Info(match == null ? "Game closed" : $"Game detected: {match.Profile.Name} (pid {match.ProcessId})");
                    Changed?.Invoke(match);
                }
            }
            catch (Exception ex) { Log.Error("Game detection", ex); }
            finally { Volatile.Write(ref _busy, 0); }
        }

        private GameMatch Detect()
        {
            var profiles = AppSettings.Snapshot(_settings.Games).Where(g => g.Enabled).ToArray();
            if (profiles.Length == 0) return null;

            var byName = new Dictionary<string, List<Process>>(StringComparer.OrdinalIgnoreCase);
            var all = Process.GetProcesses();
            try
            {
                foreach (var p in all)
                {
                    if (!byName.TryGetValue(p.ProcessName, out var l)) byName[p.ProcessName] = l = new List<Process>();
                    l.Add(p);
                }

                // Prefer the game that is currently in the foreground.
                Native.GetWindowThreadProcessId(Native.GetForegroundWindow(), out uint fgPid);
                GameMatch best = null;
                foreach (var profile in profiles)
                {
                    var hints = profile.TitleHintList();
                    foreach (var exe in profile.ExeList())
                    {
                        if (!byName.TryGetValue(exe, out var procs)) continue;
                        foreach (var p in procs)
                        {
                            string title = "";
                            try { title = p.MainWindowTitle ?? ""; } catch { }
                            if (hints.Length > 0 && !hints.Any(h => title.IndexOf(h, StringComparison.OrdinalIgnoreCase) >= 0)) continue;
                            if (hints.Length == 0 && p.MainWindowHandle == IntPtr.Zero && procs.Count > 1) continue;
                            string path = null;
                            try { path = p.MainModule?.FileName; } catch { }
                            var m = new GameMatch { Profile = profile, ProcessId = p.Id, ExePath = path, WindowTitle = title };
                            if (p.Id == fgPid) return m;
                            best ??= m;
                        }
                    }
                }
                return best;
            }
            finally { foreach (var p in all) p.Dispose(); }
        }

        public void Dispose() => _timer.Dispose();
    }
}
