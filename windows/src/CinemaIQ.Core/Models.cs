using System.Text.Json;
using System.Text.Json.Serialization;
namespace CinemaIQ.Core;

public enum SourceKind { Cinemana, Vodu, Dawwirli, Combined }
public sealed record Subtitle(string Url, string Title, string Language = "und");
public sealed record StreamOption(string Url, string Quality = "تلقائي")
{
    public int Height => System.Text.RegularExpressions.Regex.Match(Quality, @"(360|480|576|720|1080|1440|2160|4320)") is { Success: true } m ? int.Parse(m.Value) : 0;
    public override string ToString() => Quality;
}
public sealed record MediaItem
{
    public string Id { get; init; } = "";
    public string Title { get; init; } = "";
    public string EnglishTitle { get; init; } = "";
    public string OriginalTitle { get; init; } = "";
    public string Poster { get; init; } = "";
    public string Backdrop { get; init; } = "";
    public string Description { get; init; } = "";
    public string Year { get; init; } = "";
    public string Duration { get; init; } = "";
    public string Rating { get; init; } = "";
    public string ImdbId { get; init; } = "";
    public string ImdbRating { get; init; } = "";
    public string Quality { get; init; } = "";
    public string Edition { get; init; } = "";
    public string Trailer { get; init; } = "";
    public string SeriesId { get; init; } = "";
    public string EpisodeTitle { get; init; } = "";
    public string Director { get; init; } = "";
    public bool IsSeries { get; init; }
    public int Season { get; init; } = 1;
    public int Episode { get; init; }
    public List<Subtitle> Subtitles { get; init; } = [];
    public List<CastMember> Cast { get; init; } = [];
    public SourceKind Source => Id.StartsWith("vodu:") ? SourceKind.Vodu : Id.StartsWith("daw:") ? SourceKind.Dawwirli : SourceKind.Cinemana;
    public string GroupId => string.IsNullOrEmpty(SeriesId) ? Id : SeriesId;
    public string Artwork => string.IsNullOrEmpty(Backdrop) ? Poster : Backdrop;
    public string EpisodeLabel => Episode > 0 ? $"الحلقة {Episode}" + (string.IsNullOrWhiteSpace(EpisodeTitle) ? "" : " · " + EpisodeTitle) : Title;
    public string Information => string.Join("  ·  ", new[] { Year, string.IsNullOrEmpty(ImdbRating) ? Rating : "IMDb " + ImdbRating, Quality, Edition, Duration, Episode > 0 ? $"الموسم {Season} · الحلقة {Episode}" : IsSeries ? "مسلسل" : "فيلم" }.Where(x => !string.IsNullOrWhiteSpace(x)));
    public string DisplayTitle(bool english) => english ? (string.IsNullOrWhiteSpace(EnglishTitle) ? Title : EnglishTitle) : (string.IsNullOrWhiteSpace(OriginalTitle) ? Title : OriginalTitle);
}
public sealed record CastMember(string Name, string Role, string Image);
public sealed record Shelf(string Id, string Title, IReadOnlyList<MediaItem> Items);
public sealed record Season(int Number, IReadOnlyList<MediaItem> Episodes);
public interface IContentProvider
{
    Task<IReadOnlyList<Shelf>> Home(CancellationToken token);
    Task<IReadOnlyList<MediaItem>> Catalog(bool series, string query, int page, CancellationToken token);
    Task<MediaItem> Detail(MediaItem item, CancellationToken token);
    Task<IReadOnlyList<Season>> Seasons(MediaItem item, CancellationToken token);
    Task<IReadOnlyList<StreamOption>> Streams(MediaItem item, CancellationToken token);
}
public sealed class AppConfiguration
{
    public string TmdbApiKey { get; set; } = "";
    public string OmdbApiKey { get; set; } = "";
    public string YoutubeApiKey { get; set; } = "";
    public string FirebaseApiKey { get; set; } = "";
    public string FirebaseProjectId { get; set; } = "";
    public static AppConfiguration Load(string path) { try { return JsonSerializer.Deserialize<AppConfiguration>(File.ReadAllText(path), Json.Options) ?? new(); } catch { return new(); } }
}
public static class Json
{
    public static readonly JsonSerializerOptions Options = new() { PropertyNameCaseInsensitive = true, WriteIndented = true, Converters = { new JsonStringEnumConverter() } };
    public static string Text(this JsonElement o, params string[] names)
    {
        foreach (var name in names) if (o.ValueKind == JsonValueKind.Object && o.TryGetProperty(name, out var v) && v.ValueKind is JsonValueKind.String or JsonValueKind.Number or JsonValueKind.True or JsonValueKind.False && !string.IsNullOrWhiteSpace(v.ToString())) return v.ValueKind == JsonValueKind.String ? v.GetString()! : v.GetRawText();
        return "";
    }
    public static JsonElement Get(this JsonElement o, string name) => o.ValueKind == JsonValueKind.Object && o.TryGetProperty(name, out var v) ? v : default;
    public static int Number(this JsonElement o, params string[] names) => int.TryParse(o.Text(names), out var n) ? n : 0;
    public static IEnumerable<JsonElement> Rows(this JsonElement o)
    {
        if (o.ValueKind == JsonValueKind.Array) return o.EnumerateArray().ToArray();
        if (o.ValueKind == JsonValueKind.Object)
        {
            foreach (var key in new[] { "data", "result", "videos", "items", "list", "groups", "content" })
                if (o.TryGetProperty(key, out var v) && v.ValueKind is JsonValueKind.Array or JsonValueKind.Object) return v.Rows();
            return [o];
        }
        return [];
    }
    public static string Rating(this JsonElement o, params string[] keys)
    {
        foreach (var key in keys.Length == 0 ? ["stars", "rate", "imdbRate", "imdb_rating", "imdbRating", "rating", "tmdbRate"] : keys)
            if (double.TryParse(o.Text(key), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var n))
            { if (n > 10) n /= 10; if (n is > 0 and <= 10) return n.ToString("0.0", System.Globalization.CultureInfo.InvariantCulture); }
        return "";
    }
}
