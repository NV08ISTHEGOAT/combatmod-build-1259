using System;

namespace WaveClips.Core
{
    public enum AudioSourceKind
    {
        /// <summary>Audio of the detected game's process (e.g. Minecraft).</summary>
        Game,
        /// <summary>Audio of a specific app (e.g. Discord).</summary>
        App,
        /// <summary>Everything playing on the PC.</summary>
        SystemAll,
        /// <summary>Everything playing on the PC except one app (e.g. all but Discord).</summary>
        SystemExceptApp,
        /// <summary>A microphone / input device.</summary>
        Microphone,
    }

    /// <summary>One separate audio track in recorded clips.</summary>
    public sealed class AudioTrackConfig : ObservableObject
    {
        private string _name = "Track";
        private bool _enabled = true;
        private AudioSourceKind _source;
        private string _appExe = "";
        private string _deviceId = "";
        private double _volume = 1.0;
        private bool _includeInMix = true;
        private string _color = "#00E5FF";

        public string Id { get; set; } = Guid.NewGuid().ToString("N");
        public string Name { get => _name; set => Set(ref _name, value); }
        public bool Enabled { get => _enabled; set => Set(ref _enabled, value); }
        public AudioSourceKind Source
        {
            get => _source;
            set
            {
                if (!Set(ref _source, value)) return;
                OnPropertyChanged(nameof(NeedsApp));
                OnPropertyChanged(nameof(NeedsDevice));
            }
        }
        /// <summary>Comma separated exe names for App / SystemExceptApp sources.</summary>
        public string AppExe { get => _appExe; set => Set(ref _appExe, value); }
        /// <summary>Endpoint id for Microphone ("" = Windows default communications mic).</summary>
        public string DeviceId { get => _deviceId; set => Set(ref _deviceId, value); }
        /// <summary>Linear gain 0..2 applied while recording.</summary>
        public double Volume { get => _volume; set => Set(ref _volume, Math.Clamp(value, 0, 2)); }
        public bool IncludeInMix { get => _includeInMix; set => Set(ref _includeInMix, value); }
        public string Color { get => _color; set => Set(ref _color, value); }

        public bool NeedsApp => Source == AudioSourceKind.App || Source == AudioSourceKind.SystemExceptApp;
        public bool NeedsDevice => Source == AudioSourceKind.Microphone;

        public static AudioTrackConfig[] Defaults() => new[]
        {
            new AudioTrackConfig { Name = "Minecraft", Source = AudioSourceKind.Game, Color = "#00E5FF" },
            new AudioTrackConfig { Name = "Discord", Source = AudioSourceKind.App, AppExe = "Discord.exe, DiscordPTB.exe, DiscordCanary.exe", Color = "#8C9EFF" },
            new AudioTrackConfig { Name = "Microphone", Source = AudioSourceKind.Microphone, Color = "#3DFFB0" },
            new AudioTrackConfig { Name = "System (everything)", Source = AudioSourceKind.SystemAll, Color = "#FFC940", Enabled = false, IncludeInMix = false },
        };
    }
}
