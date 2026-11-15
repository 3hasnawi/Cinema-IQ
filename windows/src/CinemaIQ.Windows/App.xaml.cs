using System.Windows;
namespace CinemaIQ.Windows;
public partial class App : Application
{
    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        DispatcherUnhandledException += (_, args) => { args.Handled = true; if (e.Args.Contains("--smoke-test")) { Smoke.Failure(e.Args, args.Exception); Shutdown(1); } else MessageBox.Show("تعذّر إكمال العملية. أعد المحاولة؛ بياناتك محفوظة محليًا.", "Cinema iQ"); };
        try
        {
            var services = new Services(e.Args.Contains("--smoke-test")); var window = new MainWindow(services); MainWindow = window; window.Show();
            if (services.Smoke) await Smoke.Run(window, services, e.Args); else { await window.Open("home"); if (services.StartupNotice.Length > 0) window.ShowError(services.StartupNotice); }
        }
        catch (Exception ex) { if (e.Args.Contains("--smoke-test")) Smoke.Failure(e.Args, ex); else MessageBox.Show("تعذّر بدء Cinema iQ. أعد تثبيت النسخة الكاملة أو تحقق من صلاحية مجلد البيانات.", "Cinema iQ"); Shutdown(1); }
    }
}
