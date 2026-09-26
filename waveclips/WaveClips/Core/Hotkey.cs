using System.Text;
using System.Text.Json.Serialization;
using System.Windows.Input;

namespace WaveClips.Core
{
    /// <summary>A keyboard key (with modifiers) or a mouse button used as a global hotkey.</summary>
    public sealed class Hotkey
    {
        public const int MouseMiddle = 3, MouseX1 = 4, MouseX2 = 5;

        public int VirtualKey { get; set; }
        public int MouseButton { get; set; }
        public bool Ctrl { get; set; }
        public bool Alt { get; set; }
        public bool Shift { get; set; }
        public bool Win { get; set; }

        [JsonIgnore] public bool IsEmpty => VirtualKey == 0 && MouseButton == 0;

        public static Hotkey Key(Key key, bool ctrl = false, bool alt = false, bool shift = false)
            => new Hotkey { VirtualKey = KeyInterop.VirtualKeyFromKey(key), Ctrl = ctrl, Alt = alt, Shift = shift };

        public Hotkey Clone() => (Hotkey)MemberwiseClone();

        public bool SameAs(Hotkey o) => o != null && o.VirtualKey == VirtualKey && o.MouseButton == MouseButton
                                        && o.Ctrl == Ctrl && o.Alt == Alt && o.Shift == Shift && o.Win == Win;

        public override string ToString()
        {
            if (IsEmpty) return "Not set";
            var sb = new StringBuilder();
            if (Ctrl) sb.Append("Ctrl + ");
            if (Alt) sb.Append("Alt + ");
            if (Shift) sb.Append("Shift + ");
            if (Win) sb.Append("Win + ");
            if (MouseButton != 0)
                sb.Append(MouseButton switch { MouseMiddle => "Mouse Middle", MouseX1 => "Mouse 4", MouseX2 => "Mouse 5", _ => "Mouse " + MouseButton });
            else
                sb.Append(KeyName(VirtualKey));
            return sb.ToString();
        }

        public static string KeyName(int vk)
        {
            var key = KeyInterop.KeyFromVirtualKey(vk);
            switch (key)
            {
                case System.Windows.Input.Key.D0: case System.Windows.Input.Key.D1: case System.Windows.Input.Key.D2:
                case System.Windows.Input.Key.D3: case System.Windows.Input.Key.D4: case System.Windows.Input.Key.D5:
                case System.Windows.Input.Key.D6: case System.Windows.Input.Key.D7: case System.Windows.Input.Key.D8:
                case System.Windows.Input.Key.D9:
                    return ((int)(key - System.Windows.Input.Key.D0)).ToString();
                case System.Windows.Input.Key.Oem3: return "`";
                case System.Windows.Input.Key.OemMinus: return "-";
                case System.Windows.Input.Key.OemPlus: return "=";
                case System.Windows.Input.Key.OemOpenBrackets: return "[";
                case System.Windows.Input.Key.Oem6: return "]";
                case System.Windows.Input.Key.Oem5: return "\\";
                case System.Windows.Input.Key.Oem1: return ";";
                case System.Windows.Input.Key.OemQuotes: return "'";
                case System.Windows.Input.Key.OemComma: return ",";
                case System.Windows.Input.Key.OemPeriod: return ".";
                case System.Windows.Input.Key.OemQuestion: return "/";
                case System.Windows.Input.Key.Next: return "Page Down";
                case System.Windows.Input.Key.Prior: return "Page Up";
                case System.Windows.Input.Key.Snapshot: return "Print Screen";
                case System.Windows.Input.Key.Capital: return "Caps Lock";
                case System.Windows.Input.Key.Scroll: return "Scroll Lock";
                case System.Windows.Input.Key.Return: return "Enter";
                default: return key.ToString();
            }
        }
    }
}
