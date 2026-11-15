using CinemaIQ.Core;
using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Controls;
using System.Windows.Input;
namespace CinemaIQ.Windows;

// Isolated opt-in smoke mode. Fixtures are never a selectable production source.
public sealed class SmokeProvider(string mediaPath = "", bool offline = false) : IContentProvider
{
    public static readonly MediaItem[] Items = Enumerable.Range(1, 12).Select(i => new MediaItem { Id = (9000000 + i).ToString(), Title = new[] { "رحلة إلى الغد", "عالم الطبيعة", "ليلة في المدينة", "حكاية البحر" }[(i - 1) % 4], EnglishTitle = "Cinema iQ Test " + i, Description = "بيانات اختبار ثابتة للتحقق من عرض البطاقات والتنقل والمشغّل. لا تمثل محتوى متاحًا لدى المصادر.", Year = "2026", Rating = "8.2", Duration = "120 دقيقة", Quality = "1080p" }).ToArray();
    public Task<IReadOnlyList<Shelf>> Home(CancellationToken token) => offline ? throw new CinemaException("تعذّر الاتصال بالمصدر.") : Task.FromResult<IReadOnlyList<Shelf>>([new("featured", "الأفلام المميزة", Items[..4]), new("recent", "أضيف حديثًا", Items), new("picks", "اختيارات المشاهدة", Items.Reverse().ToArray())]);
    public Task<IReadOnlyList<MediaItem>> Catalog(bool series, string query, int page, CancellationToken token) => Task.FromResult<IReadOnlyList<MediaItem>>(page > 1 ? [] : Items.Where(m => query.Length == 0 || m.Title.Contains(query) || m.EnglishTitle.Contains(query)).Select(m => m with { IsSeries = series }).ToArray());
    public Task<MediaItem> Detail(MediaItem item, CancellationToken token) => Task.FromResult(item);
    public Task<IReadOnlyList<Season>> Seasons(MediaItem item, CancellationToken token) => Task.FromResult<IReadOnlyList<Season>>([new(1, Enumerable.Range(1, 4).Select(i => item with { Id = "910000" + i, SeriesId = item.GroupId, IsSeries = true, Episode = i, Season = 1, EpisodeTitle = "حكاية الحلقة " + i }).ToArray())]);
    public Task<IReadOnlyList<StreamOption>> Streams(MediaItem item, CancellationToken token) => Task.FromResult<IReadOnlyList<StreamOption>>(mediaPath.Length > 0 ? [new(new Uri(mediaPath).AbsoluteUri, "فيديو الاختبار المحلي")] : []);
}
public static class Smoke
{
    private static string Argument(string[] args, string key, string fallback = "") { var index = Array.IndexOf(args, key); return index >= 0 && index + 1 < args.Length ? args[index + 1] : fallback; }
    public static void Failure(string[] args, Exception ex)
    {
        var output = Argument(args, "--smoke-output", Path.Combine(Path.GetTempPath(), "CinemaIQ-Windows-Smoke")); Directory.CreateDirectory(output);
        File.WriteAllText(Path.Combine(output, "result.json"), JsonSerializer.Serialize(new { success = false, error = ex.GetType().Name, diagnostic = ex is InvalidOperationException ? ex.Message : "Smoke validation failed", location = ex.StackTrace }, Json.Options));
    }
    public static async Task Run(MainWindow window, Services services, string[] args)
    {
        var output = Argument(args, "--smoke-output", Path.Combine(Path.GetTempPath(), "CinemaIQ-Windows-Smoke")); Directory.CreateDirectory(output);
        try
        {
            var media = Argument(args, "--smoke-media"); foreach (var kind in Enum.GetValues<SourceKind>()) services.Providers[kind] = new SmokeProvider(media);
            await window.OpenHome(); await Task.Delay(500); Capture(window, Path.Combine(output, "windows-home.png"));
            window.VerifyNavigation();
            await window.Open("movies"); await Task.Delay(200); Capture(window, Path.Combine(output, "windows-movies.png"));
            var detail = new DetailWindow(window, SmokeProvider.Items[0]); detail.Show(); await Task.Delay(500); Capture(detail, Path.Combine(output, "windows-detail.png")); detail.Close();
            await window.Open("settings"); await Task.Delay(150); Capture(window, Path.Combine(output, "windows-settings.png"));
            await window.Open("profile"); await Task.Delay(150); Capture(window, Path.Combine(output, "windows-profiles.png"));
            services.Secrets.Write("smoke-credential", "fixture-private-password");
            if (services.Secrets.Read<string>("smoke-credential") != "fixture-private-password" || Directory.GetFiles(Path.Combine(services.Folder, "Secrets")).Any(f => System.Text.Encoding.UTF8.GetString(File.ReadAllBytes(f)).Contains("fixture-private-password"))) throw new InvalidOperationException("Windows encrypted secret roundtrip failed");
            services.Secrets.Delete("smoke-credential");
            services.Providers[services.Library.State.Settings.Source] = new SmokeProvider(offline: true);
            await window.Open("home"); if (!window.IsVisible) throw new InvalidOperationException("Offline launch closed the application"); Capture(window, Path.Combine(output, "windows-offline.png"));
            await window.Open("settings"); foreach (var kind in Enum.GetValues<SourceKind>()) services.Providers[kind] = new SmokeProvider(media);
            var native = false; var audio = 0; var subtitles = 0;
            if (media.Length > 0)
            {
                var player = new PlayerWindow(window, services, SmokeProvider.Items[0], []); player.Show();
                var deadline = DateTime.UtcNow.AddSeconds(25);
                while (DateTime.UtcNow < deadline && (!player.Player.IsPlaying || player.Player.Time < 1200)) await Task.Delay(200);
                if (!player.Player.IsPlaying || player.Player.Time < 1200 || player.Player.VideoTrackCount < 1) throw new InvalidOperationException("Native fixture decoder did not start");
                audio = player.Player.AudioTrackDescription.Count(t => t.Id >= 0); subtitles = player.Player.SpuDescription.Count(t => t.Id >= 0);
                if (audio < 2 || subtitles < 1) throw new InvalidOperationException("Fixture tracks missing");
                var track = player.Player.AudioTrackDescription.Last(t => t.Id >= 0); if (!player.Player.SetAudioTrack(track.Id)) throw new InvalidOperationException("Audio selection failed");
                if (!player.Player.SetSpu(player.Player.SpuDescription.First(t => t.Id >= 0).Id)) throw new InvalidOperationException("Subtitle selection failed");
                player.Player.SetPause(true); await Task.Delay(400); if (player.Player.IsPlaying) throw new InvalidOperationException("Pause failed");
                var snapshot = Path.Combine(output, "windows-decoded-video.png"); if (!player.Player.TakeSnapshot(0, snapshot, 640, 360)) throw new InvalidOperationException("Native decoded frame unavailable");
                await Task.Delay(700); if (!File.Exists(snapshot) || new FileInfo(snapshot).Length < 500) throw new InvalidOperationException("Decoded snapshot empty");
                player.Player.Play(); await Task.Delay(250); player.Close(); var stopDeadline = DateTime.UtcNow.AddSeconds(15); while (player.IsVisible && DateTime.UtcNow < stopDeadline) await Task.Delay(100); if (player.IsVisible) throw new InvalidOperationException("Decoder shutdown did not finish"); native = true;
            }
            File.WriteAllText(Path.Combine(output, "result.json"), JsonSerializer.Serialize(new { success = true, nativePlayback = native, audioTracks = audio, subtitleTracks = subtitles, keyboardFocus = true, sidebarReturn = true, offlineRecovery = true, encryptedSecrets = true, framework = System.Runtime.InteropServices.RuntimeInformation.FrameworkDescription, os = Environment.OSVersion.VersionString, screenshots = "Actual WPF RenderTargetBitmap captures; decoded-video is a native LibVLC snapshot, not a composite", dataDirectoryIsolated = true }, Json.Options));
            window.Close(); Application.Current.Shutdown(0);
        }
        catch (Exception ex)
        {
            Failure(args, ex);
            Application.Current.Shutdown(1);
        }
    }
    private static void Capture(Window window, string path)
    {
        window.UpdateLayout(); var view = (FrameworkElement)window.Content; var bitmap = new RenderTargetBitmap(Math.Max(1, (int)view.ActualWidth), Math.Max(1, (int)view.ActualHeight), 96, 96, PixelFormats.Pbgra32); bitmap.Render(view); var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap)); using var file = File.Create(path); encoder.Save(file);
    }
}

