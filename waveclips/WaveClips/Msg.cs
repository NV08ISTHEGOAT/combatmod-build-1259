using System;
using System.Windows;
using WaveClips.Core;

namespace WaveClips
{
    /// <summary>Message boxes that never block unattended runs (the self-test logs them instead).</summary>
    public static class Msg
    {
        public static bool Quiet { get; set; }
        public static event Action<string> Suppressed;

        public static MessageBoxResult Show(string text, string caption = "WaveClips",
                                            MessageBoxButton buttons = MessageBoxButton.OK, MessageBoxImage icon = MessageBoxImage.None)
        {
            if (!Quiet) return MessageBox.Show(text, caption, buttons, icon);
            Log.Warn($"[dialog] {caption}: {text}");
            Suppressed?.Invoke($"{caption}: {text}");
            return buttons == MessageBoxButton.YesNo || buttons == MessageBoxButton.YesNoCancel ? MessageBoxResult.Yes : MessageBoxResult.OK;
        }
    }
}
