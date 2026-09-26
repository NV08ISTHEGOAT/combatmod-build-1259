using System;
using System.Collections.Specialized;
using System.ComponentModel;
using System.Diagnostics;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Data;
using System.Windows.Input;
using WaveClips.Core;

namespace WaveClips.Views
{
    public partial class ClipsView : UserControl, IPage
    {
        private readonly ICollectionView _view;
        private string _filter = "all";

        public ClipsView()
        {
            InitializeComponent();
            _view = new CollectionViewSource { Source = AppHost.Library.Items }.View;
            _view.Filter = Filter;
            Grid.ItemsSource = _view;
            AppHost.Library.Items.CollectionChanged += OnItemsChanged;
            ApplySort();
        }

        public void OnShown() { BuildFilters(); UpdateHeader(); }
        public void OnHidden() { }

        private void OnItemsChanged(object sender, NotifyCollectionChangedEventArgs e)
        {
            if (!IsVisible) return;
            BuildFilters();
            UpdateHeader();
        }

        private void BuildFilters()
        {
            FilterChips.Children.Clear();
            void Add(string key, string label)
            {
                var rb = new RadioButton { Content = label, Tag = key, GroupName = "ClipFilter", Style = (Style)FindResource("Chip"), IsChecked = key == _filter };
                rb.Checked += (_, __) => { _filter = (string)rb.Tag; _view.Refresh(); UpdateHeader(); };
                FilterChips.Children.Add(rb);
            }
            var items = AppHost.Library.Items;
            Add("all", $"All  {items.Count}");
            Add("fav", $"★ Favorites  {items.Count(i => i.Favorite)}");
            Add("rec", $"Recordings  {items.Count(i => i.IsRecording)}");
            foreach (var g in items.GroupBy(i => i.Game).OrderByDescending(g => g.Count()))
                Add("game:" + g.Key, $"{g.Key}  {g.Count()}");
        }

        private bool Filter(object o)
        {
            var c = (ClipItem)o;
            if (_filter == "fav" && !c.Favorite) return false;
            if (_filter == "rec" && !c.IsRecording) return false;
            if (_filter.StartsWith("game:") && c.Game != _filter.Substring(5)) return false;
            var q = Search?.Text?.Trim();
            return string.IsNullOrEmpty(q) || c.Title.IndexOf(q, StringComparison.OrdinalIgnoreCase) >= 0 ||
                   c.Game.IndexOf(q, StringComparison.OrdinalIgnoreCase) >= 0;
        }

        private void UpdateHeader()
        {
            var shown = _view.Cast<ClipItem>().ToList();
            CountText.Text = $"{shown.Count} clip{(shown.Count == 1 ? "" : "s")}";
            long bytes = shown.Sum(c => c.Size);
            SizeText.Text = bytes > 0 ? $"{bytes / (double)(1 << 30):0.00} GB  ·  {AppHost.Settings.ClipFolder}" : AppHost.Settings.ClipFolder;
            EmptyState.Visibility = shown.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            var hk = AppHost.Settings.ClipHotkey;
            EmptyHint.Text = AppHost.Library.Items.Count == 0
                ? $"Turn on the replay buffer and press {(hk == null || hk.IsEmpty ? "your clip hotkey" : hk.ToString())} in game to save your first clip."
                : "Nothing matches this filter.";
        }

        private void OnSearch(object sender, TextChangedEventArgs e) { _view?.Refresh(); if (IsLoaded) UpdateHeader(); }

        private void OnSort(object sender, SelectionChangedEventArgs e) { if (_view != null) ApplySort(); }

        private void ApplySort()
        {
            _view.SortDescriptions.Clear();
            switch (SortBox.SelectedIndex)
            {
                case 1: _view.SortDescriptions.Add(new SortDescription(nameof(ClipItem.Created), ListSortDirection.Ascending)); break;
                case 2: _view.SortDescriptions.Add(new SortDescription(nameof(ClipItem.Duration), ListSortDirection.Descending)); break;
                case 3: _view.SortDescriptions.Add(new SortDescription(nameof(ClipItem.Size), ListSortDirection.Descending)); break;
                case 4: _view.SortDescriptions.Add(new SortDescription(nameof(ClipItem.Title), ListSortDirection.Ascending)); break;
                default: _view.SortDescriptions.Add(new SortDescription(nameof(ClipItem.Created), ListSortDirection.Descending)); break;
            }
        }

