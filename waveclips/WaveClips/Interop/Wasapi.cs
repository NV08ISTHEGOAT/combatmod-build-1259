using System;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;

namespace WaveClips.Interop
{
    // ------------------------------------------------------------------ COM definitions
    [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioClient
    {
        [PreserveSig] int Initialize(int shareMode, uint streamFlags, long hnsBufferDuration, long hnsPeriodicity, IntPtr pFormat, IntPtr audioSessionGuid);
        [PreserveSig] int GetBufferSize(out uint frames);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out uint padding);
        [PreserveSig] int IsFormatSupported(int shareMode, IntPtr pFormat, out IntPtr closest);
        [PreserveSig] int GetMixFormat(out IntPtr format);
        [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(IntPtr eventHandle);
        [PreserveSig] int GetService(ref Guid riid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioCaptureClient
    {
        [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
        [PreserveSig] int ReleaseBuffer(uint frames);
        [PreserveSig] int GetNextPacketSize(out uint frames);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
        [PreserveSig] int OpenPropertyStore(int access, out IntPtr store);
        [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetState(out int state);
    }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
        [PreserveSig] int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice device);
    }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    internal class MMDeviceEnumeratorCom { }

    [ComImport, Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IActivateAudioInterfaceAsyncOperation
    {
        [PreserveSig] int GetActivateResult(out int activateResult, [MarshalAs(UnmanagedType.IUnknown)] out object activatedInterface);
    }

    [ComImport, Guid("41D949AB-9862-444A-80F6-C261334DA5EB"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IActivateAudioInterfaceCompletionHandler
    {
        [PreserveSig] int ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation);
    }

    /// <summary>Marker interface - ActivateAudioInterfaceAsync refuses handlers that aren't agile.</summary>
    [ComImport, Guid("94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAgileObject { }

    [ComVisible(true)]
    internal sealed class ActivationHandler : IActivateAudioInterfaceCompletionHandler, IAgileObject
    {
        public readonly TaskCompletionSource<IAudioClient> Result =
            new TaskCompletionSource<IAudioClient>(TaskCreationOptions.RunContinuationsAsynchronously);

        public int ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation)
        {
            try
            {
                int hr = operation.GetActivateResult(out var activateHr, out var iface);
                if (hr < 0) Result.TrySetException(Marshal.GetExceptionForHR(hr) ?? new COMException("GetActivateResult", hr));
                else if (activateHr < 0) Result.TrySetException(Marshal.GetExceptionForHR(activateHr) ?? new COMException("Activate", activateHr));
                else Result.TrySetResult((IAudioClient)iface);
            }
            catch (Exception ex) { Result.TrySetException(ex); }
            return 0;
        }
    }

    // ------------------------------------------------------------------ capture stream
    /// <summary>
    /// Captures 48 kHz stereo float audio either from a process tree (Windows 10 2004+ process loopback)
    /// or from an input device. Windows converts whatever the source produces into our format.
    /// </summary>
    internal sealed class WasapiCaptureStream : IDisposable
    {
        public const int SampleRate = 48000;
        public const int Channels = 2;

        private const int AUDCLNT_SHAREMODE_SHARED = 0;
        private const uint AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
        private const uint AUDCLNT_STREAMFLAGS_EVENTCALLBACK = 0x00040000;
        private const uint AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM = 0x80000000;
        private const uint AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY = 0x08000000;
        private const uint AUDCLNT_BUFFERFLAGS_SILENT = 0x2;
        private const string VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK = "VAD\\Process_Loopback";

        [DllImport("Mmdevapi.dll", ExactSpelling = true)]
        private static extern int ActivateAudioInterfaceAsync(
            [MarshalAs(UnmanagedType.LPWStr)] string deviceInterfacePath,
            [MarshalAs(UnmanagedType.LPStruct)] Guid riid,
            IntPtr activationParams,
            IActivateAudioInterfaceCompletionHandler completionHandler,
            out IActivateAudioInterfaceAsyncOperation activationOperation);

        private static readonly Guid IID_IAudioClient = new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
        private static readonly Guid IID_IAudioCaptureClient = new Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317");

        /// <summary>Called on the capture thread with interleaved stereo float samples.</summary>
        public event Action<float[], int> DataAvailable;
        /// <summary>Called once if the stream dies (device unplugged, process loopback unsupported, ...).</summary>
        public event Action<string> Failed;

        private readonly Func<IAudioClient> _activate;
        private readonly bool _loopback;
        private Thread _thread;
        private volatile bool _stop;
        public string Description { get; }

        private WasapiCaptureStream(Func<IAudioClient> activate, bool loopback, string description)
        {
            _activate = activate;
            _loopback = loopback;
            Description = description;
        }

        /// <summary>Capture everything a process (and its children) plays, or everything except it.</summary>
        public static WasapiCaptureStream ForProcess(int pid, bool includeTree, string description)
            => new WasapiCaptureStream(() => ActivateProcessLoopback(pid, includeTree), true, description);

        /// <summary>Capture from a microphone. deviceId null/empty = Windows default recording device.</summary>
        public static WasapiCaptureStream ForInputDevice(string deviceId, string description)
            => new WasapiCaptureStream(() => ActivateDevice(deviceId), false, description);

        public void Start()
        {
            _thread = new Thread(Run) { IsBackground = true, Name = "WASAPI " + Description, Priority = ThreadPriority.AboveNormal };
            _thread.SetApartmentState(ApartmentState.MTA);
            _thread.Start();
        }

        public void Dispose()
        {
            _stop = true;
            try { _thread?.Join(1500); } catch { }
        }

        private static IAudioClient ActivateProcessLoopback(int pid, bool includeTree)
        {
            // AUDIOCLIENT_ACTIVATION_PARAMS { ActivationType = PROCESS_LOOPBACK, { TargetProcessId, ProcessLoopbackMode } }
            IntPtr parms = Marshal.AllocHGlobal(12);
            IntPtr propVariant = Marshal.AllocHGlobal(24);
            try
            {
                Marshal.WriteInt32(parms, 0, 1);
                Marshal.WriteInt32(parms, 4, pid);
                Marshal.WriteInt32(parms, 8, includeTree ? 0 : 1);
                // PROPVARIANT { vt = VT_BLOB, blob = { cbSize, pBlobData } }
                for (int i = 0; i < 24; i += 4) Marshal.WriteInt32(propVariant, i, 0);
                Marshal.WriteInt16(propVariant, 0, 65);
                Marshal.WriteInt32(propVariant, 8, 12);
                Marshal.WriteIntPtr(propVariant, 16, parms);

                var handler = new ActivationHandler();
                int hr = ActivateAudioInterfaceAsync(VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK, IID_IAudioClient, propVariant, handler, out var op);
                if (hr < 0) throw new COMException("Per-app audio capture is not available (needs Windows 10 version 2004 or newer)", hr);
                if (!handler.Result.Task.Wait(TimeSpan.FromSeconds(5)))
                    throw new TimeoutException("Audio activation timed out");
                GC.KeepAlive(op);
                return handler.Result.Task.Result;
            }
            catch (AggregateException ae) { throw ae.InnerException ?? ae; }
            finally
            {
                Marshal.FreeHGlobal(propVariant);
                Marshal.FreeHGlobal(parms);
            }
        }

        private static IAudioClient ActivateDevice(string deviceId)
        {
            var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
            try
            {
                IMMDevice device;
                int hr = string.IsNullOrEmpty(deviceId)
                    ? enumerator.GetDefaultAudioEndpoint(1 /*eCapture*/, 0 /*eConsole*/, out device)
                    : enumerator.GetDevice(deviceId, out device);
                if (hr < 0 || device == null) throw new COMException("Microphone not found", hr);
                var iid = IID_IAudioClient;
                hr = device.Activate(ref iid, 0x17 /*CLSCTX_ALL*/, IntPtr.Zero, out var client);
                Marshal.ThrowExceptionForHR(hr);
                return (IAudioClient)client;
            }
            finally { Marshal.ReleaseComObject(enumerator); }
        }

        private static IntPtr AllocFormat(bool isFloat)
        {
            // WAVEFORMATEX (18 bytes)
            IntPtr f = Marshal.AllocHGlobal(20);
            short bits = (short)(isFloat ? 32 : 16);
            short block = (short)(Channels * bits / 8);
            Marshal.WriteInt16(f, 0, (short)(isFloat ? 3 : 1)); // WAVE_FORMAT_IEEE_FLOAT / PCM
            Marshal.WriteInt16(f, 2, Channels);
            Marshal.WriteInt32(f, 4, SampleRate);
            Marshal.WriteInt32(f, 8, SampleRate * block);
            Marshal.WriteInt16(f, 12, block);
            Marshal.WriteInt16(f, 14, bits);
            Marshal.WriteInt16(f, 16, 0);
            return f;
        }

        private void Run()
        {
            IAudioClient client = null;
            IAudioCaptureClient capture = null;
            var evt = new AutoResetEvent(false);
            try
            {
                client = _activate();
                uint flags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
                if (_loopback) flags |= AUDCLNT_STREAMFLAGS_LOOPBACK;

                bool isFloat = true;
                IntPtr fmt = AllocFormat(true);
                int hr;
                try { hr = client.Initialize(AUDCLNT_SHAREMODE_SHARED, flags, 2_000_000, 0, fmt, IntPtr.Zero); }
                finally { Marshal.FreeHGlobal(fmt); }
                if (hr < 0)
                {
                    // Some drivers refuse float with autoconvert - fall back to 16 bit PCM on a fresh client.
                    Marshal.ReleaseComObject(client);
                    client = _activate();
                    isFloat = false;
                    fmt = AllocFormat(false);
                    try { hr = client.Initialize(AUDCLNT_SHAREMODE_SHARED, flags, 2_000_000, 0, fmt, IntPtr.Zero); }
                    finally { Marshal.FreeHGlobal(fmt); }
                    Marshal.ThrowExceptionForHR(hr);
                }

                Marshal.ThrowExceptionForHR(client.SetEventHandle(evt.SafeWaitHandle.DangerousGetHandle()));
                var iid = IID_IAudioCaptureClient;
                Marshal.ThrowExceptionForHR(client.GetService(ref iid, out var svc));
                capture = (IAudioCaptureClient)svc;
                Marshal.ThrowExceptionForHR(client.Start());

                float[] scratch = new float[48000 * Channels];
                while (!_stop)
                {
                    evt.WaitOne(20);
                    while (!_stop)
                    {
                        hr = capture.GetNextPacketSize(out uint packet);
                        if (hr < 0) throw new COMException("Audio stream lost", hr);
                        if (packet == 0) break;

                        hr = capture.GetBuffer(out IntPtr data, out uint frames, out uint bufFlags, out _, out _);
                        if (hr < 0) throw new COMException("Audio stream lost", hr);
                        if (frames > 0)
                        {
                            int n = (int)frames * Channels;
                            if (scratch.Length < n) scratch = new float[n];
                            if ((bufFlags & AUDCLNT_BUFFERFLAGS_SILENT) != 0) Array.Clear(scratch, 0, n);
                            else if (isFloat) Marshal.Copy(data, scratch, 0, n);
                            else unsafe
                            {
                                short* s = (short*)data;
                                for (int i = 0; i < n; i++) scratch[i] = s[i] / 32768f;
                            }
                            capture.ReleaseBuffer(frames);
                            DataAvailable?.Invoke(scratch, (int)frames);
                        }
                        else capture.ReleaseBuffer(frames);
                    }
                }
                client.Stop();
            }
            catch (Exception ex)
            {
                if (!_stop)
                {
                    Core.Log.Warn($"Audio capture '{Description}' failed: {ex.Message}");
                    Failed?.Invoke(ex.Message);
                }
            }
            finally
            {
                if (capture != null) Marshal.ReleaseComObject(capture);
                if (client != null) Marshal.ReleaseComObject(client);
                evt.Dispose();
            }
        }
    }
}
