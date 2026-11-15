using CinemaIQ.Core;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Windows.Media.Imaging;
namespace CinemaIQ.Windows;

public sealed class SecretStore(string folder)
{
    private string FileName(string key) => Path.Combine(folder, Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(key))) + ".bin");
    public T? Read<T>(string key)
    { try { var bytes = ProtectedData.Unprotect(File.ReadAllBytes(FileName(key)), null, DataProtectionScope.CurrentUser); return JsonSerializer.Deserialize<T>(bytes, Json.Options); } catch (FileNotFoundException) { return default; } catch (DirectoryNotFoundException) { return default; } catch { throw new CinemaException("تعذّر فتح مخزن البيانات الآمن لهذا المستخدم. لم تُحذف بياناتك."); } }
    public void Write<T>(string key, T value)
    { Directory.CreateDirectory(folder); var bytes = ProtectedData.Protect(JsonSerializer.SerializeToUtf8Bytes(value, Json.Options), null, DataProtectionScope.CurrentUser); var file = FileName(key); File.WriteAllBytes(file + ".tmp", bytes); File.Move(file + ".tmp", file, true); }
    public void Delete(string key) { var path = FileName(key); if (File.Exists(path)) File.Delete(path); }
}
public sealed class Services : IDisposable
{
    public bool Smoke { get; }
    public string Folder { get; }
    public Network Network { get; } = new();
    public AppConfiguration Configuration { get; }
    public LibraryStore Library { get; private set; }
    public SecretStore Secrets { get; }
    public CloudClient Cloud { get; }
    public Metadata Metadata { get; }
    public ImageCache Images { get; }
    public Dictionary<SourceKind, IContentProvider> Providers { get; }
    public List<IptvSource> IptvSources { get; private set; }
    private readonly SemaphoreSlim cloudGate = new(1);
    public string StartupNotice { get; private set; } = "";
    private bool sourcesReadable = true;
    public Services(bool smoke)
    {
        Smoke = smoke; Folder = smoke ? Path.Combine(Path.GetTempPath(), "CinemaIQ-Smoke-" + Guid.NewGuid().ToString("N")) : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CinemaIQ", "UserData");
        Directory.CreateDirectory(Folder); Secrets = new(Path.Combine(Folder, "Secrets")); Configuration = AppConfiguration.Load(Path.Combine(AppContext.BaseDirectory, "appsettings.json"));
        Library = new(Folder); Cloud = new(Configuration); Cloud.SessionChanged += () => { if (Cloud.Session != null) Secrets.Write("cloud", Cloud.Session); };
        try { Cloud.Session = Secrets.Read<CloudSession>("cloud"); } catch (CinemaException e) { StartupNotice = e.Message; }
        if (Cloud.Session != null) SwitchAccount();
        try { IptvSources = Secrets.Read<List<IptvSource>>("iptv") ?? []; } catch (CinemaException e) { IptvSources = []; sourcesReadable = false; StartupNotice = e.Message; }
        Metadata = new(Network, Configuration); Images = new(Network, Path.Combine(Folder, "ImageCache"));
        Providers = new() { [SourceKind.Cinemana] = new Cinemana(Network), [SourceKind.Vodu] = new Vodu(Network), [SourceKind.Dawwirli] = new Dawwirli(Network) };
        Providers[SourceKind.Combined] = new Combined(Providers);
        if (smoke) foreach (var kind in Enum.GetValues<SourceKind>()) Providers[kind] = new SmokeProvider();
    }
    public void SwitchAccount()
    {
        var account = Cloud.Session?.Uid; Library = new(account == null ? Folder : Path.Combine(Folder, "Accounts", Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(account)))));
    }
    public void SaveSources() { if (!sourcesReadable) throw new CinemaException("مخزن الاشتراكات غير متاح لهذا المستخدم. لم تُستبدل بياناتك القديمة."); Secrets.Write("iptv", IptvSources); }
    public async Task Sync(CancellationToken token)
    {
        await cloudGate.WaitAsync(token);
        try {
        if (Cloud.Session == null) return;
        await Cloud.Verify(token); if (Cloud.Session.Verified)
        {
            foreach (var p in Library.State.PendingProfiles.Values.ToArray()) { await Cloud.SaveProfile(p, token); if (Library.State.PendingProfiles.GetValueOrDefault(p.Id) == p) Library.State.PendingProfiles.Remove(p.Id); Library.Save(); }
            foreach (var id in Library.State.DeletedProfiles.ToArray()) { await Cloud.DeleteProfile(id, token); Library.State.DeletedProfiles.Remove(id); Library.Save(); }
            var profiles = await Cloud.Profiles(token);
            if (profiles.Count == 0) foreach (var p in Library.State.Profiles) await Cloud.SaveProfile(p, token);
            else { Library.State.Profiles = profiles.ToList(); if (!profiles.Any(p => p.Id == Library.State.ActiveProfile)) Library.State.ActiveProfile = profiles[0].Id; Library.Save(); }
            await Cloud.Synchronize(Library, token);
        }
        } finally { cloudGate.Release(); }
    }
    public async Task SignIn(string email, string password, bool create, CancellationToken token)
    {
        await cloudGate.WaitAsync(token); var previous = Cloud.Session;
        try { await Cloud.SignIn(email, password, create, token); SwitchAccount(); }
        catch { Cloud.Session = previous; if (previous == null) Secrets.Delete("cloud"); else Secrets.Write("cloud", previous); throw; }
        finally { cloudGate.Release(); }
    }
    public async Task SignOut(CancellationToken token)
    { await cloudGate.WaitAsync(token); try { Cloud.Session = null; Secrets.Delete("cloud"); SwitchAccount(); } finally { cloudGate.Release(); } }
    public void Dispose() { Cloud.Dispose(); Network.Dispose(); }
}
public sealed class ImageCache(INetwork network, string folder)
{
    private readonly System.Collections.Concurrent.ConcurrentDictionary<string, Task<BitmapSource?>> active = new();
    public Task<BitmapSource?> Load(string url, int width = 600) => string.IsNullOrWhiteSpace(url) ? Task.FromResult<BitmapSource?>(null) : active.GetOrAdd(url + "|" + width, _ => Read(url, width));
    private async Task<BitmapSource?> Read(string url, int width)
    {
        try
        {
            var uri = Network.Validate(url, true); Directory.CreateDirectory(folder); var path = Path.Combine(folder, Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(url))));
            var bytes = File.Exists(path) ? await File.ReadAllBytesAsync(path) : await network.Read(uri, uri.Host, CancellationToken.None, true, true, 6 * 1024 * 1024);
            var image = await Task.Run(() => { using var stream = new MemoryStream(bytes); var bitmap = new BitmapImage(); bitmap.BeginInit(); bitmap.CacheOption = BitmapCacheOption.OnLoad; bitmap.DecodePixelWidth = width; bitmap.StreamSource = stream; bitmap.EndInit(); bitmap.Freeze(); return bitmap; });
            if (!File.Exists(path)) await File.WriteAllBytesAsync(path, bytes);
            if (active.Count > 300) active.Clear();
            var files = new DirectoryInfo(folder).GetFiles(); long size = files.Sum(f => f.Length);
            foreach (var old in files.OrderBy(f => f.LastWriteTimeUtc)) { if (size < 200 * 1024 * 1024) break; try { old.Delete(); size -= old.Length; } catch (IOException) { } }
            return image;
        }
        catch { return null; }
    }
}
