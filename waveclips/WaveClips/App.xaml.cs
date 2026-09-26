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
            AppHost.Initialize();

            var win = new MainWindow();
            MainWindow = win;
            bool minimized = AppHost.Settings.StartMinimized || e.Args.Contains("--minimized");
            if (!minimized) win.Show();

            // Bring the window forward when a second copy is launched.
            var t = new Thread(() =>
            {
                while (_showSignal.WaitOne())
                    Dispatcher.BeginInvoke(new Action(() => ((MainWindow)MainWindow).ShowFromTray()));
            }) { IsBackground = true, Name = "Single instance" };
            t.Start();

            _ = AppHost.StartAsync();
        }

        private void OnUiException(object sender, DispatcherUnhandledExceptionEventArgs e)
        {
            Log.Error("UI exception", e.Exception);
            MessageBox.Show("Something went wrong:\n\n" + e.Exception.Message + "\n\nDetails were written to " + Paths.LogFile,
                "WaveClips", MessageBoxButton.OK, MessageBoxImage.Warning);
            e.Handled = true;
        }

        public static async void Quit()
        {
            (Current.MainWindow as MainWindow)?.PrepareExit();
            try { await AppHost.ShutdownAsync(); }
            catch (Exception ex) { Log.Error("Shutdown", ex); }
            Current.Shutdown();
        }
    }
}
