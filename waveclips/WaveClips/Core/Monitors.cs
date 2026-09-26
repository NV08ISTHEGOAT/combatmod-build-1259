using System;
using System.Collections.Generic;
using System.Linq;
using WaveClips.Interop;

namespace WaveClips.Core
{
    public static class Monitors
    {
        public static IReadOnlyList<MonitorInfo> Enumerate()
        {
            var names = DisplayConfig.FriendlyNames();
            var screens = System.Windows.Forms.Screen.AllScreens;
            var result = new List<MonitorInfo>();

            List<Dxgi.OutputInfo> outputs;
            try { outputs = Dxgi.EnumerateOutputs(); } catch { outputs = new List<Dxgi.OutputInfo>(); }

            if (outputs.Count > 0)
            {
                foreach (var o in outputs)
                {
                    var scr = screens.FirstOrDefault(s => string.Equals(s.DeviceName, o.DeviceName, StringComparison.OrdinalIgnoreCase));
                    result.Add(new MonitorInfo
                    {
                        DeviceName = o.DeviceName,
                        FriendlyName = names.TryGetValue(o.DeviceName, out var n) ? n : "Display",
                        AdapterName = o.AdapterName,
                        AdapterIndex = o.AdapterIndex,
                        OutputIndex = o.OutputIndex,
                        X = o.Left, Y = o.Top, Width = o.Right - o.Left, Height = o.Bottom - o.Top,
                        RefreshRate = Native.GetRefreshRate(o.DeviceName),
                        IsPrimary = scr?.Primary ?? (o.Left == 0 && o.Top == 0),
                    });
                }
            }
            else
            {
                for (int i = 0; i < screens.Length; i++)
                {
                    var s = screens[i];
                    result.Add(new MonitorInfo
                    {
                        DeviceName = s.DeviceName,
                        FriendlyName = names.TryGetValue(s.DeviceName, out var n) ? n : "Display",
                        AdapterName = "",
                        AdapterIndex = 0,
                        OutputIndex = i,
                        X = s.Bounds.X, Y = s.Bounds.Y, Width = s.Bounds.Width, Height = s.Bounds.Height,
                        RefreshRate = Native.GetRefreshRate(s.DeviceName),
                        IsPrimary = s.Primary,
                    });
                }
            }

            // Number monitors left-to-right like Windows' display settings roughly does.
            var ordered = result.OrderBy(m => m.X).ThenBy(m => m.Y).ToList();
            return ordered.Select((m, i) => new MonitorInfo
            {
                DeviceName = m.DeviceName, FriendlyName = m.FriendlyName, AdapterName = m.AdapterName,
                AdapterIndex = m.AdapterIndex, OutputIndex = m.OutputIndex, X = m.X, Y = m.Y,
                Width = m.Width, Height = m.Height, RefreshRate = m.RefreshRate, IsPrimary = m.IsPrimary, Number = i + 1,
            }).ToList();
        }

        public static MonitorInfo Find(IReadOnlyList<MonitorInfo> list, string deviceName)
            => list.FirstOrDefault(m => string.Equals(m.DeviceName, deviceName, StringComparison.OrdinalIgnoreCase))
               ?? list.FirstOrDefault(m => m.IsPrimary) ?? list.FirstOrDefault();
    }
}
