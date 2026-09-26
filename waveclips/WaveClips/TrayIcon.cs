using System;
using System.Drawing;
using System.Windows;
using System.Windows.Forms;
using WaveClips.Capture;

namespace WaveClips
{
    /// <summary>Notification-area icon: WaveClips keeps clipping while its window is closed.</summary>
    public sealed class TrayIcon : IDisposable
    {
        private readonly NotifyIcon _icon;
        private readonly ToolStripMenuItem _buffer, _record;

        public TrayIcon()
        {
            var menu = new ContextMenuStrip { Renderer = new DarkRenderer(), ShowImageMargin = false };
            menu.Items.Add(new ToolStripMenuItem("Open WaveClips", null, (_, __) => MainWin()?.ShowFromTray()) { Font = new Font(System.Drawing.SystemFonts.MenuFont, System.Drawing.FontStyle.Bold) });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(new ToolStripMenuItem("Save clip", null, (_, __) => _ = AppHost.Capture.SaveClipAsync()));
            _record = new ToolStripMenuItem("Start recording", null, (_, __) => _ = AppHost.Capture.ToggleRecordingAsync());
            _buffer = new ToolStripMenuItem("Replay buffer", null, (_, __) => _ = AppHost.Capture.ToggleBufferAsync());
            menu.Items.Add(_record);
            menu.Items.Add(_buffer);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(new ToolStripMenuItem("Open clips folder", null, (_, __) => OpenFolder()));
            menu.Items.Add(new ToolStripMenuItem("Quit", null, (_, __) => App.Quit()));
            menu.Opening += (_, __) =>
            {
                _record.Text = AppHost.Capture.IsRecording ? "Stop recording" : "Start recording";
                _buffer.Text = AppHost.Capture.BufferEnabled ? "Turn replay buffer off" : "Turn replay buffer on";
            };

            Icon ico;
            try
            {
                var s = System.Windows.Application.GetResourceStream(new Uri("pack://application:,,,/Assets/waveclips.ico"))?.Stream;
                ico = s != null ? new Icon(s) : SystemIcons.Application;
            }
            catch { ico = SystemIcons.Application; }

            _icon = new NotifyIcon { Icon = ico, Text = "WaveClips", ContextMenuStrip = menu, Visible = true };
            _icon.MouseClick += (_, e) => { if (e.Button == MouseButtons.Left) MainWin()?.ShowFromTray(); };
            AppHost.Capture.PropertyChanged += (_, e) =>
            {
                if (e.PropertyName is nameof(CaptureEngine.IsRecording) or nameof(CaptureEngine.State))
                {
                    var c = AppHost.Capture;
                    var text = c.IsRecording ? "WaveClips - RECORDING" : c.State == BufferState.Running ? "WaveClips - replay buffer on" : "WaveClips";
                    _icon.Text = text;
                }
            };
        }

        private static MainWindow MainWin() => System.Windows.Application.Current?.MainWindow as MainWindow;

        private static void OpenFolder()
        {
            try
            {
                System.IO.Directory.CreateDirectory(AppHost.Settings.ClipFolder);
                System.Diagnostics.Process.Start("explorer.exe", $"\"{AppHost.Settings.ClipFolder}\"");
            }
            catch { }
        }

        /// <summary>Only used while the main window is hidden and the overlay is disabled.</summary>
        public void Balloon(NotifyKind kind, string title, string message)
        {
            var main = MainWin();
            if (main != null && main.IsVisible) return;
            if (AppHost.Settings.ClipOverlay) return;
            _icon.ShowBalloonTip(2500, title, string.IsNullOrEmpty(message) ? " " : message,
                kind == NotifyKind.Error ? ToolTipIcon.Warning : ToolTipIcon.Info);
        }

        public void Dispose()
        {
            _icon.Visible = false;
            _icon.Dispose();
        }

        private sealed class DarkRenderer : ToolStripProfessionalRenderer
        {
            public DarkRenderer() : base(new DarkColors()) { }
            protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e)
            {
                e.TextColor = e.Item.Selected ? Color.FromArgb(0, 229, 255) : Color.FromArgb(224, 247, 250);
                base.OnRenderItemText(e);
            }
        }

        private sealed class DarkColors : ProfessionalColorTable
        {
            private static readonly Color Bg = Color.FromArgb(7, 27, 36), Sel = Color.FromArgb(0, 77, 90), Border = Color.FromArgb(0, 229, 255);
            public override Color ToolStripDropDownBackground => Bg;
            public override Color MenuBorder => Border;
            public override Color MenuItemBorder => Sel;
            public override Color MenuItemSelected => Sel;
            public override Color MenuItemSelectedGradientBegin => Sel;
            public override Color MenuItemSelectedGradientEnd => Sel;
            public override Color ImageMarginGradientBegin => Bg;
            public override Color ImageMarginGradientMiddle => Bg;
            public override Color ImageMarginGradientEnd => Bg;
            public override Color SeparatorDark => Color.FromArgb(0, 77, 96);
            public override Color SeparatorLight => Bg;
        }
    }
}
