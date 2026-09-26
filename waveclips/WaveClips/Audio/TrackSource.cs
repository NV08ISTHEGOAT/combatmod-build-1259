using System;
using System.Collections.Generic;
using System.Linq;
using WaveClips.Core;
using WaveClips.Interop;

namespace WaveClips.Audio
{
    /// <summary>
    /// Live capture for one configured audio track. Re-targets automatically when the app/game it follows
    /// starts, restarts or closes, and retries after failures.
    /// </summary>
    internal sealed class TrackSource : IDisposable
    {
        public AudioTrackConfig Config { get; }
        public string Status { get; private set; } = "Starting…";
        public bool IsCapturing => _stream != null && !_failed;

        private readonly JitterBuffer _buffer = new JitterBuffer();
        private WasapiCaptureStream _stream;
        private string _key;
        private volatile bool _failed;
        private double _retryAt;
        private float _peak;

        public TrackSource(AudioTrackConfig config) { Config = config; }

        /// <summary>Peak level since the last call (0..1+).</summary>
        public float TakePeak()
        {
            float p = _peak;
            _peak = 0;
            return p;
        }

        internal void ReportPeak(float p) { if (p > _peak) _peak = p; }

        public void Read(float[] dst, int frames) => _buffer.Read(dst, frames);

        /// <summary>Called every ~1.5 s from the engine's maintenance loop.</summary>
        public void Update(GameMatch game, Dictionary<int, (int parent, string exe)> procs)
        {
            string key; string label; Func<WasapiCaptureStream> factory;
            int ownPid = Environment.ProcessId;

            switch (Config.Source)
            {
                case AudioSourceKind.Game:
                    if (game == null) { Detach("Waiting for a game to start…"); return; }
                    key = $"p:{game.ProcessId}:inc";
                    label = $"Capturing {game.Profile.Name} (pid {game.ProcessId})";
                    factory = () => WasapiCaptureStream.ForProcess(game.ProcessId, true, Config.Name);
                    break;

                case AudioSourceKind.App:
                {
                    int pid = FindAppRoot(Config.AppExe, procs);
                    if (pid == 0) { Detach($"{FirstName(Config.AppExe)} isn't running"); return; }
                    key = $"p:{pid}:inc";
                    label = $"Capturing {FirstName(Config.AppExe)} (pid {pid})";
                    factory = () => WasapiCaptureStream.ForProcess(pid, true, Config.Name);
                    break;
                }

                case AudioSourceKind.SystemExceptApp:
                {
                    int pid = FindAppRoot(Config.AppExe, procs);
                    int target = pid != 0 ? pid : ownPid;
                    key = $"p:{target}:exc";
                    label = pid != 0 ? $"All audio except {FirstName(Config.AppExe)}" : "All audio";
                    factory = () => WasapiCaptureStream.ForProcess(target, false, Config.Name);
                    break;
                }

                case AudioSourceKind.SystemAll:
                    key = $"p:{ownPid}:exc";
                    label = "All desktop audio";
                    factory = () => WasapiCaptureStream.ForProcess(ownPid, false, Config.Name);
                    break;

                default: // Microphone
                    key = "d:" + Config.DeviceId;
                    label = string.IsNullOrEmpty(Config.DeviceId) ? "Default microphone" : "Microphone";
                    factory = () => WasapiCaptureStream.ForInputDevice(Config.DeviceId, Config.Name);
                    break;
            }

            if (key == _key && !_failed) return;
            if (key == _key && _failed && AppClock.Seconds < _retryAt) return;

            StopStream();
            _key = key;
            _failed = false;
            try
            {
                var s = factory();
                s.DataAvailable += OnData;
                s.Failed += msg =>
                {
                    _failed = true;
                    _retryAt = AppClock.Seconds + 5;
                    Status = "Error: " + msg;
                };
                _stream = s;
                s.Start();
                Status = label;
            }
            catch (Exception ex)
            {
                _failed = true;
                _retryAt = AppClock.Seconds + 5;
                Status = "Error: " + ex.Message;
            }
        }

        private void OnData(float[] data, int frames) => _buffer.Write(data, frames);

        private void Detach(string status)
        {
            StopStream();
            _key = null;
            Status = status;
        }

        private void StopStream()
        {
            var s = _stream;
            _stream = null;
            if (s != null)
            {
                s.DataAvailable -= OnData;
                s.Dispose();
            }
            _buffer.Clear();
        }

        public void Dispose() => StopStream();

        private static string FirstName(string exes)
        {
            var first = GameProfile.Split(exes).FirstOrDefault() ?? "App";
            return GameProfile.NormalizeExe(first);
        }

        /// <summary>Finds the top-most process of an app (e.g. the root Discord.exe) so its whole tree is captured.</summary>
        public static int FindAppRoot(string exes, Dictionary<int, (int parent, string exe)> procs)
        {
            var names = new HashSet<string>(GameProfile.Split(exes).Select(GameProfile.NormalizeExe), StringComparer.OrdinalIgnoreCase);
            if (names.Count == 0) return 0;
            bool Match(string exe) => names.Contains(GameProfile.NormalizeExe(exe ?? ""));
            foreach (var kv in procs.OrderBy(k => k.Key))
            {
                if (!Match(kv.Value.exe)) continue;
                if (procs.TryGetValue(kv.Value.parent, out var parent) && Match(parent.exe)) continue;
                return kv.Key;
            }
            return 0;
        }
    }
}
