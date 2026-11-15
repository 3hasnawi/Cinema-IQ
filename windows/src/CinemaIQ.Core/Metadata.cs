using System.Text.Json;
using System.Text.RegularExpressions;
namespace CinemaIQ.Core;

public sealed class Metadata(INetwork network, AppConfiguration config)
{
    private static string Normalize(string s) => Regex.Replace(s.ToLowerInvariant(), @"[^\p{L}\p{N}]", "");
    private Task<JsonElement> Tmdb(string path, CancellationToken token, params (string, string)[] args) => network.Json(NetworkExtensions.Url("https://api.themoviedb.org/3/", path, [("api_key", config.TmdbApiKey), ("language", "ar"), .. args]), "api.themoviedb.org", token);
    public async Task<MediaItem> Enrich(MediaItem item, CancellationToken token)
    {
        if (string.IsNullOrWhiteSpace(config.TmdbApiKey)) return item;
        var kind = item.IsSeries ? "tv" : "movie";
        var query = item.EnglishTitle.Length > 0 ? item.EnglishTitle : item.Title;
        var results = (await Tmdb("search/" + kind, token, ("query", query))).Get("results").Rows().ToArray();
        var match = results.FirstOrDefault(r => new[] { r.Text("title", "name"), r.Text("original_title", "original_name") }.Any(t => new[] { item.Title, item.EnglishTitle, item.OriginalTitle }.Where(s => s.Length > 0).Any(s => Normalize(s) == Normalize(t))) && (item.Year.Length < 4 || r.Text("release_date", "first_air_date").StartsWith(item.Year[..4])));
        var id = match.Number("id"); if (id <= 0) return item;
        var details = await Tmdb($"{kind}/{id}", token, ("append_to_response", "credits,external_ids,videos"));
        var imdb = details.Text("imdb_id"); if (imdb.Length == 0) imdb = details.Get("external_ids").Text("imdb_id");
        var cast = details.Get("credits").Get("cast").Rows().Take(14).Select(c => new CastMember(c.Text("name"), c.Text("character"), Image(c.Text("profile_path"), "w185"))).ToList();
        var director = details.Get("credits").Get("crew").Rows().FirstOrDefault(c => c.Text("job") == "Director").Text("name");
        var trailer = details.Get("videos").Get("results").Rows().FirstOrDefault(v => v.Text("site") == "YouTube" && v.Text("type") == "Trailer").Text("key");
        var description = details.Text("overview"); var title = details.Text("original_title", "original_name");
        var enriched = item with { Poster = Image(details.Text("poster_path"), "w500") is { Length: > 0 } poster ? poster : item.Poster, Backdrop = Image(details.Text("backdrop_path"), "w1280") is { Length: > 0 } backdrop ? backdrop : item.Backdrop, Description = description.Length > 0 ? description : item.Description, OriginalTitle = title.Length > 0 ? title : item.OriginalTitle, ImdbId = imdb, Cast = cast, Director = director, Trailer = trailer.Length > 0 ? "https://www.youtube.com/watch?v=" + trailer : item.Trailer };
        if (imdb.Length > 0 && config.OmdbApiKey.Length > 0)
        {
            var rating = await network.Json(NetworkExtensions.Url("https://www.omdbapi.com/", "", ("apikey", config.OmdbApiKey), ("i", imdb)), "www.omdbapi.com", token);
            if (rating.Text("imdbID") == imdb && rating.Text("Response") == "True" && rating.Text("imdbRating") != "N/A") enriched = enriched with { ImdbRating = rating.Text("imdbRating") };
        }
        return enriched;
    }
    public static string Image(string path, string size) => path.StartsWith('/') ? $"https://image.tmdb.org/t/p/{size}{path}" : "";
    public async Task<IReadOnlyList<MediaItem>> Episodes(MediaItem series, IReadOnlyList<MediaItem> episodes, CancellationToken token)
    {
        if (config.TmdbApiKey.Length == 0 || episodes.Count == 0) return episodes;
        var query = series.EnglishTitle.Length > 0 ? series.EnglishTitle : series.Title;
        var found = (await Tmdb("search/tv", token, ("query", query))).Get("results").Rows().FirstOrDefault(r => new[] { r.Text("name"), r.Text("original_name") }.Any(t => Normalize(t) == Normalize(query)) && (series.Year.Length < 4 || r.Text("first_air_date").StartsWith(series.Year[..4])));
        var id = found.Number("id"); if (id <= 0) return episodes;
        var result = new List<MediaItem>();
        foreach (var group in episodes.GroupBy(e => e.Season))
        {
            var season = await Tmdb($"tv/{id}/season/{group.Key}", token);
            var info = season.Get("episodes").Rows().GroupBy(e => e.Number("episode_number")).ToDictionary(g => g.Key, g => g.First());
            foreach (var episode in group) result.Add(info.TryGetValue(episode.Episode, out var e) ? episode with { EpisodeTitle = e.Text("name"), Description = e.Text("overview") is { Length: > 0 } text ? text : episode.Description, Backdrop = Image(e.Text("still_path"), "w500") is { Length: > 0 } art ? art : episode.Backdrop, Rating = e.Rating("vote_average") } : episode);
        }
        return result;
    }
    public static Uri Trailer(MediaItem item)
    {
        if (Regex.IsMatch(item.Trailer, @"^[A-Za-z0-9_-]{11}$")) return new("https://www.youtube.com/watch?v=" + item.Trailer);
        if (Uri.TryCreate(item.Trailer, UriKind.Absolute, out var u) && u.Scheme == "https" && (u.Host is "youtu.be" or "www.youtube.com" or "youtube.com" or "www.youtube-nocookie.com")) return u;
        return new("https://www.youtube.com/results?search_query=" + Uri.EscapeDataString((item.EnglishTitle.Length > 0 ? item.EnglishTitle : item.Title) + " " + item.Year + " official trailer"));
    }
    public async Task<Uri> TrailerUrl(MediaItem item, CancellationToken token)
    {
        if (item.Trailer.Length > 0 || config.YoutubeApiKey.Length == 0) return Trailer(item);
        var title = item.EnglishTitle.Length > 0 ? item.EnglishTitle : item.Title;
        try
        {
            var response = await network.Json(NetworkExtensions.Url("https://www.googleapis.com/youtube/v3/", "search", ("key", config.YoutubeApiKey), ("part", "snippet"), ("type", "video"), ("maxResults", "5"), ("q", title + " " + item.Year + " official trailer")), "www.googleapis.com", token);
            var found = response.Get("items").Rows().FirstOrDefault(o => Normalize(o.Get("snippet").Text("title")).Contains(Normalize(title)) && o.Get("snippet").Text("title").Contains("trailer", StringComparison.OrdinalIgnoreCase) && Regex.IsMatch(o.Get("id").Text("videoId"), @"^[A-Za-z0-9_-]{11}$"));
            if (found.Get("id").Text("videoId") is { Length: 11 } id) return new("https://www.youtube.com/watch?v=" + id);
        }
        catch (CinemaException) { }
        return Trailer(item);
    }
    public async Task<(long IntroStart, long IntroEnd, long Credits)> Markers(MediaItem item, CancellationToken token)
    {
        if (!Regex.IsMatch(item.ImdbId, @"^tt\d+$")) return default;
        var args = item.IsSeries ? new (string, string)[] { ("imdb_id", item.ImdbId), ("season", item.Season.ToString()), ("episode", item.Episode.ToString()) } : [("imdb_id", item.ImdbId), ("is_movie", "true")];
        var o = await network.Json(NetworkExtensions.Url("https://api.introdb.app/", "segments", args), "api.introdb.app", token);
        if (o.Text("imdb_id") != item.ImdbId || item.IsSeries && (o.Number("season") != item.Season || o.Number("episode") != item.Episode)) return default;
        long Time(JsonElement segment, string name) => segment.Get(name + "_ms").ValueKind != JsonValueKind.Undefined ? segment.Get(name + "_ms").TryGetInt64Safe() : segment.Get(name + "_sec").ValueKind == JsonValueKind.Number ? (long)(segment.Get(name + "_sec").GetDouble() * 1000) : 0;
        return (Time(o.Get("intro"), "start"), Time(o.Get("intro"), "end"), Time(o.Get("outro"), "start"));
    }
}
