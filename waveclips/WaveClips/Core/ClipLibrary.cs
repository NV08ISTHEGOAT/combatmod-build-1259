using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace WaveClips.Core
{
    public sealed class ClipMeta
    {
        public string Game { get; set; }
        public bool Favorite { get; set; }
        public bool IsRecording { get; set; }
        public double Duration { get; set; }
        public string[] Tracks { get; set; } = Array.Empty<string>();
        public DateTime Created { get; set; }
    }

    public sealed class ClipItem : ObservableObject
    {
        private string _path;
        private ImageSource _thumb;
        private double _duration;
        private bool _favorite;
        private long _size;

        public string Path { get => _path; set { if (Set(ref _path, value)) { OnPropertyChanged(nameof(Title)); } } }
        public string Title => System.IO.Path.GetFileNameWithoutExtension(Path);
        public string Game { get; set; } = "Desktop";
        public DateTime Created { get; set; }
        public bool IsRecording { get; set; }
        public string[] Tracks { get; set; } = Array.Empty<string>();
        public long Size { get => _size; set { if (Set(ref _size, value)) OnPropertyChanged(nameof(SizeText)); } }
        public double Duration { get => _duration; set { if (Set(ref _duration, value)) OnPropertyChanged(nameof(DurationText)); } }
        public bool Favorite { get => _favorite; set => Set(ref _favorite, value); }
        public ImageSource Thumbnail { get => _thumb; set => Set(ref _thumb, value); }

        public string DurationText => Duration <= 0 ? "--:--" : TimeSpan.FromSeconds(Duration).ToString(Duration >= 3600 ? @"h\:mm\:ss" : @"m\:ss");
        public string SizeText => Size >= 1 << 30 ? $"{Size / (double)(1 << 30):0.0} GB" : $"{Size / (double)(1 << 20):0.0} MB";
        public string DateText => Created.Date == DateTime.Today ? "Today " + Created.ToString("HH:mm")
                                  : Created.Date == DateTime.Today.AddDays(-1) ? "Yesterday " + Created.ToString("HH:mm")
                                  : Created.ToString("d MMM yyyy  HH:mm");
        public string TracksText => Tracks.Length == 0 ? "" : string.Join(" · ", Tracks);
        public string Kind => IsRecording ? "RECORDING" : "CLIP";
    }

    /// <summary>All clips on disk (clip folder, recursively) plus metadata WaveClips knows about them.</summary>
    public sealed class ClipLibrary
    {
        private static readonly string[] Extensions = { ".mp4", ".mkv", ".mov", ".webm", ".gif" };
        private readonly AppSettings _settings;
        private Dictionary<string, ClipMeta> _index = new Dictionary<string, ClipMeta>(StringComparer.OrdinalIgnoreCase);
        private readonly SemaphoreSlim _thumbGate = new SemaphoreSlim(2);
        private readonly object _indexGate = new object();

        public ObservableCollection<ClipItem> Items { get; } = new ObservableCollection<ClipItem>();
        public event Action<ClipItem> ClipAdded;

        public ClipLibrary(AppSettings settings)
        {
            _settings = settings;
            try
            {
                if (File.Exists(Paths.LibraryFile))
                    _index = JsonSerializer.Deserialize<Dictionary<string, ClipMeta>>(File.ReadAllText(Paths.LibraryFile))
                             ?? _index;
                _index = new Dictionary<string, ClipMeta>(_index, StringComparer.OrdinalIgnoreCase);
            }
            catch (Exception ex) { Log.Warn("Library index unreadable: " + ex.Message); }
        }

        private void SaveIndex()
        {
            try
            {
                string json;
                lock (_indexGate) json = JsonSerializer.Serialize(_index, new JsonSerializerOptions { WriteIndented = true });
                File.WriteAllText(Paths.LibraryFile, json);
            }
            catch (Exception ex) { Log.Warn("Library index save failed: " + ex.Message); }
        }

        /// <summary>Rescans the clip folder. Call on the UI thread.</summary>
        public async Task RefreshAsync()
        {
            var root = _settings.ClipFolder;
            var files = await Task.Run(() =>
            {
                try
                {
                    if (!Directory.Exists(root)) return new List<FileInfo>();
                    return new DirectoryInfo(root).EnumerateFiles("*", SearchOption.AllDirectories)
                        .Where(f => Extensions.Contains(f.Extension.ToLowerInvariant()) && !f.Name.EndsWith(".part.mp4"))
                        .OrderByDescending(f => f.CreationTime).ToList();
                }
                catch (Exception ex) { Log.Warn("Clip scan failed: " + ex.Message); return new List<FileInfo>(); }
            });

            var existing = Items.ToDictionary(i => i.Path, StringComparer.OrdinalIgnoreCase);
            var fresh = new List<ClipItem>();
            foreach (var f in files)
            {
                if (existing.TryGetValue(f.FullName, out var item)) { item.Size = f.Length; fresh.Add(item); continue; }
                fresh.Add(MakeItem(f, root));
            }
            Items.Clear();
            foreach (var i in fresh) Items.Add(i);
            foreach (var i in fresh.Where(i => i.Thumbnail == null || i.Duration <= 0)) _ = LoadExtrasAsync(i);
        }

        private ClipItem MakeItem(FileInfo f, string root)
        {
            ClipMeta meta;
            lock (_indexGate) _index.TryGetValue(f.FullName, out meta);
            string game = meta?.Game;
            if (string.IsNullOrEmpty(game))
            {
                var parent = f.Directory?.FullName ?? "";
                game = string.Equals(parent.TrimEnd('\\'), root.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase) ? "Desktop" : f.Directory!.Name;
            }
            return new ClipItem
            {
                Path = f.FullName,
                Game = game,
                Created = meta?.Created ?? f.CreationTime,
                IsRecording = meta?.IsRecording ?? f.Name.Contains("Recording"),
                Tracks = meta?.Tracks ?? Array.Empty<string>(),
                Duration = meta?.Duration ?? 0,
                Favorite = meta?.Favorite ?? false,
                Size = f.Length,
            };
        }

        /// <summary>Registers a freshly written clip (any thread).</summary>
        public void Add(string path, ClipMeta meta)
        {
            lock (_indexGate) _index[path] = meta;
            SaveIndex();
            Application.Current?.Dispatcher.BeginInvoke(new Action(() =>
            {
                var fi = new FileInfo(path);
                if (!fi.Exists) return;
                var existing = Items.FirstOrDefault(i => string.Equals(i.Path, path, StringComparison.OrdinalIgnoreCase));
                if (existing != null) Items.Remove(existing);
                var item = MakeItem(fi, _settings.ClipFolder);
                Items.Insert(0, item);
                _ = LoadExtrasAsync(item);
                ClipAdded?.Invoke(item);
            }));
        }

        public void SetFavorite(ClipItem item, bool fav)
        {
            item.Favorite = fav;
            UpdateMeta(item.Path, m => m.Favorite = fav);
        }

        private void UpdateMeta(string path, Action<ClipMeta> change)
        {
            lock (_indexGate)
            {
                if (!_index.TryGetValue(path, out var m)) _index[path] = m = new ClipMeta { Created = DateTime.Now };
                change(m);
            }
            SaveIndex();
        }

        public ClipMeta GetMeta(string path)
        {
            lock (_indexGate) return _index.TryGetValue(path, out var m) ? m : null;
        }

        public bool Rename(ClipItem item, string newName)
        {
            newName = Paths.SafeFileName(newName);
            if (string.IsNullOrWhiteSpace(newName)) return false;
            var target = System.IO.Path.Combine(System.IO.Path.GetDirectoryName(item.Path)!, newName + System.IO.Path.GetExtension(item.Path));
            if (File.Exists(target)) return false;
            File.Move(item.Path, target);
            lock (_indexGate)
            {
                if (_index.Remove(item.Path, out var meta)) _index[target] = meta;
            }
            SaveIndex();
            item.Path = target;
            return true;
        }

        public void Delete(ClipItem item)
        {
            Microsoft.VisualBasic.FileIO.FileSystem.DeleteFile(item.Path,
                Microsoft.VisualBasic.FileIO.UIOption.OnlyErrorDialogs, Microsoft.VisualBasic.FileIO.RecycleOption.SendToRecycleBin);
            lock (_indexGate) _index.Remove(item.Path);
            SaveIndex();
            Items.Remove(item);
        }

        private async Task LoadExtrasAsync(ClipItem item)
        {
            if (!FFmpeg.Available) return;
            await _thumbGate.WaitAsync();
            try
            {
                if (item.Duration <= 0)
                {
                    var info = await MediaProbe.ProbeAsync(item.Path);
                    item.Duration = info.Duration;
                    if (item.Tracks.Length == 0 && info.Audio.Count > 0) item.Tracks = info.Audio.Select(a => a.Title).ToArray();
                    UpdateMeta(item.Path, m => { m.Duration = info.Duration; if (m.Tracks.Length == 0) m.Tracks = item.Tracks; if (string.IsNullOrEmpty(m.Game)) m.Game = item.Game; });
                }
                var thumb = await ThumbnailAsync(item.Path, item.Duration);
                if (thumb != null) item.Thumbnail = thumb;
            }
            catch (Exception ex) { Log.Warn($"Thumbnail for {item.Path}: {ex.Message}"); }
            finally { _thumbGate.Release(); }
        }

        public static string ThumbPath(string videoPath)
        {
            var fi = new FileInfo(videoPath);
            var key = $"{videoPath}|{fi.Length}|{fi.LastWriteTimeUtc.Ticks}";
            var hash = Convert.ToHexString(SHA1.HashData(Encoding.UTF8.GetBytes(key))).Substring(0, 20);
            return System.IO.Path.Combine(Paths.ThumbCache, hash + ".jpg");
        }

        public static async Task<ImageSource> ThumbnailAsync(string videoPath, double duration)
        {
            var thumb = ThumbPath(videoPath);
            if (!File.Exists(thumb))
            {
                double at = duration > 0 ? Math.Min(duration * 0.35, 5) : 1;
                var r = await FFmpeg.RunAsync(new[]
                {
                    "-hide_banner", "-v", "error", "-y", "-ss", at.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture),
                    "-i", videoPath, "-frames:v", "1", "-vf", "scale=400:-2", "-q:v", "4", thumb,
                });
                if (!r.Success || !File.Exists(thumb)) return null;
            }
            return LoadImage(thumb);
        }

        public static ImageSource LoadImage(string file)
        {
            try
            {
                var bmp = new BitmapImage();
                bmp.BeginInit();
                bmp.CacheOption = BitmapCacheOption.OnLoad;
                bmp.CreateOptions = BitmapCreateOptions.IgnoreImageCache;
                bmp.UriSource = new Uri(file);
                bmp.EndInit();
                bmp.Freeze();
                return bmp;
            }
            catch { return null; }
        }
    }
}
