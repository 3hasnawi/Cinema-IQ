using AngleSharp.Dom;
using AngleSharp.Html.Parser;
namespace CinemaIQ.Core;

public sealed class Vodu(INetwork network) : IContentProvider
{
    public const string Root = "https://movie.vodu.me/";
    private async Task<IDocument> Page(CancellationToken token, params (string, string)[] args) => new HtmlParser().ParseDocument(System.Text.Encoding.UTF8.GetString(await network.Read(NetworkExtensions.Url(Root, "index.php", args), "movie.vodu.me", token)));
    private static string Attr(IElement? e, string key) => e?.GetAttribute(key)?.Trim() ?? "";
    public static string Url(string value) => value.Length > 0 && Uri.TryCreate(new Uri(Root), value, out var u) && u.Scheme == "https" ? u.AbsoluteUri : "";
    public static string Parent(string id) => System.Text.RegularExpressions.Regex.IsMatch(id, @"^vodu:[1-9]\d*(?::[1-9]\d*)?$") ? id.Split(':')[1] : throw new CinemaException("معرّف فودو غير صالح.");
    private Task<IDocument> Title(MediaItem item, CancellationToken token) => Page(token, ("do", "view"), ("type", "post"), ("id", Parent(item.Id)));
    public static IReadOnlyList<MediaItem> ParseCatalog(IDocument doc, bool series) => doc.QuerySelectorAll(".mytitle a[href]").Select(link =>
    {
        var match = System.Text.RegularExpressions.Regex.Match(Attr(link, "href"), @"[?&]id=([1-9]\d*)");
        if (!match.Success) return null;
        var poster = Url(Attr(link.Closest(".myitem")?.QuerySelector("img[src]"), "src"));
        return new MediaItem { Id = "vodu:" + match.Groups[1].Value, Title = link.TextContent.Trim(), EnglishTitle = link.TextContent.Trim(), Poster = poster, Backdrop = poster, IsSeries = series };
    }).OfType<MediaItem>().DistinctBy(m => m.Id).ToArray();
    public async Task<IReadOnlyList<MediaItem>> Catalog(bool series, string query, int page, CancellationToken token)
    { ArgumentOutOfRangeException.ThrowIfLessThan(page, 1); return ParseCatalog(await Page(token, ("do", "list"), ("type", series ? "1" : "0"), ("page", page.ToString()), ("title", query)), series); }
    public async Task<IReadOnlyList<Shelf>> Home(CancellationToken token) => [new("movies", "أحدث الأفلام", await Catalog(false, "", 1, token)), new("series", "أحدث المسلسلات", await Catalog(true, "", 1, token))];
    private static IElement? Play(IDocument doc, string id) => id.Split(':') is { Length: 3 } p ? doc.QuerySelectorAll(".episodeitem a.play").FirstOrDefault(a => Attr(a, "data-id") == p[2]) : doc.QuerySelectorAll(".postcontent a.play").FirstOrDefault(a => a.Closest(".episodeitem") == null);
    public static List<StreamOption> ParseStreams(IElement? e) => new[] { ("data-url", "720p"), ("data-url1080", "1080p"), ("data-url4k", "2160p"), ("data-url360", "360p") }.Select(p => new StreamOption(Url(Attr(e, p.Item1)), p.Item2)).Where(s => s.Url.Length > 0).DistinctBy(s => s.Url).ToList();
    private static List<Subtitle> Subs(IElement? e) => Url(Attr(e, "data-webvtt")) is { Length: > 0 } url ? [new(url, "العربية", "ar")] : [];
    public static MediaItem ParseDetail(IDocument doc, MediaItem item)
    {
        var content = doc.QuerySelector(".postcontent") ?? throw new CinemaException("تعذّر قراءة تفاصيل فودو.");
        var anchor = Play(doc, item.Id); if (item.Episode > 0) return anchor == null ? throw new CinemaException("الحلقة غير متاحة.") : item with { Subtitles = Subs(anchor) };
        var fields = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var h in content.QuerySelectorAll("h3")) if (h.QuerySelector("span") is { } label) fields[label.TextContent.Trim().TrimEnd(':')] = h.TextContent.Replace(label.TextContent, "").Trim();
        string Field(string key) => fields.GetValueOrDefault(key, "");
        var poster = Url(Attr(content.QuerySelector("img[src]"), "src"));
        var name = content.QuerySelector("h1")?.TextContent.Trim() ?? item.Title;
        var synopsis = content.QuerySelectorAll("h3").FirstOrDefault(h => h.TextContent.Trim().TrimEnd(':').Equals("Synopsis", StringComparison.OrdinalIgnoreCase))?.NextElementSibling?.TextContent.Trim() ?? "";
        return item with { Title = name, EnglishTitle = name, OriginalTitle = Field("Original Title"), Description = synopsis, Poster = poster.Length > 0 ? poster : item.Poster, Backdrop = poster.Length > 0 ? poster : item.Backdrop, Year = Field("Year"), Duration = Field("Runtime"), Rating = Field("IMdB Rating"), ImdbRating = Field("IMdB Rating"), Quality = Field("Quality"), Edition = Field("Version"), Trailer = Url(Attr(content.QuerySelector("iframe[src]"), "src")), IsSeries = item.IsSeries || content.QuerySelector(".episodes") != null, Subtitles = Subs(anchor) };
    }
    public async Task<MediaItem> Detail(MediaItem item, CancellationToken token) => ParseDetail(await Title(item, token), item);
    public async Task<IReadOnlyList<StreamOption>> Streams(MediaItem item, CancellationToken token) => ParseStreams(Play(await Title(item, token), item.Id));
    public async Task<IReadOnlyList<Season>> Seasons(MediaItem item, CancellationToken token)
    {
        var doc = await Title(item, token); var result = new List<MediaItem>(); var parent = Parent(item.Id);
        var labels = doc.QuerySelectorAll(".customtabs .nav-tabs a[href]").ToDictionary(a => Attr(a, "href").TrimStart('#'), a => a.TextContent.Trim());
        foreach (var a in doc.QuerySelectorAll(".episodeitem a.play"))
        {
            var id = Attr(a, "data-id"); if (!System.Text.RegularExpressions.Regex.IsMatch(id, @"^[1-9]\d*$")) continue;
            var title = Attr(a, "data-title"); var match = System.Text.RegularExpressions.Regex.Match(title, @"S(\d+)E(\d+)", System.Text.RegularExpressions.RegexOptions.IgnoreCase);
            var season = match.Success ? int.Parse(match.Groups[1].Value) : 1;
            var tab = a.Ancestors<IElement>().FirstOrDefault(e => (e.Id ?? "").StartsWith("tab-"));
            var label = labels.GetValueOrDefault(tab?.Id ?? "", ""); var sm = System.Text.RegularExpressions.Regex.Match(label, @"Season\s*(\d+)", System.Text.RegularExpressions.RegexOptions.IgnoreCase);
            if (sm.Success) season = int.Parse(sm.Groups[1].Value);
            result.Add(item with { Id = $"vodu:{parent}:{id}", SeriesId = "vodu:" + parent, IsSeries = true, Season = season, Episode = match.Success ? int.Parse(match.Groups[2].Value) : result.Count(m => m.Season == season) + 1, EpisodeTitle = title, Subtitles = Subs(a) });
        }
        return result.DistinctBy(m => m.Id).GroupBy(m => m.Season).OrderBy(g => g.Key).Select(g => new Season(g.Key, g.OrderBy(m => m.Episode).ToArray())).ToArray();
    }
}
