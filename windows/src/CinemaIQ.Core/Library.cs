using System.Text.Json;
namespace CinemaIQ.Core;

public sealed record Profile(string Id, string Name, long Color = 4294937131, string Avatar = "avatar-0");
public sealed record Progress(MediaItem Item, long Position, long Duration, bool Completed, long UpdatedAt);
public sealed class LibraryData
{
    public Dictionary<string, MediaItem> Favorites { get; set; } = [];
    public Dictionary<string, MediaItem> Recent { get; set; } = [];
    public Dictionary<string, MediaItem> Followed { get; set; } = [];
    public Dictionary<string, Progress> Progress { get; set; } = [];
    public Dictionary<string, string> Outbox { get; set; } = [];
}
public sealed class Settings
{
    public SourceKind Source { get; set; } = SourceKind.Combined;
    public bool EnglishTitles { get; set; } = true;
    public string Quality { get; set; } = "auto";
    public int SubtitleSize { get; set; } = 24;
    public int SubtitleBackground { get; set; } = 128;
    public string SubtitleLanguage { get; set; } = "ar";
    public bool HardwareDecoding { get; set; } = true;
    public bool ReducedMotion { get; set; }
}
public sealed class LocalState
{
    public int Schema { get; set; } = 1;
    public string ActiveProfile { get; set; } = "main";
    public List<Profile> Profiles { get; set; } = [new("main", "الملف الرئيسي")];
    public Settings Settings { get; set; } = new();
    public Dictionary<string, LibraryData> Libraries { get; set; } = [];
    public Dictionary<string, Profile> PendingProfiles { get; set; } = [];
    public List<string> DeletedProfiles { get; set; } = [];
    public string IptvSourceId { get; set; } = "";
}
public sealed class LibraryStore
{
    private readonly object gate = new();
    private readonly string file;
    public LocalState State { get; private set; }
    public event Action? Changed;
    public LibraryStore(string folder)
    {
        Directory.CreateDirectory(folder); file = Path.Combine(folder, "library-v1.json"); State = Read(file) ?? Read(file + ".backup") ?? new();
        if (State.Profiles.Count == 0) State.Profiles.Add(new("main", "الملف الرئيسي"));
        if (!State.Profiles.Any(p => p.Id == State.ActiveProfile)) State.ActiveProfile = State.Profiles[0].Id;
    }
    private static LocalState? Read(string file)
    {
        try
        {
            var s = JsonSerializer.Deserialize<LocalState>(File.ReadAllText(file), Json.Options);
            if (s == null || s.Schema != 1 || s.Profiles == null || s.Settings == null || s.Libraries == null || s.PendingProfiles == null || s.DeletedProfiles == null || s.Profiles.Any(p => p == null || string.IsNullOrWhiteSpace(p.Id) || string.IsNullOrWhiteSpace(p.Name)) || s.Libraries.Values.Any(l => l == null || l.Favorites == null || l.Recent == null || l.Followed == null || l.Progress == null || l.Outbox == null)) return null;
            if (!Enum.IsDefined(s.Settings.Source)) s.Settings.Source = SourceKind.Combined;
            return s;
        }
        catch { return null; }
    }
    public LibraryData Current { get { lock (gate) { if (!State.Libraries.ContainsKey(State.ActiveProfile)) State.Libraries[State.ActiveProfile] = new(); return State.Libraries[State.ActiveProfile]; } } }
    public void Save()
    {
        lock (gate)
        {
            var temp = file + ".tmp"; File.WriteAllText(temp, JsonSerializer.Serialize(State, Json.Options));
            if (File.Exists(file) && Read(file) != null) File.Copy(file, file + ".backup", true); File.Move(temp, file, true);
        }
        Changed?.Invoke();
    }
    public void ToggleFavorite(MediaItem item)
    {
        if (!Current.Favorites.Remove(item.Id)) Current.Favorites[item.Id] = item;
        Queue("favorite/" + item.Id, Current.Favorites.ContainsKey(item.Id) ? FilmWire.Encode(item) : ""); Save();
    }
    public long Position(MediaItem item) => Current.Progress.GetValueOrDefault(item.Id) is { Completed: false } p ? p.Position : 0;
    public void Checkpoint(MediaItem item, long position, long duration, IReadOnlyList<MediaItem>? episodes = null, bool ended = false)
    {
        if (position < 0 || duration < 0 || duration > 0 && position > duration + 10000) return;
        var completed = ended || duration > 0 && position >= duration * 0.95;
        var p = new Progress(item, completed ? 0 : position, duration, completed, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()); Current.Progress[item.Id] = p;
        if (item.IsSeries) { Current.Followed[item.GroupId] = item; Queue("follow/" + item.GroupId, FilmWire.Encode(item)); }
        if (completed)
        {
            Current.Recent.Remove(item.GroupId);
            var next = Next(item, episodes ?? []); if (next != null) Current.Recent[item.GroupId] = next;
        }
        else Current.Recent[item.GroupId] = item;
        Queue("recent/" + item.GroupId, Current.Recent.TryGetValue(item.GroupId, out var recent) ? FilmWire.Encode(recent) : "");
        Queue("playback/" + item.Id, JsonSerializer.Serialize(new { position = p.Position, duration, completed, watched = completed ? FilmWire.Object(item, p.UpdatedAt) : null }));
        if (Current.Progress.Count > 1500) foreach (var old in Current.Progress.Values.OrderByDescending(p => p.UpdatedAt).Skip(1500).Select(p => p.Item.Id).ToArray()) Current.Progress.Remove(old);
        Save();
    }
    public static MediaItem? Next(MediaItem item, IReadOnlyList<MediaItem> episodes) => episodes.Where(e => e.GroupId == item.GroupId && (e.Season > item.Season || e.Season == item.Season && e.Episode > item.Episode)).OrderBy(e => e.Season).ThenBy(e => e.Episode).FirstOrDefault();
    public void RemoveRecent(MediaItem item)
    { Current.Recent.Remove(item.GroupId); Current.Followed.Remove(item.GroupId); Queue("recent/" + item.GroupId, ""); Queue("follow/" + item.GroupId, ""); Save(); }
    public void MarkWatched(MediaItem item, bool watched)
    { if (watched) Checkpoint(item, 0, 0, ended: true); else { Current.Progress[item.Id] = new(item, 0, 0, false, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()); Queue("playback/" + item.Id, "{\"position\":0,\"duration\":0,\"completed\":false}"); Save(); } }
    private void Queue(string key, string value) => Current.Outbox[key] = value;
    public void ApplyCloud(IReadOnlyDictionary<string, string> records)
    {
        foreach (var (key, value) in records)
        {
            if (Current.Outbox.ContainsKey(key)) continue;
            var p = key.Split('/', 2); if (p.Length != 2) continue;
            if (p[0] is "favorite" or "recent" or "follow")
            {
                var target = p[0] == "favorite" ? Current.Favorites : p[0] == "recent" ? Current.Recent : Current.Followed;
                if (value.Length == 0) target.Remove(p[1]); else try { using var doc = JsonDocument.Parse(value); var m = FilmWire.Decode(doc.RootElement); if (m != null) target[p[1]] = m; } catch (JsonException) { }
            }
            else if (p[0] == "playback" && value.Length > 0) try
            {
                using var doc = JsonDocument.Parse(value); var o = doc.RootElement; var m = FilmWire.Decode(o.Get("watched")) ?? Current.Progress.GetValueOrDefault(p[1])?.Item ?? Current.Recent.Values.FirstOrDefault(m => m.Id == p[1]);
                if (m != null) Current.Progress[p[1]] = new(m, o.Get("position").TryGetInt64Safe(), o.Get("duration").TryGetInt64Safe(), o.Text("completed").Equals("true", StringComparison.OrdinalIgnoreCase), DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            } catch (JsonException) { }
        }
        Save();
    }
}
public static class FilmWire
{
    public static object Object(MediaItem m, long watchedAt = 0) => new { nb = m.Id, ar_title = m.Title, en_title = m.EnglishTitle, original_title = m.OriginalTitle, imgMediumThumbObjUrl = m.Poster, imgObjUrl = m.Backdrop, ar_content = m.Description, year = m.Year, duration = m.Duration, stars = m.Rating, kind = m.IsSeries ? "2" : "1", season = m.Season, episodeNummer = m.Episode, seriesId = m.SeriesId, episodeTitle = m.EpisodeTitle, imdbId = m.ImdbId, videoQuality = m.Quality, videoVersion = m.Edition, watchedAt };
    public static string Encode(MediaItem item) => JsonSerializer.Serialize(Object(item));
    public static MediaItem? Decode(JsonElement o) => Cinemana.Parse(o);
    public static long TryGetInt64Safe(this JsonElement o) => o.ValueKind == JsonValueKind.Number && o.TryGetInt64(out var n) ? Math.Max(0, n) : long.TryParse(o.ToString(), out n) ? Math.Max(0, n) : 0;
}
