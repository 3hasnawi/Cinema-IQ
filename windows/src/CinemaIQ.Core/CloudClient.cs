using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
namespace CinemaIQ.Core;

public sealed class CloudSession
{
    public string Uid { get; set; } = "";
    public string Email { get; set; } = "";
    public string IdToken { get; set; } = "";
    public string RefreshToken { get; set; } = "";
    public DateTime ExpiresAt { get; set; }
    public bool Verified { get; set; }
    public override string ToString() => Email;
}
public sealed class CloudClient(AppConfiguration config) : IDisposable
{
    private readonly HttpClient client = new(new HttpClientHandler { AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(25) };
    public bool Configured => config.FirebaseApiKey.Length > 0 && Regex.IsMatch(config.FirebaseProjectId, @"^[a-z0-9-]+$");
    public CloudSession? Session { get; set; }
    public event Action? SessionChanged;
    private readonly SemaphoreSlim sync = new(1);
    private async Task<JsonElement> Send(HttpMethod method, string url, object? body, CancellationToken token, bool auth = false)
    {
        using var request = new HttpRequestMessage(method, url);
        if (auth) request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", Session?.IdToken);
        if (body is HttpContent content) request.Content = content;
        else if (body != null) request.Content = new StringContent(JsonSerializer.Serialize(body), Encoding.UTF8, "application/json");
        using var response = await client.SendAsync(request, token);
        if (!response.IsSuccessStatusCode)
        {
            var text = await response.Content.ReadAsStringAsync(token);
            throw new CinemaException(text.Contains("EMAIL_NOT_FOUND") || text.Contains("INVALID_LOGIN_CREDENTIALS") || text.Contains("INVALID_PASSWORD") ? "البريد أو كلمة المرور غير صحيحة." : text.Contains("EMAIL_EXISTS") ? "البريد مسجّل سابقًا." : text.Contains("WEAK_PASSWORD") ? "اختر كلمة مرور من 6 أحرف على الأقل." : text.Contains("API_KEY") || text.Contains("APP_BLOCKED") ? "تسجيل الدخول غير متاح في هذه النسخة حاليًا. يمكنك متابعة الاستخدام محليًا." : "تعذّر الاتصال بحساب المزامنة. تحقق من الشبكة وتأكيد البريد وقواعد Firebase.");
        }
        using var doc = JsonDocument.Parse(await response.Content.ReadAsByteArrayAsync(token)); return doc.RootElement.Clone();
    }
    private string Auth(string action) => "https://identitytoolkit.googleapis.com/v1/accounts:" + action + "?key=" + Uri.EscapeDataString(config.FirebaseApiKey);
    public async Task SignIn(string email, string password, bool create, CancellationToken token)
    {
        if (!Configured) throw new CinemaException("لم تُضبط إعدادات حساب المزامنة في هذه النسخة.");
        var o = await Send(HttpMethod.Post, Auth(create ? "signUp" : "signInWithPassword"), new { email = email.Trim(), password, returnSecureToken = true }, token);
        Session = new() { Uid = o.Text("localId"), Email = o.Text("email"), IdToken = o.Text("idToken"), RefreshToken = o.Text("refreshToken"), ExpiresAt = DateTime.UtcNow.AddSeconds(int.TryParse(o.Text("expiresIn"), out var seconds) ? seconds : 3600) };
        if (create) await Send(HttpMethod.Post, Auth("sendOobCode"), new { requestType = "VERIFY_EMAIL", idToken = Session.IdToken }, token);
        await Verify(token);
    }
    public async Task ResetPassword(string email, CancellationToken token) => await Send(HttpMethod.Post, Auth("sendOobCode"), new { requestType = "PASSWORD_RESET", email = email.Trim() }, token);
    public async Task Verify(CancellationToken token)
    {
        if (Session == null) return; await Valid(token);
        var o = await Send(HttpMethod.Post, Auth("lookup"), new { idToken = Session.IdToken }, token);
        Session.Verified = o.Get("users").Rows().FirstOrDefault().Text("emailVerified").Equals("true", StringComparison.OrdinalIgnoreCase);
        if (Session.Verified) { Session.ExpiresAt = DateTime.MinValue; await Valid(token); }
        SessionChanged?.Invoke();
    }
    private async Task Valid(CancellationToken token)
    {
        if (Session == null) throw new CinemaException("سجّل الدخول أولًا.");
        if (Session.ExpiresAt > DateTime.UtcNow.AddMinutes(2)) return;
        var o = await Send(HttpMethod.Post, "https://securetoken.googleapis.com/v1/token?key=" + Uri.EscapeDataString(config.FirebaseApiKey), new FormUrlEncodedContent(new Dictionary<string, string> { ["grant_type"] = "refresh_token", ["refresh_token"] = Session.RefreshToken }), token);
        Session.IdToken = o.Text("id_token"); Session.RefreshToken = o.Text("refresh_token"); Session.ExpiresAt = DateTime.UtcNow.AddSeconds(int.TryParse(o.Text("expires_in"), out var n) ? n : 3600); SessionChanged?.Invoke();
    }
    private string Documents => $"projects/{config.FirebaseProjectId}/databases/(default)/documents";
    private string ProfilePath(string id) => $"{Documents}/users/{Uri.EscapeDataString(Session!.Uid)}/profiles/{Uri.EscapeDataString(id)}";
    private async Task<List<JsonElement>> List(string path, CancellationToken token)
    {
        var result = new List<JsonElement>(); var pageToken = "";
        do
        {
            var o = await Send(HttpMethod.Get, "https://firestore.googleapis.com/v1/" + path + "?pageSize=300" + (pageToken.Length > 0 ? "&pageToken=" + Uri.EscapeDataString(pageToken) : ""), null, token, true);
            result.AddRange(o.Get("documents").Rows()); pageToken = o.Text("nextPageToken");
            if (result.Count > 10000) throw new CinemaException("بيانات المزامنة أكبر من الحد المدعوم.");
        } while (pageToken.Length > 0);
        return result;
    }
    public async Task<IReadOnlyList<Profile>> Profiles(CancellationToken token)
    {
        await Valid(token); if (!Session!.Verified) throw new CinemaException("أكّد بريدك أولًا ثم اضغط تحققت من البريد.");
        var docs = await List($"{Documents}/users/{Session.Uid}/profiles", token);
        return docs.Where(d => d.Get("fields").Get("deleted").Text("booleanValue") != "true").Select(d => new Profile(d.Text("name").Split('/')[^1], d.Get("fields").Get("name").Text("stringValue"), long.TryParse(d.Get("fields").Get("color").Text("integerValue"), out var color) ? color : 4294937131, d.Get("fields").Get("avatar").Text("stringValue"))).Where(p => p.Name.Length > 0 && Regex.IsMatch(p.Id, @"^[A-Za-z0-9_-]+$")).OrderBy(p => p.Id == "main" ? "" : p.Id).Take(6).ToArray();
    }
    private static object StringField(string value) => new { stringValue = value };
    public async Task DeleteProfile(string id, CancellationToken token)
    {
        if (id == "main") return; await Valid(token);
        await Send(HttpMethod.Patch, "https://firestore.googleapis.com/v1/" + ProfilePath(id) + "?updateMask.fieldPaths=deleted", new { fields = new { deleted = new { booleanValue = true } } }, token, true);
    }
    public async Task SaveProfile(Profile profile, CancellationToken token)
    {
        await Valid(token);
        var fields = new Dictionary<string, object> { ["name"] = StringField(profile.Name), ["color"] = new { integerValue = profile.Color.ToString() }, ["avatar"] = StringField(profile.Avatar.StartsWith("avatar-") ? profile.Avatar : "avatar-0"), ["deleted"] = new { booleanValue = false } };
        await Send(HttpMethod.Patch, "https://firestore.googleapis.com/v1/" + ProfilePath(profile.Id) + "?updateMask.fieldPaths=name&updateMask.fieldPaths=color&updateMask.fieldPaths=avatar&updateMask.fieldPaths=deleted", new { fields }, token, true);
    }
    public async Task Synchronize(LibraryStore store, CancellationToken token)
    {
        if (Session is not { Verified: true } || !await sync.WaitAsync(0, token)) return;
        try
        {
            await Valid(token); var profileId = store.State.ActiveProfile; var library = store.Current;
            foreach (var batch in library.Outbox.ToArray().Chunk(100))
            {
                var writes = batch.Select(pair => new { update = new { name = ProfilePath(profileId) + "/entries/" + Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(pair.Key))).ToLowerInvariant(), fields = new Dictionary<string, object> { ["key"] = StringField(pair.Key), ["value"] = StringField(pair.Value) } }, updateTransforms = new[] { new { fieldPath = "updatedAt", setToServerValue = "REQUEST_TIME" } } }).ToArray();
                await Send(HttpMethod.Post, "https://firestore.googleapis.com/v1/" + Documents + ":commit", new { writes }, token, true);
                foreach (var pair in batch) if (library.Outbox.GetValueOrDefault(pair.Key) == pair.Value) library.Outbox.Remove(pair.Key);
                store.Save();
            }
            var records = (await List(ProfilePath(profileId) + "/entries", token)).Where(d => d.Get("fields").Get("key").Text("stringValue").Length > 0).GroupBy(d => d.Get("fields").Get("key").Text("stringValue")).ToDictionary(g => g.Key, g => g.First().Get("fields").Get("value").Text("stringValue"));
            if (store.State.ActiveProfile == profileId && ReferenceEquals(store.Current, library)) store.ApplyCloud(records);
        }
        finally { sync.Release(); }
    }
    public void Dispose() { client.Dispose(); sync.Dispose(); }
}
