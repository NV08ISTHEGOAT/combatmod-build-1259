using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Threading;
using WaveClips.Interop;

namespace WaveClips.Core
{
    public enum HotkeyAction { SaveClip, ToggleRecording, ToggleBuffer }

    /// <summary>
    /// Global hotkeys through low-level keyboard/mouse hooks, so they work while a fullscreen game has focus
    /// and support mouse side buttons. Keys are never swallowed - the game still receives them.
    /// </summary>
    public sealed class HotkeyManager : IDisposable
    {
        private const int VK_SHIFT = 0x10, VK_CONTROL = 0x11, VK_MENU = 0x12, VK_LWIN = 0x5B, VK_RWIN = 0x5C;

        private readonly Thread _thread;
        private uint _threadId;
        private IntPtr _kbHook, _mouseHook;
        private Native.LowLevelProc _kbProc, _mouseProc; // keep delegates alive
        private readonly HashSet<int> _down = new HashSet<int>();
        private volatile KeyValuePair<Hotkey, HotkeyAction>[] _bindings = Array.Empty<KeyValuePair<Hotkey, HotkeyAction>>();
        private readonly ManualResetEventSlim _ready = new ManualResetEventSlim();

        /// <summary>While true (e.g. the user is recording a new hotkey) nothing fires.</summary>
        public volatile bool Suspended;

        /// <summary>Raised on the hook thread - marshal to the UI yourself.</summary>
        public event Action<HotkeyAction> Pressed;

        public HotkeyManager()
        {
            _thread = new Thread(HookThread) { IsBackground = true, Name = "Hotkeys" };
            _thread.Start();
            _ready.Wait(2000);
        }

        public void SetBindings(IEnumerable<KeyValuePair<Hotkey, HotkeyAction>> bindings)
        {
            var list = new List<KeyValuePair<Hotkey, HotkeyAction>>();
            foreach (var b in bindings) if (b.Key != null && !b.Key.IsEmpty) list.Add(b);
            _bindings = list.ToArray();
        }

        private void HookThread()
        {
            _threadId = Native.GetCurrentThreadId();
            _kbProc = KeyboardProc;
            _mouseProc = MouseProc;
            var mod = Native.GetModuleHandle(null);
            _kbHook = Native.SetWindowsHookEx(Native.WH_KEYBOARD_LL, _kbProc, mod, 0);
            _mouseHook = Native.SetWindowsHookEx(Native.WH_MOUSE_LL, _mouseProc, mod, 0);
            if (_kbHook == IntPtr.Zero) Log.Error("Keyboard hook failed: " + Marshal.GetLastWin32Error());
            _ready.Set();
            while (Native.GetMessage(out var msg, IntPtr.Zero, 0, 0) > 0) { /* hooks are serviced inside GetMessage */ }
            if (_kbHook != IntPtr.Zero) Native.UnhookWindowsHookEx(_kbHook);
            if (_mouseHook != IntPtr.Zero) Native.UnhookWindowsHookEx(_mouseHook);
        }

        private static bool IsDown(int vk) => (Native.GetAsyncKeyState(vk) & 0x8000) != 0;

        private IntPtr KeyboardProc(int nCode, IntPtr wParam, IntPtr lParam)
        {
            if (nCode >= 0)
            {
                int msg = wParam.ToInt32();
                var data = Marshal.PtrToStructure<Native.KBDLLHOOKSTRUCT>(lParam);
                int vk = (int)data.vkCode;
                if (msg == Native.WM_KEYDOWN || msg == Native.WM_SYSKEYDOWN)
                {
                    if (_down.Add(vk)) Check(vk, 0);
                }
                else if (msg == Native.WM_KEYUP || msg == Native.WM_SYSKEYUP)
                {
                    _down.Remove(vk);
                }
            }
            return Native.CallNextHookEx(_kbHook, nCode, wParam, lParam);
        }

        private IntPtr MouseProc(int nCode, IntPtr wParam, IntPtr lParam)
        {
            if (nCode >= 0)
            {
                int msg = wParam.ToInt32();
                if (msg == Native.WM_MBUTTONDOWN) Check(0, Hotkey.MouseMiddle);
                else if (msg == Native.WM_XBUTTONDOWN)
                {
                    var data = Marshal.PtrToStructure<Native.MSLLHOOKSTRUCT>(lParam);
                    int button = (int)(data.mouseData >> 16) == 1 ? Hotkey.MouseX1 : Hotkey.MouseX2;
                    Check(0, button);
                }
            }
            return Native.CallNextHookEx(_mouseHook, nCode, wParam, lParam);
        }

        private void Check(int vk, int mouseButton)
        {
            if (Suspended) return;
            bool ctrl = IsDown(VK_CONTROL), alt = IsDown(VK_MENU), shift = IsDown(VK_SHIFT), win = IsDown(VK_LWIN) || IsDown(VK_RWIN);
            foreach (var b in _bindings)
            {
                var h = b.Key;
                if (mouseButton != 0 ? h.MouseButton != mouseButton : (h.MouseButton != 0 || h.VirtualKey != vk)) continue;
                // A modifier key that *is* the hotkey counts as pressed; otherwise modifiers must match exactly.
                if (h.Ctrl != ctrl && !IsModifierVk(vk, VK_CONTROL)) continue;
                if (h.Alt != alt && !IsModifierVk(vk, VK_MENU)) continue;
                if (h.Shift != shift && !IsModifierVk(vk, VK_SHIFT)) continue;
                if (h.Win != win) continue;
                var action = b.Value;
                ThreadPool.QueueUserWorkItem(_ => Pressed?.Invoke(action));
            }
        }

        private static bool IsModifierVk(int vk, int family) => family switch
        {
            VK_CONTROL => vk == 0x11 || vk == 0xA2 || vk == 0xA3,
            VK_MENU => vk == 0x12 || vk == 0xA4 || vk == 0xA5,
            VK_SHIFT => vk == 0x10 || vk == 0xA0 || vk == 0xA1,
            _ => false,
        };

        public void Dispose()
        {
            if (_threadId != 0) Native.PostThreadMessage(_threadId, Native.WM_QUIT, IntPtr.Zero, IntPtr.Zero);
        }
    }
}
