using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using WaveClips.Core;

namespace WaveClips.Views
{
    public partial class GamesView : UserControl, IPage
    {
        public GamesView()
        {
            InitializeComponent();
            DataContext = AppHost.State;
            GameList.ItemsSource = AppHost.Settings.Games;
            AppHost.State.PropertyChanged += (_, __) => UpdateSub();
        }

        public void OnShown() { UpdateSub(); AppHost.Games.PollNow(); }
        public void OnHidden() { AppHost.Games.PollNow(); }

        private void UpdateSub()
        {
            var g = AppHost.State.Game;
            DetectedSub.Text = g == null ? "Start one of the enabled games below and it shows up here within a couple of seconds."
                                         : $"{g.WindowTitle}  ·  process id {g.ProcessId}";
        }

        private void OnAddManual(object sender, RoutedEventArgs e)
        {
            AppHost.Settings.Games.Insert(0, new GameProfile { Name = "New game", ExeNames = "game.exe" });
        }

        private void OnAddRunning(object sender, RoutedEventArgs e)
        {
            var menu = new ContextMenu { PlacementTarget = AddRunning, MaxHeight = 520 };
            var seen = new SortedDictionary<string, (string title, string path)>(StringComparer.OrdinalIgnoreCase);
            foreach (var p in Process.GetProcesses())
            {
                try
                {
                    if (p.MainWindowHandle == IntPtr.Zero || p.Id == Environment.ProcessId || string.IsNullOrWhiteSpace(p.MainWindowTitle)) continue;
                    if (!seen.ContainsKey(p.ProcessName)) seen[p.ProcessName] = (p.MainWindowTitle, null);
                }
                catch { }
                finally { p.Dispose(); }
            }
            if (seen.Count == 0) menu.Items.Add(new MenuItem { Header = "No windows found", IsEnabled = false });
            foreach (var kv in seen)
            {
                var title = kv.Value.title.Length > 60 ? kv.Value.title.Substring(0, 60) + "…" : kv.Value.title;
                var item = new MenuItem { Header = $"{title}   ({kv.Key}.exe)" };
                item.Click += (_, __) =>
                {
                    bool generic = kv.Key.Equals("javaw", StringComparison.OrdinalIgnoreCase) || kv.Key.Equals("java", StringComparison.OrdinalIgnoreCase);
                    AppHost.Settings.Games.Insert(0, new GameProfile
                    {
                        Name = generic ? kv.Value.title.Split(' ')[0] : kv.Key,
                        ExeNames = kv.Key + ".exe",
                        WindowTitleHints = generic ? kv.Value.title.Split(' ')[0] : "",
                    });
                    AppHost.Games.PollNow();
                };
                menu.Items.Add(item);
            }
            menu.IsOpen = true;
        }

        private void OnRemove(object sender, RoutedEventArgs e)
        {
            if (((FrameworkElement)sender).Tag is GameProfile g &&
                Msg.Show($"Stop watching for {g.Name}?", "WaveClips", MessageBoxButton.YesNo) == MessageBoxResult.Yes)
                AppHost.Settings.Games.Remove(g);
        }
    }
}
