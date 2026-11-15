using System.Text;
using System.Text.Json;
namespace CinemaIQ.Core;

public sealed class Cinemana(INetwork network) : IContentProvider
{
    private const string Root = "https://cinemana.shabakaty.cc/api/android/";
    private Task<JsonElement> Get(string path, CancellationToken token) => network.Json(new Uri(Root + path), "shabakaty.cc", token);
    public static string Image(string raw) => string.IsNullOrWhiteSpace(raw) || raw.StartsWith("http://") ? "" : raw.StartsWith("https://") ? raw : raw.StartsWith("//") ? "https:" + raw : "https://cnth2.shabakaty.cc/" + raw.TrimStart('/');
    public static List<Subtitle> Subtitles(JsonElement data)
    {
        string? Code(string raw)
        {
            var text = raw.Trim().ToLowerInvariant();
            var known = text switch { "ar" or "ara" or "arabic" or "العربية" or "عربي" or "عربية" => "ar", "en" or "eng" or "english" or "الإنجليزية" or "الانجليزية" or "انكليزي" or "الانكليزية" => "en", "fr" or "fra" or "fre" or "french" => "fr", "tr" or "tur" or "turkish" => "tr", "es" or "spa" or "spanish" => "es", "de" or "deu" or "ger" or "german" => "de", "ku" or "kur" or "kurdish" => "ku", _ => "" };
            if (known.Length > 0) return known;
            if (System.Text.RegularExpressions.Regex.IsMatch(text, @"^[a-z]{2,3}[-_][a-z]{2,4}$")) return text.Replace('_', '-');
            return null;
        }
        var subtitles = new List<Subtitle>();
        foreach (var o in data.Rows())
        {
            var url = Image(o.Text("file", "url")); if (url.Length == 0) continue;
            var nested = o.Get("language").ValueKind == JsonValueKind.Object ? o.Get("language") : o.Get("lang");
            var label = o.Text("languageName", "language_name", "language_title", "langName", "lang_name", "ar_name", "en_name", "ar_title", "en_title", "name", "title", "label");
            var values = new[] { o.Text("languageCode", "language_code", "langCode", "locale"), nested.Text("code", "iso", "name", "ar_name", "en_name"), o.Text("language", "lang"), label };
            var language = values.Select(Code).FirstOrDefault(s => s != null);
            if (language == null && Uri.TryCreate(url, UriKind.Absolute, out var uri)) language = System.Text.RegularExpressions.Regex.Split(Uri.UnescapeDataString(uri.AbsolutePath.Split('/')[^1]), @"[._\-\s]+").Select(Code).FirstOrDefault(s => s != null);
            language ??= "und";
            var title = language.Split('-')[0] switch { "ar" => "العربية", "en" => "الإنجليزية", "fr" => "الفرنسية", "tr" => "التركية", "es" => "الإسبانية", "de" => "الألمانية", "ku" => "الكردية", _ => label.Length > 0 && !label.All(char.IsDigit) ? label : "ترجمة " + (subtitles.Count + 1) };
            subtitles.Add(new(url, title, language));
        }
        return subtitles.DistinctBy(s => s.Url).GroupBy(s => s.Title).SelectMany(group => group.Count() == 1 ? group : group.Select((s, i) => s with { Title = s.Title + " · " + (i + 1) })).ToList();
    }
    public static MediaItem? Parse(JsonElement o, bool series = false)
    {
        var id = o.Text("nb", "videoId", "videoID", "id"); if (string.IsNullOrEmpty(id)) return null;
        var subs = Subtitles(o.Get("translations"));
        return new() { Id = id, Title = o.Text("ar_title", "en_title", "title", "name"), EnglishTitle = o.Text("en_title", "title_en"), OriginalTitle = o.Text("original_title", "original_name"), Poster = Image(o.Text("imgMediumThumbObjUrl", "imgThumbObjUrl", "imgObjUrl", "poster")), Backdrop = Image(o.Text("imgObjUrl", "imgLargeThumbObjUrl", "imgMediumThumbObjUrl")), Description = o.Text("ar_content", "ar_description", "description", "content", "en_content"), Year = o.Text("year"), Duration = o.Text("duration"), Rating = o.Rating(), ImdbId = o.Text("imdbId", "imdb_id"), ImdbRating = o.Rating("imdbRate", "imdb_rate", "imdbRating"), IsSeries = series || o.Text("kind", "videoKind") == "2", Season = Math.Max(1, o.Number("season")), Episode = o.Number("episodeNummer", "episodeNumber", "episode"), SeriesId = o.Text("seriesId"), EpisodeTitle = o.Text("episodeTitle"), Quality = o.Text("videoQuality", "quality", "resolution"), Edition = o.Text("videoVersion", "edition", "releaseType"), Trailer = o.Text("trailer", "trailerUrl", "youtube"), Subtitles = subs };
    }
    public static List<MediaItem> Items(JsonElement o, bool series = false) => o.Rows().Select(o => Parse(o, series)).OfType<MediaItem>().DistinctBy(m => m.Id).ToList();
    public static string CatalogPath(bool series, string query, int page)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(page, 1);
        var q = string.IsNullOrWhiteSpace(query) ? "-" : Uri.EscapeDataString(Convert.ToBase64String(Encoding.UTF8.GetBytes(query)));
        return $"video/V/2/videoKind/{(series ? 2 : 1)}/categoryNb/0/langNb/0/sortParam/desc/itemsPerPage/10/video_title_search/{q}/pageNumber/{page - 1}/enableTranslation/0/level/1";
    }
    public async Task<IReadOnlyList<MediaItem>> Catalog(bool series, string query, int page, CancellationToken token) => Items(await Get(CatalogPath(series, query, page), token), series);
    public async Task<IReadOnlyList<Shelf>> Home(CancellationToken token)
    {
        var shelves = new List<Shelf>();
        foreach (var group in (await Get("videoGroups/lang/ar/level/1", token)).Rows().Take(8))
        {
            var id = group.Text("groupsID", "groupID", "nb", "id"); if (id.Length == 0) continue;
            var items = Items(group.Get("content"));
            if (items.Count == 0) items = Items(await Get($"videoListPagination/groupID/{Uri.EscapeDataString(id)}/level/1/itemsPerPage/12/page/1", token));
            if (items.Count > 0) shelves.Add(new(id, group.Text("ar_title", "ar_name", "title", "name"), items));
        }
        if (shelves.Count == 0) shelves.Add(new("latest", "أضيف حديثًا", await Catalog(false, "", 1, token)));
        return shelves;
    }
    public async Task<MediaItem> Detail(MediaItem item, CancellationToken token)
    {
        var parsed = (await Get("allVideoInfo/id/" + Uri.EscapeDataString(item.Id), token)).Rows().Select(o => Parse(o, item.IsSeries)).OfType<MediaItem>().FirstOrDefault() ?? throw new CinemaException("لم يُرجع المصدر تفاصيل صالحة.");
        return parsed with { Poster = parsed.Poster.Length > 0 ? parsed.Poster : item.Poster, Backdrop = parsed.Backdrop.Length > 0 ? parsed.Backdrop : item.Backdrop, SeriesId = item.SeriesId, Season = item.Season, Episode = item.Episode, EpisodeTitle = item.EpisodeTitle };
    }
    public async Task<IReadOnlyList<Season>> Seasons(MediaItem item, CancellationToken token)
    {
        var data = await Get("videoSeason/id/" + Uri.EscapeDataString(item.GroupId), token); var result = new List<Season>();
        foreach (var wrapper in new[] { "data", "result", "seasons" }) if (data.Get(wrapper).ValueKind is JsonValueKind.Object or JsonValueKind.Array) { data = data.Get(wrapper); break; }
        if (data.ValueKind == JsonValueKind.Object) foreach (var p in data.EnumerateObject())
            if (int.TryParse(p.Name, out var number)) result.Add(new(number, Items(p.Value).Select(m => m with { IsSeries = true, SeriesId = item.GroupId, Season = number, Poster = m.Poster.Length > 0 ? m.Poster : item.Poster }).OrderBy(m => m.Episode).ToArray()));
        if (result.Count == 0) result.AddRange(Items(data).GroupBy(m => m.Season).Select(g => new Season(g.Key, g.Select(m => m with { SeriesId = item.GroupId, IsSeries = true }).OrderBy(m => m.Episode).ToArray())));
        return result.OrderBy(s => s.Number).ToArray();
    }
    public async Task<IReadOnlyList<StreamOption>> Streams(MediaItem item, CancellationToken token) => (await Get("transcoddedFiles/id/" + Uri.EscapeDataString(item.Id), token)).Rows().Select(o => new StreamOption(o.Text("videoUrl", "url"), o.Text("resolution", "quality"))).Where(s => Uri.TryCreate(s.Url, UriKind.Absolute, out var u) && u.Scheme == "https").DistinctBy(s => s.Url).ToArray();
}
