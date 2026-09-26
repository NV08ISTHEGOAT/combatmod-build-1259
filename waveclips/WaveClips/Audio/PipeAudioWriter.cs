using System;
using System.Buffers;
using System.Collections.Concurrent;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Threading;

namespace WaveClips.Audio
{
    /// <summary>
    /// Streams one audio track (raw 48 kHz stereo f32le) into ffmpeg through a named pipe.
    /// FFmpeg timestamps raw audio by sample count, so once we know when the first video frame was
    /// grabbed we drop (or pad) exactly enough samples for audio time 0 to equal video time 0.
    /// </summary>
    internal sealed class PipeAudioWriter : IDisposable
    {
        private readonly struct Chunk
        {
            public readonly float[] Data; public readonly int Frames; public readonly long FrameIndex;
            public Chunk(float[] d, int f, long i) { Data = d; Frames = f; FrameIndex = i; }
        }

        public string PipeName { get; }
        public string PipePath => @"\\.\pipe\" + PipeName;
        /// <summary>null = the mix of all tracks.</summary>
        public string TrackId { get; }
        public string Title { get; }
        public bool Connected => _connected;

        private readonly NamedPipeServerStream _pipe;
        private readonly BlockingCollection<Chunk> _queue = new BlockingCollection<Chunk>(4000);
        private readonly Func<long, double> _frameTime;
        private readonly Thread _thread;
        private readonly CancellationTokenSource _cts = new CancellationTokenSource();
        private volatile bool _connected, _disposed;
        private long _firstFrame = -1;
        private double _videoStart = double.NaN, _extraShift;
        private bool _syncApplied;
        private long _skipFrames;

        /// <param name="frameTime">Maps an engine frame index to AppClock seconds.</param>
        public PipeAudioWriter(string pipeName, string trackId, string title, Func<long, double> frameTime)
        {
            PipeName = pipeName;
            TrackId = trackId;
            Title = title;
            _frameTime = frameTime;
            _pipe = new NamedPipeServerStream(pipeName, PipeDirection.Out, 1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous, 0, 1 << 20);
            _thread = new Thread(Run) { IsBackground = true, Name = "Pipe " + title, Priority = ThreadPriority.AboveNormal };
            _thread.Start();
        }

        /// <summary>
        /// Called once the moment the first video frame was captured is known.
        /// <paramref name="shiftSeconds"/> = capture latency - user delay (positive = audio earlier).
        /// </summary>
        public void SetVideoStart(double videoStartSeconds, double shiftSeconds)
        {
            _extraShift = shiftSeconds;
            _videoStart = videoStartSeconds;
        }

        /// <summary>Pacer thread. Takes ownership of <paramref name="data"/> (an ArrayPool rental).</summary>
        public void Push(float[] data, int frames, long frameIndex)
        {
            if (!_connected || _disposed || !_queue.TryAdd(new Chunk(data, frames, frameIndex)))
                ArrayPool<float>.Shared.Return(data);
        }

        private void Run()
        {
            try
            {
                _pipe.WaitForConnectionAsync(_cts.Token).GetAwaiter().GetResult();
                _connected = true;
                foreach (var c in _queue.GetConsumingEnumerable())
                {
                    try
                    {
                        if (_firstFrame < 0) _firstFrame = c.FrameIndex;
                        int offset = 0, frames = c.Frames;

                        if (!_syncApplied && !double.IsNaN(_videoStart))
                        {
                            _syncApplied = true;
                            double shift = _videoStart - _frameTime(_firstFrame) + _extraShift;
                            shift = Math.Clamp(shift, -1.0, 3.0);
                            _skipFrames = (long)Math.Round(shift * AudioEngine.Rate);
                            Core.Log.Info($"Audio '{Title}': sync shift {shift * 1000:F0} ms");
                            if (_skipFrames < 0)
                            {
                                var zeros = new byte[(int)(-_skipFrames) * 8];
                                _pipe.Write(zeros, 0, zeros.Length);
                                _skipFrames = 0;
                            }
                        }
                        if (_skipFrames > 0)
                        {
                            int s = (int)Math.Min(_skipFrames, frames);
                            _skipFrames -= s;
                            offset = s;
                            frames -= s;
                        }
                        if (frames > 0)
                            _pipe.Write(MemoryMarshal.AsBytes(new ReadOnlySpan<float>(c.Data, offset * 2, frames * 2)));
                    }
                    finally { ArrayPool<float>.Shared.Return(c.Data); }
                }
            }
            catch (Exception ex)
            {
                if (!_disposed) Core.Log.Info($"Audio pipe '{Title}' closed: {ex.Message}");
            }
            finally
            {
                _connected = false;
                while (_queue.TryTake(out var left)) ArrayPool<float>.Shared.Return(left.Data);
            }
        }

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            _queue.CompleteAdding();
            _cts.Cancel();
            try { _pipe.Dispose(); } catch { }
            _thread.Join(1000);
        }
    }
}
