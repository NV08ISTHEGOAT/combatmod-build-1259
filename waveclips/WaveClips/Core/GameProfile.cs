using System;
using System.Linq;

namespace WaveClips.Core
{
    /// <summary>A game WaveClips watches for. When its process runs, clips get tagged with it and the
    /// "Game" audio track captures that process only.</summary>
    public sealed class GameProfile : ObservableObject
    {
        private string _name = "New Game";
        private string _exeNames = "";
        private string _titleHints = "";
        private bool _enabled = true;

        public string Id { get; set; } = Guid.NewGuid().ToString("N");

        public string Name { get => _name; set => Set(ref _name, value); }

        /// <summary>Comma separated executable names, e.g. "javaw.exe, java.exe".</summary>
        public string ExeNames { get => _exeNames; set => Set(ref _exeNames, value); }

        /// <summary>Optional comma separated words; at least one must appear in the window title.
        /// Needed for games like Minecraft Java that run inside a generic javaw.exe.</summary>
        public string WindowTitleHints { get => _titleHints; set => Set(ref _titleHints, value); }

        public bool Enabled { get => _enabled; set => Set(ref _enabled, value); }

        public string[] ExeList() => Split(ExeNames).Select(NormalizeExe).ToArray();
        public string[] TitleHintList() => Split(WindowTitleHints);

        public static string NormalizeExe(string exe)
        {
            exe = exe.Trim();
            return exe.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ? exe[..^4] : exe;
        }

        internal static string[] Split(string s) =>
            (s ?? "").Split(new[] { ',', ';' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);

        public static GameProfile[] Defaults() => new[]
        {
            new GameProfile { Name = "Minecraft", ExeNames = "javaw.exe, java.exe, Minecraft.Windows.exe",
                              WindowTitleHints = "Minecraft, Lunar Client, Badlion, Feather, LabyMod, Wave" },
            new GameProfile { Name = "Fortnite", ExeNames = "FortniteClient-Win64-Shipping.exe", Enabled = false },
            new GameProfile { Name = "Valorant", ExeNames = "VALORANT-Win64-Shipping.exe", Enabled = false },
            new GameProfile { Name = "Roblox", ExeNames = "RobloxPlayerBeta.exe", Enabled = false },
            new GameProfile { Name = "Counter-Strike 2", ExeNames = "cs2.exe", Enabled = false },
        };
    }
}