        private async void OnRefresh(object sender, RoutedEventArgs e)
        {
            await AppHost.Library.RefreshAsync();
            BuildFilters();
            UpdateHeader();
        }

        private void OnOpenFolder(object sender, RoutedEventArgs e)
        {
            try { System.IO.Directory.CreateDirectory(AppHost.Settings.ClipFolder); Process.Start("explorer.exe", $"\"{AppHost.Settings.ClipFolder}\""); } catch { }
        }

        private static ClipItem ItemOf(object sender) => sender switch
        {
            MenuItem mi => (mi.Parent as ContextMenu)?.PlacementTarget is FrameworkElement fe ? fe.Tag as ClipItem : mi.DataContext as ClipItem,
            FrameworkElement f => f.Tag as ClipItem ?? f.DataContext as ClipItem,
            _ => null,
        };

        private void OnCardClick(object sender, MouseButtonEventArgs e)
        {
            if (e.OriginalSource is DependencyObject d && FindParent<ToggleButton>(d) != null) return;
            if (ItemOf(sender) is ClipItem c) ((MainWindow)Window.GetWindow(this)).OpenInEditor(c.Path);
        }

        private static T FindParent<T>(DependencyObject d) where T : DependencyObject
        {
            while (d != null && d is not T) d = System.Windows.Media.VisualTreeHelper.GetParent(d);
            return d as T;
        }

        private void OnFavoriteClick(object sender, RoutedEventArgs e)
        {
            if (ItemOf(sender) is ClipItem c) AppHost.Library.SetFavorite(c, !c.Favorite);
            e.Handled = true;
            BuildFilters();
        }

        private void OnMenuEdit(object sender, RoutedEventArgs e) { if (ItemOf(sender) is ClipItem c) ((MainWindow)Window.GetWindow(this)).OpenInEditor(c.Path); }

        private void OnMenuPlay(object sender, RoutedEventArgs e)
        {
            if (ItemOf(sender) is ClipItem c) try { Process.Start(new ProcessStartInfo(c.Path) { UseShellExecute = true }); } catch { }
        }

        private void OnMenuCopy(object sender, RoutedEventArgs e)
        {
            if (ItemOf(sender) is not ClipItem c) return;
            var files = new System.Collections.Specialized.StringCollection { c.Path };
            Clipboard.SetFileDropList(files);
            AppHost.Notifier.Show(Capture.NotifyKind.Info, "Copied!", "Paste it straight into Discord with Ctrl+V");
        }

        private void OnMenuShow(object sender, RoutedEventArgs e)
        {
            if (ItemOf(sender) is ClipItem c) try { Process.Start("explorer.exe", $"/select,\"{c.Path}\""); } catch { }
        }

        private void OnMenuRename(object sender, RoutedEventArgs e)
        {
            if (ItemOf(sender) is not ClipItem c) return;
            var name = InputDialog.Ask(Window.GetWindow(this), "Rename clip", "New name:", c.Title);
            if (name == null || name == c.Title) return;
            try
            {
                if (!AppHost.Library.Rename(c, name)) Msg.Show("That name is taken or invalid.", "WaveClips");
            }
            catch (Exception ex) { Msg.Show(ex.Message, "Rename failed"); }
        }

        private void OnMenuFavorite(object sender, RoutedEventArgs e)
        {
            if (ItemOf(sender) is ClipItem c) { AppHost.Library.SetFavorite(c, !c.Favorite); BuildFilters(); }
        }

        private void OnMenuDelete(object sender, RoutedEventArgs e)
        {
            if (ItemOf(sender) is not ClipItem c) return;
            if (Msg.Show($"Move \"{c.Title}\" to the Recycle Bin?", "Delete clip", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
            try { AppHost.Library.Delete(c); UpdateHeader(); BuildFilters(); }
            catch (Exception ex) { Msg.Show(ex.Message, "Delete failed"); }
        }
    }
}
