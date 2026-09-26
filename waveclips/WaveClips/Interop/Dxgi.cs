using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace WaveClips.Interop
{
    /// <summary>Just enough DXGI to list adapters/outputs in the same order FFmpeg's ddagrab uses them.</summary>
    internal static class Dxgi
    {
        public sealed class OutputInfo
        {
            public int AdapterIndex;
            public int OutputIndex;
            public string AdapterName;
            public string DeviceName; // \\.\DISPLAYn
            public int Left, Top, Right, Bottom;
        }

        [DllImport("dxgi.dll")]
        private static extern int CreateDXGIFactory1(ref Guid riid, out IntPtr factory);

        [StructLayout(LayoutKind.Sequential)]
        private struct RECT { public int Left, Top, Right, Bottom; }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct DXGI_OUTPUT_DESC
        {
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string DeviceName;
            public RECT DesktopCoordinates;
            public int AttachedToDesktop;
            public int Rotation;
            public IntPtr Monitor;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct DXGI_ADAPTER_DESC1
        {
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string Description;
            public uint VendorId, DeviceId, SubSysId, Revision;
            public UIntPtr DedicatedVideoMemory, DedicatedSystemMemory, SharedSystemMemory;
            public long AdapterLuid;
            public uint Flags;
        }

        // vtable slots (IUnknown = 0..2, IDXGIObject = 3..6)
        private const int Factory1_EnumAdapters1 = 12;
        private const int Adapter_EnumOutputs = 7;
        private const int Adapter1_GetDesc1 = 10;
        private const int Output_GetDesc = 7;

        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        private delegate int EnumDelegate(IntPtr self, uint index, out IntPtr result);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        private delegate int AdapterDescDelegate(IntPtr self, out DXGI_ADAPTER_DESC1 desc);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        private delegate int OutputDescDelegate(IntPtr self, out DXGI_OUTPUT_DESC desc);

        private static T Slot<T>(IntPtr com, int slot) where T : Delegate
        {
            var vtbl = Marshal.ReadIntPtr(com);
            return Marshal.GetDelegateForFunctionPointer<T>(Marshal.ReadIntPtr(vtbl, slot * IntPtr.Size));
        }

        public static List<OutputInfo> EnumerateOutputs()
        {
            var list = new List<OutputInfo>();
            var iid = new Guid("770aae78-f26f-4dba-a829-253c83d1b387"); // IDXGIFactory1
            if (CreateDXGIFactory1(ref iid, out var factory) < 0 || factory == IntPtr.Zero) return list;
            try
            {
                var enumAdapters = Slot<EnumDelegate>(factory, Factory1_EnumAdapters1);
                for (uint a = 0; enumAdapters(factory, a, out var adapter) >= 0; a++)
                {
                    try
                    {
                        Slot<AdapterDescDelegate>(adapter, Adapter1_GetDesc1)(adapter, out var ad);
                        if ((ad.Flags & 2) != 0) continue; // DXGI_ADAPTER_FLAG_SOFTWARE
                        var enumOutputs = Slot<EnumDelegate>(adapter, Adapter_EnumOutputs);
                        for (uint o = 0; enumOutputs(adapter, o, out var output) >= 0; o++)
                        {
                            try
                            {
                                Slot<OutputDescDelegate>(output, Output_GetDesc)(output, out var od);
                                if (od.AttachedToDesktop == 0) continue;
                                list.Add(new OutputInfo
                                {
                                    AdapterIndex = (int)a,
                                    OutputIndex = (int)o,
                                    AdapterName = ad.Description,
                                    DeviceName = od.DeviceName,
                                    Left = od.DesktopCoordinates.Left,
                                    Top = od.DesktopCoordinates.Top,
                                    Right = od.DesktopCoordinates.Right,
                                    Bottom = od.DesktopCoordinates.Bottom,
                                });
                            }
                            finally { Marshal.Release(output); }
                        }
                    }
                    finally { Marshal.Release(adapter); }
                }
            }
            catch (Exception ex) { Core.Log.Error("DXGI enumeration failed", ex); }
            finally { Marshal.Release(factory); }
            return list;
        }
    }

    /// <summary>Resolves real monitor names ("ASUS VG27A") via the DisplayConfig API.</summary>
    internal static class DisplayConfig
    {
        [StructLayout(LayoutKind.Sequential)]
        private struct LUID { public uint Low; public int High; }

        [StructLayout(LayoutKind.Sequential)]
        private struct PATH_SOURCE { public LUID adapterId; public uint id, modeInfoIdx, statusFlags; }

        [StructLayout(LayoutKind.Sequential)]
        private struct PATH_TARGET
        {
            public LUID adapterId; public uint id, modeInfoIdx, outputTechnology, rotation, scaling;
            public uint refreshNum, refreshDen, scanLineOrdering; public int targetAvailable; public uint statusFlags;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct PATH_INFO { public PATH_SOURCE sourceInfo; public PATH_TARGET targetInfo; public uint flags; }

        [StructLayout(LayoutKind.Sequential, Size = 64)]
        private struct MODE_INFO { public uint infoType, id; public LUID adapterId; }

        [StructLayout(LayoutKind.Sequential)]
        private struct HEADER { public int type; public int size; public LUID adapterId; public uint id; }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct SOURCE_NAME
        {
            public HEADER header;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string viewGdiDeviceName;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct TARGET_NAME
        {
            public HEADER header;
            public uint flags, outputTechnology;
            public ushort edidManufactureId, edidProductCodeId;
            public uint connectorInstance;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string monitorFriendlyDeviceName;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string monitorDevicePath;
        }

        [DllImport("user32.dll")] private static extern int GetDisplayConfigBufferSizes(uint flags, out uint numPaths, out uint numModes);
        [DllImport("user32.dll")] private static extern int QueryDisplayConfig(uint flags, ref uint numPaths, [Out] PATH_INFO[] paths, ref uint numModes, [Out] MODE_INFO[] modes, IntPtr topology);
        [DllImport("user32.dll")] private static extern int DisplayConfigGetDeviceInfo(ref SOURCE_NAME req);
        [DllImport("user32.dll")] private static extern int DisplayConfigGetDeviceInfo(ref TARGET_NAME req);

        /// <summary>GDI device name (\\.\DISPLAY1) -> friendly monitor name.</summary>
        public static Dictionary<string, string> FriendlyNames()
        {
            var map = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            try
            {
                const uint QDC_ONLY_ACTIVE_PATHS = 2;
                if (GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS, out var np, out var nm) != 0) return map;
                var paths = new PATH_INFO[np];
                var modes = new MODE_INFO[nm];
                if (QueryDisplayConfig(QDC_ONLY_ACTIVE_PATHS, ref np, paths, ref nm, modes, IntPtr.Zero) != 0) return map;
                for (int i = 0; i < np; i++)
                {
                    var src = new SOURCE_NAME();
                    src.header.type = 1; // GET_SOURCE_NAME
                    src.header.size = Marshal.SizeOf<SOURCE_NAME>();
                    src.header.adapterId = paths[i].sourceInfo.adapterId;
                    src.header.id = paths[i].sourceInfo.id;
                    if (DisplayConfigGetDeviceInfo(ref src) != 0) continue;

                    var tgt = new TARGET_NAME();
                    tgt.header.type = 2; // GET_TARGET_NAME
                    tgt.header.size = Marshal.SizeOf<TARGET_NAME>();
                    tgt.header.adapterId = paths[i].targetInfo.adapterId;
                    tgt.header.id = paths[i].targetInfo.id;
                    if (DisplayConfigGetDeviceInfo(ref tgt) != 0) continue;
                    if (!string.IsNullOrWhiteSpace(tgt.monitorFriendlyDeviceName))
                        map[src.viewGdiDeviceName] = tgt.monitorFriendlyDeviceName;
                }
            }
            catch (Exception ex) { Core.Log.Warn("DisplayConfig names unavailable: " + ex.Message); }
            return map;
        }
    }
}
