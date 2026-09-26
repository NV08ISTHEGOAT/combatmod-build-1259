using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using WaveClips.Core;

namespace WaveClips.Controls
{
    /// <summary>Click, then press any key combo or a mouse button (middle / side buttons) to bind it.
    /// Esc cancels, Backspace/Delete clears.</summary>
    public class HotkeyBox : Button
    {
        public static readonly DependencyProperty HotkeyProperty =
            DependencyProperty.Register(nameof(Hotkey), typeof(Hotkey), typeof(HotkeyBox),
                new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.BindsTwoWayByDefault, (d, e) => ((HotkeyBox)d).Refresh()));

        public Hotkey Hotkey { get => (Hotkey)GetValue(HotkeyProperty); set => SetValue(HotkeyProperty, value); }

        /// <summary>Raised when capture starts/ends so global hotkeys can be paused meanwhile.</summary>
        public static event Action<bool> CapturingChanged;

        private bool _capturing;

        public HotkeyBox()
        {
            MinWidth = 180;
            HorizontalContentAlignment = HorizontalAlignment.Left;
            Focusable = true;
            Refresh();
            LostKeyboardFocus += (_, __) => StopCapture();
        }

        protected override void OnClick()
        {
            if (_capturing) return;
            _capturing = true;
            CapturingChanged?.Invoke(true);
            Content = "Press a key or mouse button…";
            Foreground = (Brush)FindResource("Warning");
            Focus();
            Keyboard.Focus(this);
        }

        private void StopCapture()
        {
            if (!_capturing) return;
            _capturing = false;
            CapturingChanged?.Invoke(false);
            Refresh();
        }

        private void Refresh()
        {
            if (_capturing) return;
            Content = Hotkey == null || Hotkey.IsEmpty ? "Not set" : Hotkey.ToString();
            ClearValue(ForegroundProperty);
        }

        protected override void OnPreviewKeyDown(KeyEventArgs e)
        {
            if (!_capturing) { base.OnPreviewKeyDown(e); return; }
            e.Handled = true;
            var key = e.Key == Key.System ? e.SystemKey : e.Key;
            if (key == Key.Escape) { StopCapture(); return; }
            if (key == Key.Back || key == Key.Delete) { Hotkey = new Hotkey(); StopCapture(); return; }
            if (key is Key.LeftCtrl or Key.RightCtrl or Key.LeftAlt or Key.RightAlt or Key.LeftShift or Key.RightShift or Key.LWin or Key.RWin)
                return; // wait for the real key
            var mods = Keyboard.Modifiers;
            Hotkey = new Hotkey
            {
                VirtualKey = KeyInterop.VirtualKeyFromKey(key),
                Ctrl = mods.HasFlag(ModifierKeys.Control),
                Alt = mods.HasFlag(ModifierKeys.Alt),
                Shift = mods.HasFlag(ModifierKeys.Shift),
                Win = mods.HasFlag(ModifierKeys.Windows),
            };
            StopCapture();
        }

        protected override void OnPreviewMouseDown(MouseButtonEventArgs e)
        {
            if (!_capturing) { base.OnPreviewMouseDown(e); return; }
            int button = e.ChangedButton switch
            {
                MouseButton.Middle => Hotkey.MouseMiddle,
                MouseButton.XButton1 => Hotkey.MouseX1,
                MouseButton.XButton2 => Hotkey.MouseX2,
                _ => 0,
            };
            if (button == 0) return;
            e.Handled = true;
            var mods = Keyboard.Modifiers;
            Hotkey = new Hotkey
            {
                MouseButton = button,
                Ctrl = mods.HasFlag(ModifierKeys.Control),
                Alt = mods.HasFlag(ModifierKeys.Alt),
                Shift = mods.HasFlag(ModifierKeys.Shift),
            };
            StopCapture();
        }
    }
}
