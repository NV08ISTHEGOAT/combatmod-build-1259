using System;
using System.Linq;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace WaveClips.Editor
{
    /// <summary>Plays the clip's separate audio tracks together with live per-track volume/mute/solo.
    /// The video (MediaElement) is the master clock; the editor re-seeks this when it drifts.</summary>
    public sealed class MultiTrackPlayer : IDisposable
    {
        private WasapiOut _out;
        private Mixer _mixer;
        public const double Latency = 0.09;

        public bool IsPlaying => _out?.PlaybackState == PlaybackState.Playing;
        public double Position => _mixer == null ? 0 : Math.Max(0, _mixer.PositionSeconds - Latency);
        public float MasterVolume { get; set; } = 1f;

        /// <summary>Why preview audio is unavailable (null when it works). The editor still works without it.</summary>
        public string Error { get; private set; }

        public void Load(string[] wavs)
        {
            Dispose();
            Error = null;
            if (wavs.Length == 0) return;
            _mixer = new Mixer(wavs.Select(w => new WaveFileReader(w)).ToArray(), this);
            try
            {
                _out = new WasapiOut(AudioClientShareMode.Shared, true, 60);
                _out.Init(_mixer);
            }
            catch (Exception ex)
            {
                // Typically no speakers/headphones (E_NOTFOUND from the default endpoint).
                try { _out?.Dispose(); } catch { }
                _out = null;
                Error = (uint)ex.HResult == 0x80070490
                    ? "No speakers or headphones found - the preview is silent (exports still include all audio)."
                    : "Audio preview unavailable: " + ex.Message;
                Core.Log.Warn("Editor audio preview: " + ex.Message);
            }
        }

        public void SetTrack(int index, float gain, double offsetSeconds) => _mixer?.SetTrack(index, gain, offsetSeconds);

        public void Play(double at)
        {
            if (_out == null) return;
            _mixer.Seek(at + Latency);
            if (_out.PlaybackState != PlaybackState.Playing) _out.Play();
        }

        public void Pause()
        {
            if (_out?.PlaybackState == PlaybackState.Playing) _out.Pause();
        }

        public void Seek(double t) => _mixer?.Seek(t + Latency);

        public void Dispose()
        {
            try { _out?.Stop(); } catch { }
            _out?.Dispose();
            _out = null;
            _mixer?.Dispose();
            _mixer = null;
        }

        private sealed class Mixer : ISampleProvider, IDisposable
        {
            private readonly WaveFileReader[] _readers;
            private readonly float[] _gain;
            private readonly long[] _offsetFrames;
            private readonly long[] _silence; // frames of leading silence still owed per track (negative offsets / before start)
            private readonly MultiTrackPlayer _owner;
            private readonly object _gate = new object();
            private byte[] _buf = new byte[0];
            private long _frame;

            public Mixer(WaveFileReader[] readers, MultiTrackPlayer owner)
            {
                _readers = readers;
                _owner = owner;
                _gain = Enumerable.Repeat(1f, readers.Length).ToArray();
                _offsetFrames = new long[readers.Length];
                _silence = new long[readers.Length];
                WaveFormat = WaveFormat.CreateIeeeFloatWaveFormat(48000, 2);
            }

            public WaveFormat WaveFormat { get; }
            public double PositionSeconds { get { lock (_gate) return _frame / 48000.0; } }

            public void SetTrack(int i, float gain, double offset)
            {
                if (i < 0 || i >= _readers.Length) return;
                lock (_gate)
                {
                    _gain[i] = gain;
                    long off = (long)Math.Round(offset * 48000);
                    if (off != _offsetFrames[i]) { _offsetFrames[i] = off; SeekTrack(i); }
                }
            }

            public void Seek(double t)
            {
                lock (_gate)
                {
                    _frame = Math.Max(0, (long)(t * 48000));
                    for (int i = 0; i < _readers.Length; i++) SeekTrack(i);
                }
            }

            private void SeekTrack(int i)
            {
                long local = _frame - _offsetFrames[i];
                var r = _readers[i];
                int block = r.WaveFormat.BlockAlign;
                if (local < 0) { _silence[i] = -local; r.Position = 0; }
                else { _silence[i] = 0; r.Position = Math.Min(r.Length, local * block); }
            }

            public int Read(float[] buffer, int offset, int count)
            {
                lock (_gate)
                {
                    Array.Clear(buffer, offset, count);
                    int frames = count / 2;
                    float master = _owner.MasterVolume;
                    for (int t = 0; t < _readers.Length; t++)
                    {
                        float g = _gain[t] * master;
                        var r = _readers[t];
                        int skip = (int)Math.Min(_silence[t], frames);
                        _silence[t] -= skip;
                        int want = frames - skip;
                        if (want <= 0) continue;
                        int bytes = want * r.WaveFormat.BlockAlign;
                        if (_buf.Length < bytes) _buf = new byte[bytes];
                        int got = r.Read(_buf, 0, bytes);
                        if (g <= 0.0001f) continue;
                        int ch = r.WaveFormat.Channels;
                        int gotFrames = got / r.WaveFormat.BlockAlign;
                        for (int f = 0; f < gotFrames; f++)
                        {
                            int bi = f * r.WaveFormat.BlockAlign;
                            float l = BitConverter.ToInt16(_buf, bi) / 32768f;
                            float rr = ch > 1 ? BitConverter.ToInt16(_buf, bi + 2) / 32768f : l;
                            int o = offset + (skip + f) * 2;
                            buffer[o] += l * g;
                            buffer[o + 1] += rr * g;
                        }
                    }
                    for (int i = offset; i < offset + frames * 2; i++) buffer[i] = Math.Clamp(buffer[i], -1f, 1f);
                    _frame += frames;
                    return count;
                }
            }

            public void Dispose()
            {
                foreach (var r in _readers) r.Dispose();
            }
        }
    }
}
