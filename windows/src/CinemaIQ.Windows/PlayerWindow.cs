using CinemaIQ.Core;
using LibVLCSharp.Shared;
using LibVLCSharp.WPF;
using MediaPlayer = LibVLCSharp.Shared.MediaPlayer;
using System.IO;
using System.IO.Compression;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
namespace CinemaIQ.Windows;

public sealed partial class PlayerWindow : Window
{
    private readonly Services services;
    private readonly LibVLC lib;
    public MediaPlayer Player { get; }
    private readonly VideoView video;
    private readonly Grid overlay = new();
    private readonly TextBlock title = Ui.Text("", 32), details = Ui.Text("", 18), message = Ui.Text("جارٍ التشغيل…", 22);
    private readonly Slider timeline = new() { Minimum = 0, Maximum = 1, Margin = new Thickness(20, 8, 20, 8) };
    private readonly TextBlock clock = Ui.Text("", 15, Ui.Muted);
    private readonly StackPanel controls = new();
    private readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromMilliseconds(250) };
    private readonly CancellationTokenSource lifetime = new();
    private readonly SemaphoreSlim tuning = new(1);
    private readonly List<string> subtitleFiles = [];
    private readonly MediaItem[] episodes;
    private readonly IReadOnlyList<Channel> channels = [];
    private Channel? channel;
    private MediaItem item;
    private string? group;
    private IReadOnlyList<StreamOption> streams = [];
    private long seekOnStart;
    private bool pauseOnStart, closing, retired, ended, dragging, settingTimeline;
    private string? pendingUrl;
    private DateTime lastInteraction = DateTime.UtcNow, savedAt;
    private Border? guide;
    private bool autoSubtitle, loadingMedia;
    private int generation;
    private long introStart, introEnd, credits;
    private readonly Button next;
    private readonly Button skip;
    private bool nextAnnounced, introAnnounced;
    public PlayerWindow(Window owner, Services services, MediaItem media, MediaItem[] episodes) : this(owner, services, media, episodes, null, [], null) { }
    public PlayerWindow(Window owner, Services services, Channel channel, IReadOnlyList<Channel> all, string? group) : this(owner, services, new MediaItem { Id = "iptv:" + channel.Id, Title = channel.Name }, [], channel, all, group) { }
    private PlayerWindow(Window owner, Services services, MediaItem media, MediaItem[] episodes, Channel? channel, IReadOnlyList<Channel> channels, string? group)
    {
        Ui.ApplyWindow(this); this.services = services; this.item = media; this.episodes = episodes; this.channel = channel; this.channels = channels; this.group = group;
        Owner = owner; Title = media.Title; Width = owner.ActualWidth; Height = owner.ActualHeight; WindowState = WindowState.Maximized; WindowStyle = WindowStyle.None; ResizeMode = ResizeMode.NoResize; Background = Brushes.Black;
        LibVLCSharp.Shared.Core.Initialize(Path.Combine(AppContext.BaseDirectory, "libvlc", "win-x64"));
        var settings = services.Library.State.Settings;
        lib = new LibVLC("--quiet", "--no-video-title-show", "--no-sub-autodetect-file", "--freetype-fontsize=" + Math.Clamp(settings.SubtitleSize, 14, 48), "--freetype-background-opacity=" + Math.Clamp(settings.SubtitleBackground, 0, 255), "--freetype-background-color=0", "--sub-language=" + settings.SubtitleLanguage);
        Player = new MediaPlayer(lib) { EnableHardwareDecoding = settings.HardwareDecoding && !services.Smoke, Volume = 90 };
        TextOptions.SetTextRenderingMode(overlay, TextRenderingMode.Grayscale); video = new VideoView { MediaPlayer = Player, Content = overlay }; Content = video;
        title.HorizontalAlignment = HorizontalAlignment.Right; title.VerticalAlignment = VerticalAlignment.Top; title.Margin = new Thickness(48); overlay.Children.Add(title);
        details.HorizontalAlignment = HorizontalAlignment.Right; details.VerticalAlignment = VerticalAlignment.Bottom; details.Width = 760; details.MaxHeight = 160; details.TextTrimming = TextTrimming.CharacterEllipsis; details.Margin = new Thickness(40, 0, 48, 200); overlay.Children.Add(details);
        message.HorizontalAlignment = HorizontalAlignment.Center; message.VerticalAlignment = VerticalAlignment.Center; message.MaxWidth = 800; overlay.Children.Add(message);
        var play = Ui.Button("⏯", Toggle); play.ToolTip = "تشغيل أو إيقاف مؤقت";
        var volume = new Slider { Minimum = 0, Maximum = 100, Value = 90, Width = 130, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12) }; volume.ValueChanged += (_, _) => Player.Volume = (int)volume.Value;
        var buttonRow = Ui.Row(Ui.Button("إغلاق", Close), Ui.Button("القنوات / الحلقات", OpenGuide), Ui.AsyncButton("الجودة", Quality, Error), Ui.Button("الترجمة", Subtitles), Ui.Button("الصوت", Audio), Ui.Button("السرعة", Speed), Ui.Button("−10", () => Seek(-10000)), play, Ui.Button("+10", () => Seek(10000)), volume);
        var buttons = new WrapPanel { FlowDirection = FlowDirection.RightToLeft, HorizontalAlignment = HorizontalAlignment.Stretch }; foreach (var button in buttonRow.Children.Cast<UIElement>().ToArray()) { buttonRow.Children.Remove(button); buttons.Children.Add(button); }
        controls.Children.Add(timeline); controls.Children.Add(clock); controls.Children.Add(buttons); controls.Margin = new Thickness(35, 10, 35, 20); controls.VerticalAlignment = VerticalAlignment.Bottom;
        var panel = Ui.Card(controls, 8); panel.VerticalAlignment = VerticalAlignment.Bottom; panel.Margin = new Thickness(32); overlay.Children.Add(panel);
        skip = Ui.Button("تخطي المقدمة", () => { if (introEnd > Player.Time) Player.Time = introEnd; }); next = Ui.Button("الحلقة التالية  ▶", () => { if (LibraryStore.Next(item, this.episodes) is { } following) _ = PlayItem(following); });
        var prompts = Ui.Row(next, skip); prompts.HorizontalAlignment = HorizontalAlignment.Right; prompts.VerticalAlignment = VerticalAlignment.Bottom; prompts.Margin = new Thickness(30, 0, 48, 172); overlay.Children.Add(prompts); next.Visibility = skip.Visibility = Visibility.Collapsed;
        timeline.PreviewMouseLeftButtonDown += (_, _) => dragging = true; timeline.PreviewMouseLeftButtonUp += (_, _) => { if (dragging && Player.IsSeekable) Player.Time = (long)timeline.Value; dragging = false; };
        timeline.ValueChanged += (_, _) => { if (!settingTimeline && timeline.IsKeyboardFocusWithin && !dragging && Player.IsSeekable) Player.Time = (long)timeline.Value; };
        overlay.Background = Brushes.Transparent; overlay.PreviewMouseLeftButtonDown += (_, _) => lastInteraction = DateTime.UtcNow;
        PreviewKeyDown += Keys; overlay.PreviewKeyDown += Keys;
        Player.Playing += Playing; Player.EndReached += Ended; Player.EncounteredError += Failed;
        timer.Tick += (_, _) => Tick(panel); timer.Start();
        Loaded += async (_, _) => { try { if (channel != null) await TuneChannel(channel); else await PlayItem(item); play.Focus(); } catch (Exception e) { Error(Ui.Message(e)); } };
        Closing += Retire;
        _ = SetThreadExecutionState(0x80000003);
    }
    private void Error(string text) { message.Text = text; message.Visibility = Visibility.Visible; lastInteraction = DateTime.UtcNow; }
    public async Task PlayItem(MediaItem selected, string? forcedUrl = null)
    {
        if (loadingMedia || closing) return;
        Save(); var previous = item; loadingMedia = true;
        try
        {
            var resolved = selected;
            if (forcedUrl == null && !services.Smoke) try { resolved = await services.Providers[selected.Source].Detail(selected, lifetime.Token); } catch (CinemaException) { }
            if (resolved.ImdbId.Length == 0) resolved = resolved with { ImdbId = selected.ImdbId };
            var choices = forcedUrl == null ? await services.Providers[selected.Source].Streams(resolved, lifetime.Token) : new StreamOption[] { new(forcedUrl, "تلقائي") };
            if (choices.Count == 0) throw new CinemaException("لم يوفّر المصدر رابط تشغيل صالحًا لهذا العمل.");
            item = resolved; streams = choices; ended = false; autoSubtitle = false; nextAnnounced = introAnnounced = false; introStart = introEnd = credits = 0;
            title.Text = item.Title; details.Text = item.Episode > 0 ? item.EpisodeLabel + "\n" + item.Information + "\n" + item.Description : item.Information + "\n" + item.Description;
            var quality = services.Library.State.Settings.Quality;
            var chosen = quality == "highest" ? streams.OrderByDescending(x => x.Height).First() : int.TryParse(quality, out var cap) ? streams.Where(x => x.Height <= cap).OrderByDescending(x => x.Height).FirstOrDefault() ?? streams[0] : streams.FirstOrDefault(x => x.Url.Contains(".m3u8", StringComparison.OrdinalIgnoreCase)) ?? streams[0];
            await Start(chosen.Url, services.Library.Position(item), false);
        }
        catch { item = previous; loadingMedia = false; throw; }
        if (!services.Smoke) try
        {
            var id = item.Id; var metadataItem = item;
            if (metadataItem.ImdbId.Length == 0)
            {
                var root = metadataItem.IsSeries ? await services.Providers[metadataItem.Source].Detail(metadataItem with { Id = metadataItem.GroupId, SeriesId = "", Episode = 0 }, lifetime.Token) : metadataItem;
                var info = await services.Metadata.Enrich(root, lifetime.Token); metadataItem = metadataItem with { ImdbId = info.ImdbId };
            }
            var markers = await services.Metadata.Markers(metadataItem, lifetime.Token);
            if (item.Id == id) { item = metadataItem; introStart = markers.IntroStart; introEnd = markers.IntroEnd; credits = markers.Credits; }
        }
        catch (CinemaException) { }
        catch (OperationCanceledException) { }
    }
    private async Task Start(string url, long position, bool paused)
    {
        loadingMedia = true;
        await tuning.WaitAsync(lifetime.Token);
        try
        {
            if (!(services.Smoke && Uri.TryCreate(url, UriKind.Absolute, out var file) && file.IsFile)) Network.Validate(url, channel != null && services.IptvSources.FirstOrDefault(s => s.Id == channel.SourceId)?.AllowHttp == true);
            message.Text = "جارٍ التشغيل…"; message.Visibility = Visibility.Visible;
            Player.Playing -= Playing; Player.EndReached -= Ended; Player.EncounteredError -= Failed; generation++;
            await Task.Run(Player.Stop); lifetime.Token.ThrowIfCancellationRequested();
            Player.Playing += Playing; Player.EndReached += Ended; Player.EncounteredError += Failed;
            using var media = new Media(lib, new Uri(url)); media.AddOption(":network-caching=1500"); media.AddOption(":http-user-agent=Cinema-iQ-Windows/0.1");
            var quality = services.Library.State.Settings.Quality;
            if (quality == "highest") media.AddOption(":adaptive-logic=highest"); else if (int.TryParse(quality, out var max)) media.AddOption(":adaptive-maxheight=" + max);
            seekOnStart = position; pauseOnStart = paused; if (!Player.Play(media)) throw new CinemaException("تعذّر بدء التشغيل. جرّب جودة أخرى أو أعد تحميل الرابط.");
        }
        catch { loadingMedia = false; throw; }
        finally { tuning.Release(); }
    }
    private void Playing(object? sender, EventArgs args)
    {
        var expected = generation;
        Dispatcher.BeginInvoke(async () =>
    {
        if (closing || expected != generation) return; loadingMedia = false; message.Visibility = Visibility.Collapsed;
        if (seekOnStart > 0 && Player.IsSeekable) { Player.Time = seekOnStart; seekOnStart = 0; }
        if (pauseOnStart) { pauseOnStart = false; Player.SetPause(true); }
        if (!autoSubtitle && item.Subtitles.FirstOrDefault(s => s.Language.StartsWith(services.Library.State.Settings.SubtitleLanguage)) is { } subtitle)
        { autoSubtitle = true; try { await ExternalSubtitle(subtitle); } catch (Exception) { } }
    });
    }
    private void Ended(object? sender, EventArgs e) => Dispatcher.BeginInvoke(() => { if (closing || loadingMedia) return; ended = true; Save(true); message.Text = channel == null ? "انتهى العرض" : "انقطع البث. أعد تحميل القناة."; message.Visibility = Visibility.Visible; if (LibraryStore.Next(item, episodes) != null) { next.Visibility = Visibility.Visible; next.Focus(); } });
    private void Failed(object? sender, EventArgs e) => Dispatcher.BeginInvoke(() => { if (!closing) { loadingMedia = false; Error("تعذّر تشغيل هذا الرابط. قد تكون صلاحية الرابط انتهت أو رفض المصدر الاتصال. افتح خيارات الجودة أو أعد تحميل القناة."); } });
    private void Tick(Border panel)
    {
        if (closing) return; var live = channel != null;
        var visible = guide != null || !Player.IsPlaying || DateTime.UtcNow - lastInteraction < TimeSpan.FromSeconds(5);
        panel.Visibility = title.Visibility = visible && guide == null ? Visibility.Visible : Visibility.Collapsed; details.Visibility = !live && !Player.IsPlaying && guide == null ? Visibility.Visible : Visibility.Collapsed;
        details.Margin = new Thickness(40, 0, 48, Math.Max(200, panel.ActualHeight + 65));
        timeline.Visibility = live ? Visibility.Collapsed : Visibility.Visible;
        if (!dragging) { settingTimeline = true; timeline.Maximum = Math.Max(1, Player.Length); timeline.Value = Math.Max(0, Player.Time); settingTimeline = false; }
        clock.Text = live ? (Player.IsPlaying ? "بث مباشر" : "متوقف مؤقتًا") : Format(Player.Time) + " / " + Format(Player.Length);
        var canNext = !live && LibraryStore.Next(item, episodes) != null && (ended || credits > 0 && Player.Time >= credits || Player.Length > 0 && Player.Length - Player.Time <= 90000);
        next.Visibility = canNext ? Visibility.Visible : Visibility.Collapsed;
        if (canNext && !nextAnnounced) { nextAnnounced = true; next.Focus(); lastInteraction = DateTime.UtcNow; }
        var canSkip = !live && introEnd > introStart && Player.Time >= introStart && Player.Time < introEnd;
        skip.Visibility = canSkip ? Visibility.Visible : Visibility.Collapsed;
        if (canSkip && !introAnnounced) { introAnnounced = true; skip.Focus(); lastInteraction = DateTime.UtcNow; }
        if (!live && Player.Time > 0 && DateTime.UtcNow - savedAt > TimeSpan.FromSeconds(5)) { Save(); savedAt = DateTime.UtcNow; }
    }
    private static string Format(long ms) => TimeSpan.FromMilliseconds(Math.Max(0, ms)).ToString(ms >= 3600000 ? @"h\:mm\:ss" : @"mm\:ss");
    private void Save(bool complete = false) { if (channel == null && !loadingMedia && !services.Smoke && (Player.Time >= 0 || complete || ended)) services.Library.Checkpoint(item, Math.Max(0, Player.Time), Math.Max(0, Player.Length), episodes, complete || ended); }
    private async void Toggle()
    {
        lastInteraction = DateTime.UtcNow; if (loadingMedia) return;
        try { if (pendingUrl != null) { var url = pendingUrl; pendingUrl = null; await Start(url, Player.Time, false); } else if (ended) { ended = false; await PlayItem(item); } else Player.SetPause(Player.IsPlaying); } catch (Exception e) { Error(Ui.Message(e)); }
    }
    private void Seek(long delta) { if (Player.IsSeekable && channel == null) Player.Time = Math.Clamp(Player.Time + delta, 0, Math.Max(0, Player.Length)); lastInteraction = DateTime.UtcNow; }
    private async Task TuneChannel(Channel nextChannel)
    {
        channel = nextChannel; title.Text = nextChannel.Name; details.Text = ""; autoSubtitle = true; await Start(nextChannel.Url, 0, false);
    }
    private async Task Surf(int offset)
    {
        var visible = channels.Where(c => group == null || c.Group == group).ToArray(); if (visible.Length == 0 || channel == null) return;
        var current = Array.FindIndex(visible, c => c.Id == channel.Id); await TuneChannel(visible[(current + offset + visible.Length) % visible.Length]);
    }
    private async Task Quality()
    {
        var values = channel == null ? streams : IptvClient.Qualities(channel, channels); var list = new StackPanel(); Window? dialog = null;
        foreach (var option in values)
            list.Children.Add(Ui.AsyncButton(option.Quality, async () => { var pos = Player.Time; if (!Player.IsPlaying && !ended) { pendingUrl = option.Url; message.Text = "ستُطبّق الجودة عند استئناف التشغيل."; message.Visibility = Visibility.Visible; } else await Start(option.Url, channel == null ? pos : 0, false); dialog!.Close(); }, Error));
        list.Children.Add(Ui.AsyncButton("إعادة تحميل الرابط", async () => { dialog!.Close(); if (channel != null) await TuneChannel(channel); else await PlayItem(item); }, Error));
        dialog = Ui.Dialog(this, "الجودة المتاحة من المصدر", list, 610); dialog.ShowDialog(); await Task.CompletedTask;
    }
    private void Audio()
    {
        var panel = new StackPanel(); Window? dialog = null;
        foreach (var track in Player.AudioTrackDescription) panel.Children.Add(Ui.Button(track.Name, () => { Player.SetAudioTrack(track.Id); dialog!.Close(); }));
        if (panel.Children.Count == 0) panel.Children.Add(Ui.Text("لا توجد مسارات صوت إضافية.")); dialog = Ui.Dialog(this, "الصوت", panel); dialog.ShowDialog();
    }
    private void Subtitles()
    {
        var panel = new StackPanel(); Window? dialog = null;
        panel.Children.Add(Ui.Button("إيقاف الترجمة", () => { Player.SetSpu(-1); dialog!.Close(); }));
        if (item.Source != SourceKind.Cinemana || item.Subtitles.Count == 0) foreach (var track in Player.SpuDescription.Where(t => t.Id >= 0)) panel.Children.Add(Ui.Button(track.Name, () => { Player.SetSpu(track.Id); dialog!.Close(); }));
        foreach (var subtitle in item.Subtitles.DistinctBy(s => s.Url)) panel.Children.Add(Ui.AsyncButton(subtitle.Title, async () => { await ExternalSubtitle(subtitle); dialog!.Close(); }, Error));
        dialog = Ui.Dialog(this, "الترجمة", panel); dialog.ShowDialog();
    }
    private async Task ExternalSubtitle(Subtitle subtitle)
    {
        var uri = Network.Validate(subtitle.Url); var bytes = await services.Network.Read(uri, uri.Host, lifetime.Token, maximumBytes: 4 * 1024 * 1024);
        if (bytes.Length > 2 && bytes[0] == 'P' && bytes[1] == 'K')
        { using var zip = new ZipArchive(new MemoryStream(bytes)); var entry = zip.Entries.FirstOrDefault(e => e.Name.EndsWith(".srt", StringComparison.OrdinalIgnoreCase) || e.Name.EndsWith(".vtt", StringComparison.OrdinalIgnoreCase)) ?? throw new CinemaException("ملف الترجمة غير صالح."); if (entry.Length > 4 * 1024 * 1024) throw new CinemaException("ملف الترجمة أكبر من الحد المدعوم."); using var reader = entry.Open(); using var output = new MemoryStream(); await reader.CopyToAsync(output, lifetime.Token); bytes = output.ToArray(); }
        var text = Encoding.UTF8.GetString(bytes); if (!text.Contains("-->")) throw new CinemaException("ملف الترجمة فارغ أو غير مدعوم.");
        var path = Path.Combine(Path.GetTempPath(), "CinemaIQ-Subtitle-" + Guid.NewGuid().ToString("N") + (text.TrimStart('\uFEFF').StartsWith("WEBVTT") ? ".vtt" : ".srt")); await File.WriteAllBytesAsync(path, bytes, lifetime.Token); subtitleFiles.Add(path);
        if (!Player.AddSlave(MediaSlaveType.Subtitle, new Uri(path).AbsoluteUri, true)) throw new CinemaException("تعذّر فتح مسار الترجمة.");
    }
    private void Speed() { Window? dialog = null; var panel = new StackPanel(); foreach (var rate in new[] { .75f, 1f, 1.25f, 1.5f, 2f }) panel.Children.Add(Ui.Button(rate + "×", () => { Player.SetRate(rate); dialog!.Close(); })); dialog = Ui.Dialog(this, "سرعة التشغيل", panel); dialog.ShowDialog(); }
    private void OpenGuide()
    {
        if (guide != null) { overlay.Children.Remove(guide); guide = null; return; }
        var rows = new Grid { Height = Math.Max(360, ActualHeight - 140) }; rows.RowDefinitions.Add(new() { Height = GridLength.Auto }); rows.RowDefinitions.Add(new() { Height = GridLength.Auto }); rows.RowDefinitions.Add(new()); rows.RowDefinitions.Add(new() { Height = GridLength.Auto }); var list = new ListBox { Background = Ui.Panel, Foreground = Brushes.White, FontSize = 21, DisplayMemberPath = channel == null ? "EpisodeLabel" : "Name", FlowDirection = FlowDirection.RightToLeft };
        var search = Ui.Input("ابحث عن قناة"); var categories = new ComboBox { Width = 180, ItemsSource = new[] { "كل القنوات" }.Concat(channels.Select(c => c.Group).Distinct().Order()).ToArray(), SelectedIndex = group == null ? 0 : 1 + channels.Select(c => c.Group).Distinct().Order().ToList().IndexOf(group) };
        void Filter() { list.ItemsSource = channel == null ? episodes : channels.Where(c => (categories.SelectedIndex == 0 || c.Group == categories.SelectedItem?.ToString()) && c.Name.Contains(search.Text.Trim(), StringComparison.OrdinalIgnoreCase)).ToArray(); list.SelectedItem = channel == null ? episodes.FirstOrDefault(e => e.Id == item.Id) : channels.FirstOrDefault(c => c.Id == channel.Id); if (list.SelectedItem == null && list.Items.Count > 0) list.SelectedIndex = 0; list.ScrollIntoView(list.SelectedItem); }
        async Task Choose() { try { if (list.SelectedItem is Channel c) { group = categories.SelectedIndex == 0 ? null : categories.SelectedItem?.ToString(); if (c.Id != channel?.Id) await TuneChannel(c); } else if (list.SelectedItem is MediaItem e) await PlayItem(e); if (guide != null) OpenGuide(); } catch (Exception e) { Error(Ui.Message(e)); } }
        list.KeyDown += async (_, e) => { if (e.Key == Key.Enter) { e.Handled = true; await Choose(); } }; list.MouseDoubleClick += async (_, _) => await Choose();
        search.TextChanged += (_, _) => Filter(); categories.SelectionChanged += (_, _) => Filter();
        void Add(UIElement child, int row) { Grid.SetRow(child, row); rows.Children.Add(child); }
        Add(Ui.Row(Ui.Text(channel == null ? "الحلقات" : "القنوات", 28), Ui.Button("إغلاق", OpenGuide)), 0); if (channel != null) Add(Ui.Row(search, categories), 1); Add(list, 2); Add(Ui.AsyncButton("مشاهدة", Choose, Error, true), 3);
        guide = Ui.Card(rows, 20); guide.Width = Math.Min(channel == null ? 650 : 760, Math.Max(500, ActualWidth * 0.6)); guide.MaxHeight = ActualHeight - 60; guide.HorizontalAlignment = HorizontalAlignment.Right; guide.VerticalAlignment = VerticalAlignment.Center; guide.Margin = new Thickness(30); overlay.Children.Add(guide); Filter(); list.Focus();
    }
    private async void Keys(object sender, KeyEventArgs e)
    {
        if (e.Handled) return; lastInteraction = DateTime.UtcNow;
        if (e.Key == Key.Escape) { if (guide != null) OpenGuide(); else Close(); e.Handled = true; return; }
        if (guide != null || e.OriginalSource is TextBox or Slider) return;
        try
        {
            if (e.Key == Key.Space) Toggle(); else if (e.Key == Key.M) Player.Mute = !Player.Mute;
            else if (e.Key == Key.Enter && channel != null) OpenGuide();
            else if (e.Key is Key.Up or Key.Down && channel != null) { e.Handled = true; await Surf(e.Key == Key.Down ? 1 : -1); }
            else if (e.Key is Key.Left or Key.Right) Seek(e.Key == Key.Right ? 10000 : -10000); else return;
            e.Handled = true;
        }
        catch (OperationCanceledException) { }
        catch (Exception ex) { Error(Ui.Message(ex)); }
    }
    private async void Retire(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        if (retired) return; e.Cancel = true; if (closing) return; closing = true; lifetime.Cancel(); timer.Stop(); Save();
        try { await tuning.WaitAsync(); Player.Playing -= Playing; Player.EndReached -= Ended; Player.EncounteredError -= Failed; await Task.Run(Player.Stop); video.MediaPlayer = null; await Task.Run(() => { Player.Dispose(); lib.Dispose(); }); }
        finally { foreach (var file in subtitleFiles) try { File.Delete(file); } catch (IOException) { } _ = SetThreadExecutionState(0x80000000); retired = true; lifetime.Dispose(); Close(); }
    }
    [DllImport("kernel32.dll")] private static extern uint SetThreadExecutionState(uint flags);
}