public sealed partial class MainWindow
{
    public void VerifyNavigation()
    {
        Activate(); UpdateLayout();
        if (heroButton == null || rails.Count < 2 || !heroButton.Focus()) throw new InvalidOperationException("Initial hero focus missing");
        void Press(Key key)
        {
            var target = Keyboard.FocusedElement as UIElement ?? throw new InvalidOperationException("Keyboard target missing");
            target.RaiseEvent(new KeyEventArgs(Keyboard.PrimaryDevice, PresentationSource.FromVisual(this), Environment.TickCount, key) { RoutedEvent = Keyboard.PreviewKeyDownEvent });
        }
        void Expect(Button target, string message) { if (!target.IsKeyboardFocusWithin) throw new InvalidOperationException(message); }
        Press(Key.Down); Expect(rails[0][0], "Down did not select first card");
        Press(Key.Left); Expect(rails[0][1], "Horizontal card navigation failed");
        Press(Key.Down); Expect(rails[1][0], "Next shelf did not start at first card");
        Press(Key.Right); Expect(menu[1], "Sidebar did not receive focus");
        Press(Key.Left); Expect(rails[1][0], "Sidebar return lost the original card");
        Press(Key.Up); Expect(rails[0][0], "Previous shelf did not start at first card");
        Press(Key.Up); Expect(heroButton, "Return to featured background failed");
    }
}
