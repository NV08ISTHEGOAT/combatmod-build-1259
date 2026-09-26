using System;
using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using WaveClips.Capture;
using WaveClips.Views;

namespace WaveClips
{
    public partial class MainWindow : Window
    {
        private HomeView _home;
        private ClipsView _clips;
        private EditorView _editor;
        private GamesView _games;
        private SettingsView _settings;
        private bool _reallyClosing;

        public MainWindow()
        {
            InitializeComponent();
            StateChanged += (_, __) =>
            {
                // WindowChrome windows overhang the screen when maximized; pad them back in.
                Frame.Margin = WindowState == WindowState.Maximized ? new Thickness(7) : new Thickness(0);
                MaxButton.Content = WindowState == WindowState.Maximized ? "" : "";
            };
            AppHost.Capture.PropertyChanged += OnCaptureChanged;
            AppHost.State.PropertyChanged += (_, e) => { if (e.PropertyName == nameof(AppState.GameName)) GameLabel.Text = AppHost.State.GameName; };
            AppHost.Settings.PropertyChanged += (_, e) => { if (e.PropertyName == nameof(Core.AppSettings.ClipHotkey)) UpdateClipKey(); };
            UpdateClipKey();
            UpdateStatus();
            Navigate("Home");
        }

        private void UpdateClipKey()
        {
            var h = AppHost.Settings.ClipHotkey;
            ClipKeyText.Text = h == null || h.IsEmpty ? "" : $"[{h}]";
        }

        private void OnCaptureChanged(object sender, PropertyChangedEventArgs e)
        {
            if (e.PropertyName is nameof(CaptureEngine.State) or nameof(CaptureEngine.IsRecording) or nameof(CaptureEngine.RecordingTime))
                UpdateStatus();
        }

        private void UpdateStatus()
        {
            var c = AppHost.Capture;
            (string text, string brush) = c.State switch
            {
                BufferState.Running => (c.BufferEnabled ? "Replay buffer ON" : "Capturing", "Success"),
                BufferState.Starting => ("Starting…", "Warning"),
                BufferState.Error => ("Capture error", "Danger"),
                _ => ("Buffer off", "TextDim"),
            };
            StatusLabel.Text = text;
            StatusDot.Fill = (Brush)FindResource(brush);
            RecordText.Text = c.IsRecording ? "STOP  " + c.RecordingTime : "RECORD";
            RecBadge.Visibility = c.IsRecording ? Visibility.Visible : Visibility.Collapsed;
            RecBadge.Text = "  ●  REC " + c.RecordingTime;
            SideWaves.Intensity = c.IsRecording ? 1.6 : c.State == BufferState.Running ? 1.0 : 0.45;
            SideWaves.Speed = c.IsRecording ? 2.2 : 1.0;
        }

        private void OnNav(object sender, RoutedEventArgs e)
        {
            if (!IsInitialized) return;
            if (sender == NavHome) Show("Home");
            else if (sender == NavClips) Show("Clips");
            else if (sender == NavEditor) Show("Editor");
            else if (sender == NavGames) Show("Games");
            else if (sender == NavSettings) Show("Settings");
        }

        public void Navigate(string page)
        {
            var nav = page switch { "Clips" => NavClips, "Editor" => NavEditor, "Games" => NavGames, "Settings" => NavSettings, _ => NavHome };
            if (nav.IsChecked == true) Show(page);
            else nav.IsChecked = true;
        }

        private void Show(string page)
        {
            UserControl view = page switch
            {
                "Clips" => _clips ??= new ClipsView(),
                "Editor" => _editor ??= new EditorView(),
                "Games" => _games ??= new GamesView(),
                "Settings" => _settings ??= new SettingsView(),
                _ => _home ??= new HomeView(),
            };
            PageTitle.Text = page.ToUpperInvariant();
            if (Host.Content is IPage old) old.OnHidden();
            Host.Content = view;
            if (view is IPage p) p.OnShown();
        }

        /// <summary>Opens a clip in the editor page.</summary>
        public void OpenInEditor(string path)
        {
            _editor ??= new EditorView();
            Navigate("Editor");
            _editor.Load(path);
        }

        public void ShowFromTray()
        {
            Show();
            if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
            Activate();
            Topmost = true;
            Topmost = false;
        }

        private async void OnClip(object sender, RoutedEventArgs e) => await AppHost.Capture.SaveClipAsync();
        private async void OnRecord(object sender, RoutedEventArgs e) => await AppHost.Capture.ToggleRecordingAsync();
        private void OnMinimize(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;
        private void OnMaximize(object sender, RoutedEventArgs e) =>
            WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
        private void OnClose(object sender, RoutedEventArgs e) => Close();

        /// <summary>Called before the app quits so closing isn't turned into "hide to tray".</summary>
        public void PrepareExit() => _reallyClosing = true;

        protected override void OnClosing(CancelEventArgs e)
        {
            if (!_reallyClosing && AppHost.Settings.CloseToTray)
            {
                e.Cancel = true;
                if (Host.Content is IPage p) p.OnHidden();
                Hide();
                return;
            }
            base.OnClosing(e);
        }

        protected override void OnClosed(EventArgs e)
        {
            base.OnClosed(e);
            if (!_reallyClosing) { _reallyClosing = true; App.Quit(); }
        }
    }

    /// <summary>Pages get told when they appear/disappear (to start/stop timers, meters, playback).</summary>
    public interface IPage
    {
        void OnShown();
        void OnHidden();
    }
}
