using System;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using WaveClips.Core;

namespace WaveClips
{
    public partial class App : Application
    {
        private Mutex _single;
        private EventWaitHandle _showSignal;

        protected override void OnStartup(StartupEventArgs e)
        {
            _single = new Mutex(true, "WaveClips_SingleInstance_Mutex", out bool first);
            _showSignal = new EventWaitHandle(false, EventResetMode.AutoReset, "WaveClips_Show_Event");
            if (!first)
            {
                _showSignal.Set(); // wake the running instance
                Shutdown();
                return;
            }

            DispatcherUnhandledException += OnUiException;
            AppDomain.CurrentDomain.UnhandledException += (_, a) => Log.Error("Unhandled", a.ExceptionObject as Exception);
            TaskScheduler.UnobservedTaskException += (_, a) => { Log.Error("Unobserved task", a.Exception); a.SetObserved(); };

            base.OnStartup(e);
            Log.Info("WaveClips starting");
            int st = Array.IndexOf(e.Args, "--selftest");
            AppHost.Initialize();
            if (st >= 0)
            {
                // Unattended test run: never auto-start capture or hide the window.
                AppHost.Settings.AutoStart = AutoStartMode.Manual;
                AppHost.Settings.StartMinimized = false;
            }

            var win = new MainWindow();
            MainWindow = win;
            bool minimized = st < 0 && (AppHost.Settings.StartMinimized || e.Args.Contains("--minimized"));
            if (!minimized) win.Show();

            if (st >= 0)
            {
                var outDir = st + 1 < e.Args.Length ? e.Args[st + 1] : System.IO.Path.Combine(Paths.LocalData, "selftest");
                _ = RunSelfTestAsync(win, outDir);
                return;
            }

            // Bring the window forward when a second copy is launched.
            var t = new Thread(() =>
            {
                while (_showSignal.WaitOne())
                    Dispatcher.BeginInvoke(new Action(() => ((MainWindow)MainWindow).ShowFromTray()));
            }) { IsBackground = true, Name = "Single instance" };
            t.Start();

            _ = AppHost.StartAsync();
        }

        private static async Task RunSelfTestAsync(MainWindow win, string outDir)
        {
            int failures;
            try
            {
                await AppHost.StartAsync();
                failures = await SelfTest.RunAsync(win, outDir);
            }
            catch (Exception ex) { Log.Error("Self-test", ex); failures = 1; }
            Quit(failures == 0 ? 0 : 1);
        }

        private void OnUiException(object sender, DispatcherUnhandledExceptionEventArgs e)
        {
            Log.Error("UI exception", e.Exception);
            if (SelfTest.Active) { SelfTest.RecordException(e.Exception); e.Handled = true; return; }
            Msg.Show("Something went wrong:\n\n" + e.Exception.Message + "\n\nDetails were written to " + Paths.LogFile,
                "WaveClips", MessageBoxButton.OK, MessageBoxImage.Warning);
            e.Handled = true;
        }

        public static async void Quit(int exitCode = 0)
        {
            (Current.MainWindow as MainWindow)?.PrepareExit();
            try { await AppHost.ShutdownAsync(); }
            catch (Exception ex) { Log.Error("Shutdown", ex); }
            Current.Shutdown(exitCode);
        }
    }
}
