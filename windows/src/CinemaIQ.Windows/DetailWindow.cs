using CinemaIQ.Core;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
namespace CinemaIQ.Windows;

public sealed class DetailWindow : Window
{
    private readonly MainWindow main;
    private readonly Services services;
    private readonly CancellationTokenSource lifetime = new();
    private MediaItem item;
    private MediaItem[] episodes = [];
    private readonly StackPanel content = new();
    private readonly TextBlock status = Ui.Text("جارٍ جلب التفاصيل…", 17, Ui.Muted);
    private readonly StackPanel episodePanel = new();
    private readonly StackPanel seasonButtons = new() { Orientation = Orientation.Horizontal, FlowDirection = FlowDirection.RightToLeft };
    private readonly StackPanel castPanel = new();
    private readonly Image backdrop = new() { Stretch = Stretch.UniformToFill, Opacity = .7 };
    private readonly TextBlock name = Ui.Text("", 46), description = Ui.Text("", 19), information = Ui.Text("", 18, Ui.Accent);
    private readonly Button play;
    public DetailWindow(MainWindow owner, MediaItem initial)
    {
        main = owner; services = owner.Services; item = initial; Owner = owner; Title = initial.Title; Width = owner.ActualWidth; Height = owner.ActualHeight; MinWidth = 1000; MinHeight = 640; WindowStartupLocation = WindowStartupLocation.CenterOwner; WindowState = owner.WindowState;
        var hero = new Grid { Height = Math.Max(530, owner.ActualHeight - 100), ClipToBounds = true }; hero.Children.Add(backdrop); hero.Children.Add(new System.Windows.Shapes.Rectangle { Fill = new LinearGradientBrush(new GradientStopCollection { new(Color.FromArgb(25, 24, 29, 37), 0), new(Color.FromArgb(248, 24, 29, 37), 1) }, new Point(0, 0), new Point(0, 1)) });
        name.MaxWidth = 830; name.FontWeight = FontWeights.Bold; name.HorizontalAlignment = HorizontalAlignment.Right; name.VerticalAlignment = VerticalAlignment.Top; name.Margin = new Thickness(40, 46, 52, 0); hero.Children.Add(name);
        description.MaxHeight = 115; description.TextTrimming = TextTrimming.CharacterEllipsis;
        play = Ui.AsyncButton("▶  مشاهدة", () => Play(item), Error, true); play.MinWidth = 200; play.MinHeight = 60;
        var trailer = Ui.AsyncButton("▶", async () => Ui.OpenBrowser(await services.Metadata.TrailerUrl(item, lifetime.Token)), Error); trailer.ToolTip = "التريلر على YouTube";
        var favorite = Ui.Button("♡", () => services.Library.ToggleFavorite(item)); favorite.ToolTip = "إضافة أو إزالة من قائمتي";
        var words = Ui.Stack(information, description, Ui.Row(play, trailer, favorite)); words.MaxWidth = 820; words.HorizontalAlignment = HorizontalAlignment.Right; words.VerticalAlignment = VerticalAlignment.Bottom; words.Margin = new Thickness(40, 0, 48, 45); hero.Children.Add(words);
        content.Children.Add(hero); status.Margin = new Thickness(40, 10, 40, 10); content.Children.Add(status); content.Children.Add(seasonButtons); content.Children.Add(episodePanel); content.Children.Add(castPanel);
        var scroller = new ScrollViewer { Content = content }; Content = scroller;
        PreviewKeyDown += (_, e) => { if (e.Key == Key.Escape) { Close(); e.Handled = true; } if (e.Key == Key.Down && play.IsKeyboardFocusWithin && episodePanel.Children.OfType<ScrollViewer>().FirstOrDefault()?.Content is StackPanel row && row.Children.OfType<Button>().FirstOrDefault() is { } first) { first.Focus(); first.BringIntoView(); e.Handled = true; } };
        Closed += (_, _) => { lifetime.Cancel(); lifetime.Dispose(); };
        Loaded += async (_, _) => { play.Focus(); await Load(); };
        Update();
    }
    private void Error(string text) => status.Text = text;
    private void Update() { name.Text = item.DisplayTitle(services.Library.State.Settings.EnglishTitles); information.Text = item.Information; description.Text = item.Description; }
    private async Task Load()
    {
        try
        {
            item = await services.Providers[item.Source].Detail(item, lifetime.Token); Update();
            if (item.IsSeries) { episodes = (await services.Providers[item.Source].Seasons(item, lifetime.Token)).SelectMany(s => s.Episodes).ToArray(); RenderSeasons(); }
            status.Text = "";
            try { if (!services.Smoke) item = await services.Metadata.Enrich(item, lifetime.Token); } catch (CinemaException) { }
            episodes = episodes.Select(e => e with { ImdbId = item.ImdbId }).ToArray(); if (episodes.Length > 0) RenderSeasons();
            Update(); backdrop.Source = await services.Images.Load(item.Artwork, 1440);
            if (!IsLoaded) return;
            if (item.Director.Length > 0) castPanel.Children.Add(Ui.Card(Ui.Text("المخرج: " + item.Director + (item.Year.Length > 0 ? "  ·  سنة الإنتاج: " + item.Year : ""), 22)));
            if (item.Cast.Count > 0)
            {
                castPanel.Children.Add(Ui.Text("طاقم العمل", 28)); var row = new StackPanel { Orientation = Orientation.Horizontal, FlowDirection = FlowDirection.RightToLeft };
                foreach (var member in item.Cast)
                {
                    var photo = new Image { Width = 96, Height = 96, Stretch = Stretch.UniformToFill, Clip = new EllipseGeometry(new Point(48, 48), 48, 48) }; var cell = Ui.Stack(photo, Ui.Text(member.Name, 15), Ui.Text(member.Role, 12, Ui.Muted)); cell.Width = 130; cell.Margin = new Thickness(10); row.Children.Add(cell); photo.Loaded += async (_, _) => photo.Source = await services.Images.Load(member.Image, 200);
                }
                castPanel.Children.Add(new ScrollViewer { Content = row, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Disabled });
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception e) { Error(Ui.Message(e)); }
    }
    private void RenderSeasons()
    {
        seasonButtons.Children.Clear();
        foreach (var number in episodes.Select(e => e.Season).Distinct().Order()) seasonButtons.Children.Add(Ui.Button("الموسم " + number, async () => { try { if (!services.Smoke) { var updated = await services.Metadata.Episodes(item, episodes.Where(e => e.Season == number).ToArray(), lifetime.Token); episodes = episodes.Where(e => e.Season != number).Concat(updated).OrderBy(e => e.Season).ThenBy(e => e.Episode).ToArray(); } } catch (CinemaException) { } catch (OperationCanceledException) { return; } RenderEpisodes(number); }));
        if (episodes.Length > 0) RenderEpisodes(episodes[0].Season);
    }
    private void RenderEpisodes(int season)
    {
        episodePanel.Children.Clear(); var row = new StackPanel { Orientation = Orientation.Horizontal, FlowDirection = FlowDirection.RightToLeft, Margin = new Thickness(26) };
        foreach (var episode in episodes.Where(e => e.Season == season).OrderBy(e => e.Episode))
        {
            var photo = new Image { Width = 260, Height = 146, Stretch = Stretch.UniformToFill }; photo.Loaded += async (_, _) => photo.Source = await services.Images.Load(episode.Artwork, 500);
            var title = Ui.Text(episode.EpisodeLabel, 17); var summary = Ui.Text(episode.Description, 14, Ui.Muted); summary.MaxHeight = 64; summary.TextTrimming = TextTrimming.CharacterEllipsis;
            var card = Ui.AsyncButton("", () => Play(episode), Error); card.Content = Ui.Stack(photo, title, Ui.Text(episode.ImdbRating.Length > 0 ? "IMDb " + episode.ImdbRating : episode.Rating.Length > 0 ? "★ " + episode.Rating : "", 13, Ui.Accent), summary); card.Width = 290; card.GotKeyboardFocus += (_, _) => card.BringIntoView(); row.Children.Add(card);
        }
        episodePanel.Children.Add(new ScrollViewer { Content = row, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto, VerticalScrollBarVisibility = ScrollBarVisibility.Disabled });
    }
    private Task Play(MediaItem selected)
    {
        if (selected.IsSeries && selected.Episode == 0) selected = services.Library.Current.Recent.GetValueOrDefault(selected.GroupId) ?? episodes.FirstOrDefault(e => services.Library.Current.Progress.GetValueOrDefault(e.Id)?.Completed != true) ?? episodes.FirstOrDefault() ?? throw new CinemaException("لا توجد حلقات متاحة بعد.");
        new PlayerWindow(this, services, selected, episodes).ShowDialog(); play.Focus(); return Task.CompletedTask;
    }
}
