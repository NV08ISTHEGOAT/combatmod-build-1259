using System;

namespace WaveClips.Audio
{
    /// <summary>
    /// Stereo ring buffer between a bursty capture thread and the steady pacer.
    /// Keeps ~40 ms of latency so the pacer never starves mid-burst, and trims when it drifts too far ahead.
    /// </summary>
    internal sealed class JitterBuffer
    {
        public const int PrimeFrames = 1440;      // 30 ms before we start playing out
        public const int TargetFrames = 1920;     // 40 ms steady state
        public const int MaxFrames = 9600;        // 200 ms -> drop back to target
        private const int Ch = 2;

        private readonly float[] _ring = new float[48000 * Ch]; // 1 s
        private readonly int _capFrames = 48000;
        private int _read, _count; // frames
        private bool _primed;
        private readonly object _gate = new object();

        public void Write(float[] src, int frames)
        {
            lock (_gate)
            {
                int offset = 0;
                if (frames > _capFrames) { offset = frames - _capFrames; frames = _capFrames; }
                int overflow = _count + frames - _capFrames;
                if (overflow > 0) { _read = (_read + overflow) % _capFrames; _count -= overflow; }
                int write = (_read + _count) % _capFrames;
                int first = Math.Min(frames, _capFrames - write);
                Array.Copy(src, offset * Ch, _ring, write * Ch, first * Ch);
                if (frames > first) Array.Copy(src, (offset + first) * Ch, _ring, 0, (frames - first) * Ch);
                _count += frames;
            }
        }

        /// <summary>Always fills exactly <paramref name="frames"/> frames (silence when starved).</summary>
        public void Read(float[] dst, int frames)
        {
            lock (_gate)
            {
                if (!_primed)
                {
                    if (_count >= PrimeFrames) _primed = true;
                    else { Array.Clear(dst, 0, frames * Ch); return; }
                }
                if (_count > MaxFrames)
                {
                    int drop = _count - TargetFrames;
                    _read = (_read + drop) % _capFrames;
                    _count -= drop;
                }
                int n = Math.Min(_count, frames);
                int first = Math.Min(n, _capFrames - _read);
                Array.Copy(_ring, _read * Ch, dst, 0, first * Ch);
                if (n > first) Array.Copy(_ring, 0, dst, first * Ch, (n - first) * Ch);
                _read = (_read + n) % _capFrames;
                _count -= n;
                if (n < frames)
                {
                    Array.Clear(dst, n * Ch, (frames - n) * Ch);
                    _primed = false;
                }
            }
        }

        public void Clear()
        {
            lock (_gate) { _read = 0; _count = 0; _primed = false; }
        }
    }
}
