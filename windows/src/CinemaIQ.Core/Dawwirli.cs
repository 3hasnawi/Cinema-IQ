using System.Text.Json;
using System.Text.RegularExpressions;
namespace CinemaIQ.Core;

public sealed class Dawwirli(INetwork network) : IContentProvider
{
    public const string Root = "https://premium.dawwirli.watch/";
    private Task<JsonElement> Get(string path, CancellationToken token, params (string, string)[] args) => network.Json(NetworkExtensions.Url(Root, path, args), "premium.dawwirli.watch", token);
    public static string Image(string raw) => raw.Length > 0 && Uri.TryCreate(new Uri(Root), raw, out var u) && u.Scheme == "https" ? u.AbsoluteUri : "";
    public static string Clean(string title) => Regex.Replace(Regex.Replace(Regex.Replace(title, @"^(?:AR|EN|TR|HI|KR|FR)\s*[|:-]\s*", "", RegexOptions.IgnoreCase), @"\s*\[MULTI-SUB\]\s*$", "", RegexOptions.IgnoreCase), @"\s+-\s+\d{4}\s*$", "").Trim();
    public static string[] Key(string id)
    {
        var p = id.Split(':'); if (!Regex.IsMatch(id, @"^daw:(?:series:[\w~-]+|movie:[\w~-]+:[A-Za-z0-9]{1,8}|episode:[\w~-]+:[\w~-]+:[A-Za-z0-9]{1,8})$", RegexOptions.CultureInvariant)) throw new CinemaException("معرّف دورلي غير صالح."); return p;
    }
    public static MediaItem? Parse(JsonElement o, bool series = false)
    {
        var raw = o.Text("id", "stream_id", "series_id"); if (raw.Length == 0) return null;
        var kind = o.Text("kind", "_kind"); if (kind is "series" or "movie") series = kind == "series";
        var ext = o.Text("ext", "container_extension"); if (ext.Length == 0) ext = "mp4";
        var id = series ? "daw:series:" + raw : $"daw:movie:{raw}:{ext}"; try { Key(id); } catch (CinemaException) { return null; }
        return new() { Id = id, Title = Clean(o.Text("name", "title")), EnglishTitle = Clean(o.Text("name", "title")), Poster = Image(o.Text("poster", "icon", "cover")), Backdrop = Image(o.Text("backdrop")), Description = o.Text("plot", "description"), Year = o.Text("year"), Rating = o.Rating(), Quality = o.Text("quality", "resolution"), IsSeries = series };
    }
    public static IReadOnlyList<MediaItem> Items(JsonElement o, bool series = false) => o.Rows().Select(o => Parse(o, series)).OfType<MediaItem>().Where(m => m.Title.Length > 0).DistinctBy(m => m.Id).ToArray();
    public async Task<IReadOnlyList<Shelf>> Home(CancellationToken token)
    {
        var data = await Get("api/ar/home", token); var result = new List<Shelf>(); var hero = Items(data.Get("hero")); if (hero.Count > 0) result.Add(new("featured", "الأفلام المميزة", hero));
        foreach (var row in data.Get("rows").Rows()) { var items = Items(row.Get("items"), row.Text("kind") == "series"); if (items.Count > 0) result.Add(new("row-" + result.Count, row.Text("title"), items)); }
        return result;
    }
    public async Task<IReadOnlyList<MediaItem>> Catalog(bool series, string query, int page, CancellationToken token)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(page, 1);
        if (query.Length > 0) return page == 1 ? Items((await Get("api/search", token, ("q", query), ("kind", series ? "series" : "movie"))).Get("items"), series) : [];
        var id = "newest"; var kind = "movies";
        if (series)
        {
            var row = (await Get("api/ar/home", token)).Get("rows").Rows().FirstOrDefault(r => r.Text("kind") == "series" && r.Text("all").Length > 0);
            id = row.Text("all") is { Length: > 0 } s ? s : "sr-foreign"; kind = row.Text("allKind") is { Length: > 0 } k ? k : "series";
        }
        var data = await Get("api/cat", token, ("id", id), ("kind", kind), ("page", page.ToString()));
        return data.Number("pages") is > 0 and var pages && page > pages ? [] : Items(data.Get("items"), series).Where(m => m.IsSeries == series).ToArray();
    }
    private async Task<MediaItem> Details(MediaItem item, CancellationToken token)
    {
        var p = Key(item.Id); if (p[1] == "episode") return (await Seasons(item with { Id = "daw:series:" + p[2], SeriesId = "" }, token)).SelectMany(s => s.Episodes).FirstOrDefault(m => m.Id == item.Id) ?? throw new CinemaException("لم يُعثر على الحلقة.");
        var data = await Get(p[1] == "series" ? "api/series_info" : "api/info", token, (p[1] == "series" ? "series_id" : "id", p[2])); var info = data.Get("info");
        if (info.ValueKind != JsonValueKind.Object) throw new CinemaException("لم يُرجع دورلي تفاصيل صالحة.");
        var poster = Image(info.Text("cover_big", "movie_image", "cover")); var backdrop = info.Get("backdrop_path").ValueKind == JsonValueKind.Array ? Image(info.Get("backdrop_path").EnumerateArray().FirstOrDefault().ToString()) : "";
        var name = Clean(info.Text("name", "o_name")); var year = info.Text("releasedate", "releaseDate");
        return item with { Title = name.Length > 0 ? name : item.Title, Poster = poster.Length > 0 ? poster : item.Poster, Backdrop = backdrop.Length > 0 ? backdrop : item.Backdrop, Description = info.Text("plot", "description"), Duration = info.Text("duration", "episode_run_time"), Year = year.Length >= 4 ? year[..4] : item.Year, Rating = info.Rating(), Trailer = info.Text("youtube_trailer"), Quality = info.Get("video").Number("height") is > 0 and var h ? h + "p" : item.Quality };
    }
    public static List<Subtitle> Subtitles(JsonElement data) => data.Get("tracks").Rows().Select(o => new Subtitle(Image(o.Text("url")), o.Text("label", "title", "lang", "language"), o.Text("lang", "language"))).Where(s => s.Url.Length > 0).DistinctBy(s => s.Url).ToList();
    public async Task<MediaItem> Detail(MediaItem item, CancellationToken token)
    {
        var detail = await Details(item, token); var key = Key(item.Id); if (key[1] == "series") return detail;
        try { return detail with { Subtitles = Subtitles(await Get("api/subtitles", token, ("id", key[1] == "episode" ? key[3] : key[2]), ("kind", key[1] == "episode" ? "series" : "movie"), ("title", detail.Title), ("s", detail.Season.ToString()), ("e", detail.Episode.ToString()))) }; }
        catch (CinemaException) { return detail; }
    }
    public async Task<IReadOnlyList<Season>> Seasons(MediaItem item, CancellationToken token)
    {
        var p = Key(item.GroupId); var data = await Get("api/series_info", token, ("series_id", p[2])); var result = new List<Season>(); var groups = data.Get("episodes");
        if (groups.ValueKind != JsonValueKind.Object) return [];
        foreach (var group in groups.EnumerateObject())
        {
            if (!int.TryParse(group.Name, out var number)) continue; var episodes = new List<MediaItem>();
            foreach (var o in group.Value.Rows())
            {
                var raw = o.Text("id"); if (raw.Length == 0) continue; var ext = o.Text("container_extension", "ext"); if (ext.Length == 0) ext = "mp4";
                var id = $"daw:episode:{p[2]}:{raw}:{ext}"; try { Key(id); } catch (CinemaException) { continue; }
                var info = o.Get("info"); var image = Image(info.Text("movie_image"));
                episodes.Add(item with { Id = id, SeriesId = "daw:series:" + p[2], IsSeries = true, Season = number, Episode = o.Number("episode_num") is > 0 and var n ? n : episodes.Count + 1, EpisodeTitle = Clean(o.Text("title", "name")), Description = info.Text("plot"), Rating = info.Rating(), Duration = info.Text("duration"), Backdrop = image.Length > 0 ? image : item.Backdrop });
            }
            result.Add(new(number, episodes.OrderBy(e => e.Episode).ToArray()));
        }
        return result.OrderBy(s => s.Number).ToArray();
    }
    public Task<IReadOnlyList<StreamOption>> Streams(MediaItem item, CancellationToken token)
    {
        token.ThrowIfCancellationRequested(); var p = Key(item.Id); if (p[1] == "series") throw new CinemaException("اختر حلقة أولًا.");
        var uri = NetworkExtensions.Url(Root, "vod", ("kind", p[1] == "episode" ? "series" : "movie"), ("id", p[1] == "episode" ? p[3] : p[2]), ("ext", p[^1]));
        return Task.FromResult<IReadOnlyList<StreamOption>>([new(uri.AbsoluteUri, "المصدر الأصلي")]);
    }
}
