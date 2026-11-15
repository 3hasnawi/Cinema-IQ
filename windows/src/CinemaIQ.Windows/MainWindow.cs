using CinemaIQ.Core;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
namespace CinemaIQ.Windows;

public sealed partial class MainWindow : Window
{
    public Services Services { get; }
    private LibraryStore Store => Services.Library;
    private readonly ContentControl page = new();
    private readonly StackPanel sidebar = new() { Width = 84 };
    private readonly TextBlock status = Ui.Text("", 14, Ui.Muted);
    private readonly List<List<Button>> rails = [];
    private readonly List<Button> menu = [];
    private Button? returnFocus;
    private Button? heroButton;
    private Image? heroImage;
    private TextBlock? heroTitle, heroInfo, heroDescription;
    private ScrollViewer? scroll;
    private IReadOnlyList<MediaItem> featured = [];
    private int heroIndex;
    private DateTime lastInput = DateTime.UtcNow;
    private readonly DispatcherTimer carousel = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly DispatcherTimer syncTimer = new() { Interval = TimeSpan.FromSeconds(45) };
    private CancellationTokenSource request = new();
    private string currentPage = "home";
    private bool closed;
    private bool fullscreen;
    private WindowState previousWindowState;
    public MainWindow(Services services)
    {
        Services = services; Title = "Cinema iQ"; Width = 1440; Height = 900; MinWidth = 1060; MinHeight = 660; WindowStartupLocation = WindowStartupLocation.CenterScreen;
        var root = new Grid(); root.RowDefinitions.Add(new() { Height = new GridLength(46) }); root.RowDefinitions.Add(new()); root.RowDefinitions.Add(new() { Height = GridLength.Auto });
        var brand = new Image { Source = new BitmapImage(new Uri("pack://application:,,,/Assets/brand.png")), Width = 30, Height = 30, Margin = new Thickness(10, 0, 10, 0) };
        var top = Ui.Row(Ui.Text("Cinema iQ", 20), brand); top.Margin = new Thickness(10, 0, 24, 0); top.HorizontalAlignment = HorizontalAlignment.Right; root.Children.Add(top);
        var body = new Grid(); body.ColumnDefinitions.Add(new()); body.ColumnDefinitions.Add(new() { Width = GridLength.Auto }); Grid.SetRow(body, 1); root.Children.Add(body); body.Children.Add(page);
        var rail = Ui.Card(sidebar, 2); rail.Margin = new Thickness(0, 4, 12, 12); Grid.SetColumn(rail, 1); body.Children.Add(rail);
        var items = new[] { ("●", "الملف الشخصي", "profile"), ("⌂", "الرئيسية", "home"), ("⌕", "البحث", "search"), ("▣", "الأفلام", "movies"), ("▤", "المسلسلات", "series"), ("♡", "قائمتي", "library"), ("▥", "IPTV", "iptv"), ("⚙", "الإعدادات", "settings") };
        foreach (var (icon, label, route) in items)
        {
            var button = Ui.AsyncButton(icon, () => Open(route), ShowError); button.ToolTip = label; button.MinHeight = 56; button.Tag = label; menu.Add(button); sidebar.Children.Add(button);
        }
        void Expand(bool show) { sidebar.Width = show ? 210 : 84; foreach (var b in menu) b.Content = Ui.Text(show ? (string)b.Tag : items[menu.IndexOf(b)].Item1, show ? 17 : 26); }
        sidebar.MouseEnter += (_, _) => Expand(true); sidebar.MouseLeave += (_, _) => { if (!sidebar.IsKeyboardFocusWithin) Expand(false); };
        sidebar.GotKeyboardFocus += (_, _) => Expand(true); sidebar.LostKeyboardFocus += (_, _) => Dispatcher.BeginInvoke(() => { if (!sidebar.IsKeyboardFocusWithin) Expand(false); });
        status.Margin = new Thickness(20, 0, 24, 6); Grid.SetRow(status, 2); root.Children.Add(status); Content = root;
        PreviewKeyDown += Keys; PreviewMouseDown += (_, _) => lastInput = DateTime.UtcNow;
        carousel.Tick += async (_, _) => { if (heroButton?.IsKeyboardFocusWithin == true && (scroll?.VerticalOffset ?? 1) < 1 && featured.Count > 1 && DateTime.UtcNow - lastInput > TimeSpan.FromSeconds(8)) { heroIndex = (heroIndex + 1) % featured.Count; await UpdateHero(); lastInput = DateTime.UtcNow; } }; carousel.Start();
        syncTimer.Tick += async (_, _) => { if (Services.Cloud.Session is { Verified: true }) try { await Services.Sync(request.Token); } catch (OperationCanceledException) { } catch { status.Text = "التغييرات محفوظة محليًا؛ تعذرت المزامنة حاليًا."; } }; if (!services.Smoke) syncTimer.Start();
        Closed += (_, _) => { closed = true; request.Cancel(); request.Dispose(); carousel.Stop(); syncTimer.Stop(); Services.Dispose(); };
    }
    private CancellationToken Begin(string route)
    { request.Cancel(); request.Dispose(); request = new(); currentPage = route; rails.Clear(); heroButton = null; featured = []; status.Text = ""; page.Content = Ui.Text("جارٍ التحميل…", 26); return request.Token; }
    public async Task Open(string route)
    {
        try
        {
            if (route == "home") await OpenHome(); else if (route is "movies" or "series") await OpenCatalog(route == "series", "", 1); else if (route == "search") OpenSearch(); else if (route == "library") OpenLibrary(); else if (route == "iptv") await OpenIptv(); else if (route == "profile") OpenProfiles(); else OpenSettings();
        }
        catch (OperationCanceledException) { }
        catch (Exception e) { ShowError(Ui.Message(e)); }
    }
    public void ShowError(string message)
    {
        if (closed) return; status.Text = message;
        if (rails.Count == 0 && heroButton == null) page.Content = Ui.Card(Ui.Stack(Ui.Text("تعذّر تحميل المحتوى", 30), Ui.Text(message, 18, Ui.Muted), Ui.Row(Ui.AsyncButton("إعادة المحاولة", () => Open(currentPage), ShowError, true), Ui.Button("المصادر والإعدادات", OpenSettings))));
    }
    public async Task OpenHome()
    {
        var token = Begin("home");
        var shelves = await Services.Providers[Store.State.Settings.Source].Home(token); token.ThrowIfCancellationRequested();
        if (shelves.Count == 0 || shelves.All(s => s.Items.Count == 0)) { ShowError("لم يوفّر المصدر محتوى. تحقق من الشبكة الداعمة أو اختر مصدرًا آخر."); return; }
        if (!Services.Smoke) foreach (var checkpoint in Store.Current.Followed.Values.Take(8).ToArray())
        {
            if (!Store.Current.Progress.GetValueOrDefault(checkpoint.Id)?.Completed ?? true) continue;
            try { var episodes = (await Services.Providers[checkpoint.Source].Seasons(checkpoint with { Id = checkpoint.GroupId, SeriesId = "" }, token)).SelectMany(s => s.Episodes).ToArray(); if (LibraryStore.Next(checkpoint, episodes) is { } next) { Store.Current.Recent[checkpoint.GroupId] = next; Store.Current.Outbox["recent/" + checkpoint.GroupId] = FilmWire.Encode(next); Store.Save(); } } catch (CinemaException) { }
        }
        featured = shelves.FirstOrDefault(s => s.Id.Contains("featured", StringComparison.OrdinalIgnoreCase) || s.Title.Contains("مميز"))?.Items.Where(m => !m.IsSeries).Take(8).ToArray() ?? shelves.SelectMany(s => s.Items).Where(m => !m.IsSeries).DistinctBy(m => m.Id).Take(8).ToArray();
        if (featured.Count == 0) featured = shelves.SelectMany(s => s.Items).Take(8).ToArray(); heroIndex = 0;
        var content = new StackPanel(); var hero = new Grid { Height = Math.Max(520, ActualHeight - 150), ClipToBounds = true };
        heroImage = new Image { Stretch = Stretch.UniformToFill, Opacity = 0.8 }; hero.Children.Add(heroImage);
        hero.Children.Add(new System.Windows.Shapes.Rectangle { Fill = new LinearGradientBrush(new GradientStopCollection { new(Color.FromArgb(0, 24, 29, 37), 0), new(Color.FromArgb(240, 24, 29, 37), 1) }, new Point(0, 0), new Point(0, 1)) });
        heroTitle = Ui.Text("", 50); heroTitle.MaxWidth = 760; heroTitle.FontWeight = FontWeights.Bold;
        heroInfo = Ui.Text("", 17, Ui.Accent); heroDescription = Ui.Text("", 19, Ui.Muted); heroDescription.MaxHeight = 104; heroDescription.TextTrimming = TextTrimming.CharacterEllipsis;
        var words = Ui.Stack(heroTitle, heroInfo, heroDescription, Ui.Text("← → استعرض الأعمال  ·  Enter افتح العمل  ·  ↓ القوائم", 13, Ui.Muted)); words.Width = 750; words.Margin = new Thickness(45, 35, 45, 50); words.HorizontalAlignment = HorizontalAlignment.Right; words.VerticalAlignment = VerticalAlignment.Bottom; hero.Children.Add(words);
        heroButton = new Button { Content = hero, Padding = new Thickness(0), Margin = new Thickness(0), Background = Brushes.Transparent, BorderThickness = new Thickness(0), HorizontalContentAlignment = HorizontalAlignment.Stretch };
        heroButton.Click += (_, _) => ShowDetail(featured[heroIndex]); content.Children.Add(heroButton);
        if (Store.Current.Recent.Count > 0) AddShelf(content, "متابعة المشاهدة", Store.Current.Recent.Values.Reverse().ToArray(), true);
        foreach (var shelf in shelves) if (shelf.Items.Count > 0) AddShelf(content, shelf.Title, shelf.Items);
        scroll = new ScrollViewer { Content = content, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled }; page.Content = scroll; await UpdateHero(); heroButton.Focus();
    }
    private async Task UpdateHero()
    {
        if (featured.Count == 0 || heroTitle == null || heroInfo == null || heroDescription == null || heroImage == null) return;
        var item = featured[heroIndex]; heroTitle.Text = item.DisplayTitle(Store.State.Settings.EnglishTitles); heroInfo.Text = item.Information; heroDescription.Text = item.Description;
        var image = await Services.Images.Load(item.Artwork, 1440); if (featured.Count > 0 && featured[heroIndex].Id == item.Id && heroImage != null) { heroImage.Source = image; if (!Store.State.Settings.ReducedMotion) heroImage.BeginAnimation(OpacityProperty, new DoubleAnimation(0.45, 0.8, TimeSpan.FromMilliseconds(300))); }
    }
    private void AddShelf(StackPanel parent, string title, IReadOnlyList<MediaItem> items, bool recent = false)
    {
        var heading = Ui.Text(title, 27); heading.Margin = new Thickness(30, 24, 30, 12); parent.Children.Add(heading);
        var row = new StackPanel { Orientation = Orientation.Horizontal, FlowDirection = FlowDirection.RightToLeft, Margin = new Thickness(20, 2, 20, 16) }; var buttons = new List<Button>(); rails.Add(buttons); var index = rails.Count - 1;
        foreach (var item in items)
        {
            var button = MediaCard(item, recent); button.Tag = (index, buttons.Count); buttons.Add(button); row.Children.Add(button);
            button.Click += async (_, _) => { if (recent) await Play(item); else ShowDetail(item); };
        }
        parent.Children.Add(new ScrollViewer { Content = row, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Disabled, FlowDirection = FlowDirection.RightToLeft });
    }
    public Button MediaCard(MediaItem item, bool landscape = false)
    {
        var image = new Image { Height = landscape ? 130 : 242, Width = landscape ? 232 : 164, Stretch = Stretch.UniformToFill };
        var title = Ui.Text(item.Episode > 0 ? item.EpisodeLabel : item.DisplayTitle(Store.State.Settings.EnglishTitles), 15); title.MaxHeight = 54; title.TextTrimming = TextTrimming.CharacterEllipsis;
        var panel = Ui.Stack(image, title); var button = new Button { Content = panel, Width = landscape ? 254 : 186, Padding = new Thickness(8), VerticalContentAlignment = VerticalAlignment.Top };
        button.GotKeyboardFocus += (_, _) => { button.BringIntoView(); var scale = new ScaleTransform(1, 1); button.RenderTransformOrigin = new Point(.5, .5); button.RenderTransform = scale; if (!Store.State.Settings.ReducedMotion) { scale.BeginAnimation(ScaleTransform.ScaleXProperty, new DoubleAnimation(1, 1.04, TimeSpan.FromMilliseconds(130))); scale.BeginAnimation(ScaleTransform.ScaleYProperty, new DoubleAnimation(1, 1.04, TimeSpan.FromMilliseconds(130))); } };
        button.LostKeyboardFocus += (_, _) => button.RenderTransform = Transform.Identity;
        button.Loaded += async (_, _) => { var art = await Services.Images.Load(landscape ? item.Artwork : item.Poster, 500); if (button.IsLoaded) image.Source = art; };
        var actions = new ContextMenu(); foreach (var (name, action) in new (string, Action)[] { ("تعليم كمشاهد", () => { Store.MarkWatched(item, true); }), ("إزالة علامة المشاهدة", () => Store.MarkWatched(item, false)), ("إزالة من متابعة المشاهدة", () => Store.RemoveRecent(item)), ("إضافة أو إزالة من قائمتي", () => Store.ToggleFavorite(item)) }) { var entry = new MenuItem { Header = name, FontSize = 17 }; entry.Click += (_, _) => action(); actions.Items.Add(entry); } button.ContextMenu = actions;
        return button;
    }
    public void ShowDetail(MediaItem item) { var detail = new DetailWindow(this, item); detail.ShowDialog(); }
    public async Task Play(MediaItem item)
    {
        try
        {
            var episodes = item.IsSeries ? (await Services.Providers[item.Source].Seasons(item with { Id = item.GroupId, SeriesId = "" }, request.Token)).SelectMany(s => s.Episodes).ToArray() : [];
            if (item.IsSeries && item.Episode == 0) item = Store.Current.Recent.GetValueOrDefault(item.GroupId) ?? episodes.FirstOrDefault(e => Store.Current.Progress.GetValueOrDefault(e.Id)?.Completed != true) ?? episodes.FirstOrDefault() ?? throw new CinemaException("لم تتوفر حلقات قابلة للتشغيل.");
            new PlayerWindow(this, Services, item, episodes).ShowDialog();
        }
        catch (OperationCanceledException) { }
        catch (Exception e) { status.Text = Ui.Message(e); }
    }
    private async Task OpenCatalog(bool series, string query, int number)
    {
        var token = Begin(query.Length > 0 ? "search" : series ? "series" : "movies"); var source = Store.State.Settings.Source;
        var items = await Services.Providers[source].Catalog(series, query, number, token); token.ThrowIfCancellationRequested();
        var panel = new StackPanel { Margin = new Thickness(18) }; panel.Children.Add(Ui.Text(query.Length > 0 ? "نتائج البحث · " + query : series ? "المسلسلات" : "الأفلام", 34));
        if (items.Count == 0) panel.Children.Add(Ui.Text("لا توجد نتائج في هذه الصفحة.", 24, Ui.Muted));
        foreach (var chunk in items.Chunk(5)) AddShelf(panel, "", chunk);
        panel.Children.Add(Ui.Row(Ui.AsyncButton("الصفحة التالية", () => OpenCatalog(series, query, number + 1), ShowError), Ui.Text("الصفحة " + number), Ui.AsyncButton("الصفحة السابقة", () => OpenCatalog(series, query, Math.Max(1, number - 1)), ShowError)));
        scroll = new() { Content = panel }; page.Content = scroll; rails.FirstOrDefault()?.FirstOrDefault()?.Focus();
    }
    private void OpenSearch()
    {
        Begin("search"); var text = Ui.Input("اسم العمل"); var series = new CheckBox { Content = "مسلسلات" }; var row = Ui.Row(text, Ui.AsyncButton("ابحث", () => OpenCatalog(series.IsChecked == true, text.Text.Trim(), 1), ShowError, true));
        text.KeyDown += async (_, e) => { if (e.Key == Key.Enter) { e.Handled = true; try { await OpenCatalog(series.IsChecked == true, text.Text.Trim(), 1); } catch (Exception ex) { ShowError(Ui.Message(ex)); } } };
        page.Content = Ui.Card(Ui.Stack(Ui.Text("البحث", 34), row, series, Ui.Text("اختر المصدر من الإعدادات. بعض المصادر تعمل على شبكاتها الداعمة فقط.", 16, Ui.Muted))); text.Focus();
    }
    private void OpenLibrary()
    {
        Begin("library"); var panel = new StackPanel(); panel.Children.Add(Ui.Text("قائمتي", 34));
        if (Store.Current.Favorites.Count > 0) AddShelf(panel, "أعمال أضفتها لتشاهدها لاحقًا", Store.Current.Favorites.Values.Reverse().ToArray());
        if (Store.Current.Recent.Count > 0) AddShelf(panel, "متابعة المشاهدة", Store.Current.Recent.Values.Reverse().ToArray(), true);
        var history = Store.Current.Progress.Values.Where(p => p.Completed).OrderByDescending(p => p.UpdatedAt).Select(p => p.Item).ToArray(); if (history.Length > 0) AddShelf(panel, "سجل المشاهدات", history);
        if (rails.Count == 0) panel.Children.Add(Ui.Text("قائمتك فارغة. أضف أعمالك من صفحة التفاصيل.", 23, Ui.Muted));
        scroll = new() { Content = panel }; page.Content = scroll; rails.FirstOrDefault()?.FirstOrDefault()?.Focus();
    }
    private async void Keys(object sender, KeyEventArgs e)
    {
        lastInput = DateTime.UtcNow;
        if (e.Key == Key.F11) { fullscreen = !fullscreen; if (fullscreen) { previousWindowState = WindowState; WindowState = WindowState.Normal; WindowStyle = WindowStyle.None; WindowState = WindowState.Maximized; } else { WindowState = WindowState.Normal; WindowStyle = WindowStyle.SingleBorderWindow; WindowState = previousWindowState; } e.Handled = true; return; }
        if (e.Key == Key.Escape) { e.Handled = true; if (currentPage != "home") await Open("home"); else { returnFocus = Keyboard.FocusedElement as Button ?? heroButton; menu[1].Focus(); } return; }
        if (e.OriginalSource is TextBox or PasswordBox || Keyboard.FocusedElement is TextBox or ComboBox) return;
        if (sidebar.IsKeyboardFocusWithin) { if (e.Key == Key.Left && returnFocus?.IsLoaded == true) { returnFocus.Focus(); e.Handled = true; } return; }
        if (e.Key == Key.Right && (rails.SelectMany(r => r).FirstOrDefault(b => b.IsKeyboardFocusWithin)?.Tag is ValueTuple<int, int> { Item2: 0 }))
        { returnFocus = Keyboard.FocusedElement as Button ?? heroButton; menu[1].Focus(); e.Handled = true; return; }
        if (heroButton?.IsKeyboardFocusWithin == true)
        {
            if (e.Key is Key.Left or Key.Right && featured.Count > 0) { heroIndex = (heroIndex + (e.Key == Key.Left ? 1 : -1) + featured.Count) % featured.Count; await UpdateHero(); e.Handled = true; }
            if (e.Key == Key.Down && rails.Count > 0) { rails[0].FirstOrDefault()?.Focus(); e.Handled = true; }
        }
        else if (rails.SelectMany(r => r).FirstOrDefault(b => b.IsKeyboardFocusWithin)?.Tag is ValueTuple<int, int> tag)
        {
            Button? next = null;
            if (e.Key == Key.Down && tag.Item1 + 1 < rails.Count) next = rails[tag.Item1 + 1].FirstOrDefault();
            else if (e.Key == Key.Up) next = tag.Item1 == 0 ? heroButton : rails[tag.Item1 - 1].FirstOrDefault();
            else if (e.Key is Key.Left or Key.Right) { var index = tag.Item2 + (e.Key == Key.Left ? 1 : -1); if (index >= 0 && index < rails[tag.Item1].Count) next = rails[tag.Item1][index]; }
            if (next != null) { next.Focus(); next.BringIntoView(); e.Handled = true; }
        }
    }
}
