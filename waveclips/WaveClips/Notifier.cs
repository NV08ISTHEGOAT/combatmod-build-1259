using System;
using System.IO;
using System.Media;
using System.Threading.Tasks;
using WaveClips.Capture;
using WaveClips.Core;
using WaveClips.Views;

namespace WaveClips
{
    /// <summary>Overlay toasts + little synthesized "wave" chimes.</summary>
    public sealed class Notifier
    {
        private readonly AppSettings _settings;
        private ToastWindow _toast;
        private static readonly byte[] ClipSound = Chime(new[] { 784.0, 1175.0, 1568.0 }, 0.075);
        private static readonly byte[] RecordSound = Chime(new[] { 660.0, 880.0 }, 0.09);
        private static readonly byte[] ErrorSound = Chime(new[] { 440.0, 330.0 }, 0.12);

        public Notifier(AppSettings settings) { _settings = settings; }

        /// <summary>UI thread only.</summary>
        public void Show(NotifyKind kind, string title, string message)
        {
            if (_settings.ClipSound && kind != NotifyKind.Info)
                Play(kind switch { NotifyKind.Clip => ClipSound, NotifyKind.Recording => RecordSound, _ => ErrorSound });

            if (!_settings.ClipOverlay && kind != NotifyKind.Error) return;
            try
            {
                _toast ??= new ToastWindow();
                var monitor = Monitors.Find(Monitors.Enumerate(), _settings.MonitorId);
                _toast.Present(kind, title, message, monitor, _settings.OverlayCorner);
            }
            catch (Exception ex) { Log.Error("Toast", ex); }
            AppHost.Tray?.Balloon(kind, title, message);
        }

        private static void Play(byte[] wav)
        {
            Task.Run(() =>
            {
                try { using var p = new SoundPlayer(new MemoryStream(wav)); p.PlaySync(); }
                catch { /* no audio device */ }
            });
        }

        /// <summary>Builds a short arpeggio as a 16-bit mono WAV.</summary>
        private static byte[] Chime(double[] notes, double noteLen)
        {
            const int rate = 44100;
            double tail = 0.25;
            int total = (int)(rate * (noteLen * notes.Length + tail));
            var samples = new short[total];
            for (int n = 0; n < notes.Length; n++)
            {
                int start = (int)(rate * noteLen * n);
                for (int i = start; i < total; i++)
                {
                    double t = (i - start) / (double)rate;
                    double env = Math.Min(1, t / 0.004) * Math.Exp(-t * 9);
                    double v = Math.Sin(2 * Math.PI * notes[n] * t) * 0.7 + Math.Sin(2 * Math.PI * notes[n] * 2 * t) * 0.15;
                    samples[i] = (short)Math.Clamp(samples[i] + v * env * 0.28 * short.MaxValue, short.MinValue, short.MaxValue);
                }
            }
            using var ms = new MemoryStream();
            using var w = new BinaryWriter(ms);
            w.Write("RIFF"u8.ToArray()); w.Write(36 + total * 2); w.Write("WAVE"u8.ToArray());
            w.Write("fmt "u8.ToArray()); w.Write(16); w.Write((short)1); w.Write((short)1); w.Write(rate); w.Write(rate * 2); w.Write((short)2); w.Write((short)16);
            w.Write("data"u8.ToArray()); w.Write(total * 2);
            foreach (var s in samples) w.Write(s);
            w.Flush();
            return ms.ToArray();
        }
    }
}
