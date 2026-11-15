using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
namespace CinemaIQ.Core;

public enum IptvKind { M3u, Xtream }
public sealed class IptvSource
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Name { get; set; } = "";
    public IptvKind Kind { get; set; }
    public string Address { get; set; } = "";
    public string Username { get; set; } = "";
    public string Password { get; set; } = "";
    public bool AllowHttp { get; set; }
    public string Output { get; set; } = "m3u8";
    public override string ToString() => Name;
    public Uri Validate()
    {
        var text = Address.Trim(); for (var i = 0; i < 4; i++) { var next = Regex.Replace(text, @"^https?://(?=https?://)", "", RegexOptions.IgnoreCase); if (next == text) break; text = next; }
        if (!text.Contains("://")) text = "https://" + text;
        var uri = Network.Validate(text, AllowHttp);
        if (Name.Trim().Length == 0 || Name.Length > 100) throw new CinemaException("أدخل اسمًا للمصدر لا يتجاوز 100 حرف.");
        if (Kind == IptvKind.Xtream)
        {
            if (uri.Query.Length > 0 || uri.AbsolutePath.EndsWith(".php", StringComparison.OrdinalIgnoreCase)) throw new CinemaException("أدخل عنوان خادم Xtream فقط، دون get.php أو player_api.php.");
            if (string.IsNullOrWhiteSpace(Username) || Password.Length == 0 || Username is "." or ".." || Password is "." or ".." || Username.Length > 1024 || Password.Length > 4096) throw new CinemaException("تحقق من اسم المستخدم وكلمة المرور.");
            if (Output is not "m3u8" and not "ts") throw new CinemaException("صيغة البث غير صالحة.");
            uri = new Uri(uri.AbsoluteUri.TrimEnd('/') + "/");
        }
        Address = uri.AbsoluteUri; return uri;
    }
}
public sealed class Channel
{
    public required string Id { get; init; }
    public required string SourceId { get; init; }
    public required string Name { get; init; }
    public string Group { get; init; } = "عام";
    public required string Url { get; init; }
    public string Logo { get; init; } = "";
    public string TvgId { get; init; } = "";
    public override string ToString() => Name;
}
public sealed class IptvClient(INetwork network)
{
    public const int MaximumBytes = 20 * 1024 * 1024;
    public const int MaximumChannels = 20000;
    public static string Identity(string source, string data) => source + ":" + Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(data)))[..32].ToLowerInvariant();
    public static Uri Api(IptvSource source, string? action = null) => NetworkExtensions.Url(source.Validate().AbsoluteUri, "player_api.php", action == null ? [("username", source.Username), ("password", source.Password)] : [("username", source.Username), ("password", source.Password), ("action", action)]);
    public static string Live(IptvSource source, string id)
    {
        if (!Regex.IsMatch(id, @"^[1-9]\d{0,18}$")) throw new CinemaException("معرّف قناة غير صالح.");
        return new Uri(source.Validate(), $"live/{Uri.EscapeDataString(source.Username)}/{Uri.EscapeDataString(source.Password)}/{id}.{source.Output}").AbsoluteUri;
    }
    public static IReadOnlyList<Channel> ParseM3u(string text, IptvSource source)
    {
        if (Encoding.UTF8.GetByteCount(text) > MaximumBytes) throw new CinemaException("قائمة القنوات أكبر من الحد المدعوم.");
        var lines = text.TrimStart('\uFEFF').Replace("\r", "").Split('\n');
        if (!lines.FirstOrDefault(l => !string.IsNullOrWhiteSpace(l))?.StartsWith("#EXTM3U", StringComparison.OrdinalIgnoreCase) ?? true) throw new CinemaException("الرابط لا يحتوي قائمة M3U صالحة.");
        var root = source.Validate(); var list = new List<Channel>(); var title = ""; var group = "عام"; var logo = ""; var tvg = "";
        foreach (var raw in lines)
        {
            var line = raw.Trim();
            if (line.StartsWith("#EXTINF:", StringComparison.OrdinalIgnoreCase))
            {
                var quoted = false; var comma = -1;
                for (var i = 0; i < line.Length; i++) { if (line[i] == '"' && (i == 0 || line[i - 1] != '\\')) quoted = !quoted; if (line[i] == ',' && !quoted) { comma = i; break; } }
                var metadata = comma >= 0 ? line[..comma] : line;
                var attrs = Regex.Matches(metadata, "([\\w-]+)\\s*=\\s*\"([^\"]*)\"", RegexOptions.CultureInvariant, TimeSpan.FromMilliseconds(100)).ToDictionary(m => m.Groups[1].Value.ToLowerInvariant(), m => m.Groups[2].Value);
                title = comma >= 0 ? line[(comma + 1)..].Trim() : attrs.GetValueOrDefault("tvg-name", "");
                group = attrs.GetValueOrDefault("group-title", "عام"); tvg = attrs.GetValueOrDefault("tvg-id", ""); logo = attrs.GetValueOrDefault("tvg-logo", "");
            }
            else if (line.StartsWith("#EXTGRP:", StringComparison.OrdinalIgnoreCase)) group = line[8..].Trim();
            else if (line.Length > 0 && !line.StartsWith('#'))
            {
                try
                {
                    var uri = Network.Validate(new Uri(root, line).AbsoluteUri, source.AllowHttp);
                    var name = title.Length > 0 ? title : "قناة " + (list.Count + 1); var category = group.Length > 0 ? group : "عام";
                    var image = ""; if (logo.Length > 0) try { image = Network.Validate(new Uri(root, logo).AbsoluteUri, source.AllowHttp).AbsoluteUri; } catch (CinemaException) { }
                    list.Add(new() { Id = Identity(source.Id, $"{tvg}|{name}|{category}|{uri.Host}:{uri.Port}{uri.AbsolutePath}"), SourceId = source.Id, Name = name[..Math.Min(200, name.Length)], Group = category[..Math.Min(150, category.Length)], Url = uri.AbsoluteUri, Logo = image, TvgId = tvg });
                    if (list.Count > MaximumChannels) throw new CinemaException("قائمة القنوات أكبر من الحد المدعوم.");
                }
                catch (UriFormatException) { }
                catch (CinemaException) when (list.Count <= MaximumChannels) { }
                title = ""; group = "عام"; logo = ""; tvg = "";
            }
        }
        if (list.Count == 0) throw new CinemaException("لم يُعثر على قنوات صالحة. تحقق من المصدر وخيار HTTP.");
        return list.DistinctBy(c => c.Id).ToArray();
    }
    public static void ValidateAccount(JsonElement data, DateTimeOffset now)
    {
        var info = data.Get("user_info"); if (info.Text("auth") is not "1" and not "true" || !info.Text("status").Equals("Active", StringComparison.OrdinalIgnoreCase)) throw new CinemaException("الاشتراك غير صالح أو غير مفعّل. تحقق من بياناته.");
        if (long.TryParse(info.Text("exp_date"), out var expires) && expires > 0 && expires <= now.ToUnixTimeSeconds()) throw new CinemaException("الاشتراك منتهي. راجع مزوّد الاشتراك.");
    }
    public static IReadOnlyList<Channel> ParseXtream(JsonElement data, JsonElement categories, IptvSource source)
    {
        if (data.ValueKind != JsonValueKind.Array || data.GetArrayLength() > MaximumChannels) throw new CinemaException("قائمة Xtream غير صالحة أو أكبر من الحد المدعوم.");
        var groups = categories.Rows().Where(c => c.Text("category_id").Length > 0).GroupBy(c => c.Text("category_id")).ToDictionary(g => g.Key, g => g.First().Text("category_name"));
        var result = new List<Channel>();
        foreach (var o in data.EnumerateArray())
        {
            var id = o.Text("stream_id"); var name = o.Text("name"); if (!Regex.IsMatch(id, @"^[1-9]\d{0,18}$") || name.Length == 0) continue;
            var logo = o.Text("stream_icon"); if (logo.Length > 0) try { logo = Network.Validate(logo, source.AllowHttp).AbsoluteUri; } catch (CinemaException) { logo = ""; }
            result.Add(new() { Id = Identity(source.Id, "xtream:" + id), SourceId = source.Id, Name = name, Group = groups.GetValueOrDefault(o.Text("category_id"), "عام"), Url = Live(source, id), Logo = logo, TvgId = o.Text("epg_channel_id") });
        }
        if (result.Count == 0) throw new CinemaException("لا تتوفر قنوات لهذا الاشتراك.");
        return result.DistinctBy(c => c.Id).ToArray();
    }
    public async Task<IReadOnlyList<Channel>> Load(IptvSource source, CancellationToken token)
    {
        var root = source.Validate();
        var origin = root.GetLeftPart(UriPartial.Authority);
        if (source.Kind == IptvKind.M3u) return ParseM3u(Encoding.UTF8.GetString(await network.Read(root, origin, token, source.AllowHttp, false, MaximumBytes)), source);
        ValidateAccount(await network.Json(Api(source), origin, token, source.AllowHttp, false), DateTimeOffset.UtcNow);
        var categories = await network.Json(Api(source, "get_live_categories"), origin, token, source.AllowHttp, false);
        using var doc = JsonDocument.Parse(await network.Read(Api(source, "get_live_streams"), origin, token, source.AllowHttp, false, MaximumBytes));
        return ParseXtream(doc.RootElement, categories, source);
    }
    public static IReadOnlyList<StreamOption> Qualities(Channel selected, IEnumerable<Channel> all)
    {
        string Base(string name) => Regex.Replace(name, @"\b(?:4K|UHD|FHD|HD|SD|2160p?|1080p?|720p?)\b|[\[\]()]", "", RegexOptions.IgnoreCase).Trim();
        return all.Where(c => c.SourceId == selected.SourceId && c.Group == selected.Group && Base(c.Name).Equals(Base(selected.Name), StringComparison.OrdinalIgnoreCase)).Select(c => new StreamOption(c.Url, c.Name)).DistinctBy(s => s.Url).ToArray();
    }
}
