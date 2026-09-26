namespace WaveClips.Core
{
    public sealed class MonitorInfo
    {
        public string DeviceName { get; init; }      // \\.\DISPLAY1
        public string FriendlyName { get; init; }    // "ASUS VG27A"
        public string AdapterName { get; init; }
        public int AdapterIndex { get; init; }       // DXGI adapter -> -init_hw_device d3d11va=:N
        public int OutputIndex { get; init; }        // ddagrab output_idx
        public int X { get; init; }
        public int Y { get; init; }
        public int Width { get; init; }
        public int Height { get; init; }
        public int RefreshRate { get; init; }
        public bool IsPrimary { get; init; }
        public int Number { get; init; }

        public string Title => $"{Number}. {FriendlyName}";
        public string Details => $"{Width} × {Height}  ·  {RefreshRate} Hz{(IsPrimary ? "  ·  Primary" : "")}";
        public override string ToString() => $"{Title} ({Width}x{Height} @ {RefreshRate} Hz)";
    }
}
