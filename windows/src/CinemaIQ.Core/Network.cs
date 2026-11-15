using System.Collections.Concurrent;
using System.Net;
using System.Text.Json;
namespace CinemaIQ.Core;

public sealed class CinemaException(string message) : Exception(message);
public interface INetwork
{
    Task<byte[]> Read(Uri uri, string trustedRoot, CancellationToken token, bool allowHttp = false, bool cache = true, int maximumBytes = 8 * 1024 * 1024);
}
public sealed class Network : INetwork, IDisposable
{
    private readonly HttpClient client = new(new SocketsHttpHandler { AllowAutoRedirect = false, AutomaticDecompression = DecompressionMethods.All, ConnectTimeout = TimeSpan.FromSeconds(12), PooledConnectionLifetime = TimeSpan.FromMinutes(5) }) { Timeout = TimeSpan.FromSeconds(35) };
    private readonly SemaphoreSlim slots = new(6);
    private readonly ConcurrentDictionary<string, (DateTime Until, byte[] Bytes)> saved = new();
    public static Uri Validate(string value, bool allowHttp = false)
    {
        if (value.Length > 8192 || !Uri.TryCreate(value.Trim(), UriKind.Absolute, out var uri) || (uri.Scheme != "https" && uri.Scheme != "http") || string.IsNullOrEmpty(uri.Host) || !string.IsNullOrEmpty(uri.UserInfo) || !string.IsNullOrEmpty(uri.Fragment)) throw new CinemaException("أدخل رابط HTTP أو HTTPS صالحًا دون بيانات دخول في اسم الخادم.");
        if (uri.Scheme == "http" && !allowHttp) throw new CinemaException("فعّل السماح باتصال HTTP لهذا المصدر أو استخدم HTTPS.");
        return uri;
    }
    public static bool AllowsRedirect(Uri next, string trustedRoot)
    {
        // Subscription requests carry credentials in the URL: keep them on the exact origin.
        if (Uri.TryCreate(trustedRoot, UriKind.Absolute, out var origin)) return next.Scheme == origin.Scheme && next.Host.Equals(origin.Host, StringComparison.OrdinalIgnoreCase) && next.Port == origin.Port;
        return next.Host.Equals(trustedRoot, StringComparison.OrdinalIgnoreCase) || next.Host.EndsWith("." + trustedRoot, StringComparison.OrdinalIgnoreCase);
    }
    public async Task<byte[]> Read(Uri uri, string trustedRoot, CancellationToken token, bool allowHttp = false, bool cache = true, int maximumBytes = 8 * 1024 * 1024)
    {
        Validate(uri.AbsoluteUri, allowHttp);
        if (cache && saved.TryGetValue(uri.AbsoluteUri, out var hit) && hit.Until > DateTime.UtcNow) return hit.Bytes;
        await slots.WaitAsync(token);
        try
        {
            if (cache && saved.TryGetValue(uri.AbsoluteUri, out hit) && hit.Until > DateTime.UtcNow) return hit.Bytes;
            for (var attempt = 0; ; attempt++)
            {
                var current = uri;
                try
                {
                    for (var redirect = 0; redirect < 6; redirect++)
                    {
                        using var request = new HttpRequestMessage(HttpMethod.Get, current);
                        request.Headers.TryAddWithoutValidation("User-Agent", "okhttp/4.12.0");
                        using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, token);
                        if ((int)response.StatusCode is 301 or 302 or 303 or 307 or 308)
                        {
                            var next = response.Headers.Location is { } location ? new Uri(current, location) : throw new CinemaException("إعادة توجيه غير صالحة من المصدر.");
                            Validate(next.AbsoluteUri, allowHttp);
                            if (!AllowsRedirect(next, trustedRoot)) throw new CinemaException("نقل المصدر الطلب إلى خادم مختلف. استخدم الرابط المباشر.");
                            if (current.Scheme == "https" && next.Scheme != "https") throw new CinemaException("رفض اتصال غير آمن بعد إعادة التوجيه.");
                            current = next; continue;
                        }
                        if (!response.IsSuccessStatusCode)
                        {
                            if (attempt == 0 && ((int)response.StatusCode >= 500 || response.StatusCode == HttpStatusCode.TooManyRequests))
                            { var wait = response.Headers.RetryAfter?.Delta ?? TimeSpan.FromMilliseconds(800); if (wait <= TimeSpan.FromSeconds(10)) { await Task.Delay(wait < TimeSpan.Zero ? TimeSpan.Zero : wait, token); break; } }
                            throw new CinemaException(HttpMessage((int)response.StatusCode));
                        }
                        if (response.Content.Headers.ContentLength > maximumBytes) throw new CinemaException("حجم استجابة المصدر أكبر من الحد المدعوم.");
                        using var input = await response.Content.ReadAsStreamAsync(token); using var output = new MemoryStream(); var buffer = new byte[16384];
                        int count; while ((count = await input.ReadAsync(buffer, token)) > 0) { if (output.Length + count > maximumBytes) throw new CinemaException("حجم استجابة المصدر أكبر من الحد المدعوم."); await output.WriteAsync(buffer.AsMemory(0, count), token); }
                        var bytes = output.ToArray();
                        if (cache) { if (saved.Count >= 64) saved.Clear(); saved[uri.AbsoluteUri] = (DateTime.UtcNow.AddMinutes(5), bytes); }
                        return bytes;
                    }
                    if (attempt > 0) throw new CinemaException("تعذّر إكمال الاتصال بالمصدر.");
                }
                catch (HttpRequestException) when (attempt == 0) { await Task.Delay(700, token); }
                catch (HttpRequestException) { throw new CinemaException("تعذّر الاتصال بالمصدر. تحقق من الشبكة والاتصال الآمن."); }
                catch (OperationCanceledException) when (!token.IsCancellationRequested) { throw new CinemaException("انتهت مهلة الاتصال بالمصدر. أعد المحاولة."); }
            }
        }
        finally { slots.Release(); }
    }
    public static string HttpMessage(int code) => code switch { 401 or 403 => "رفض المصدر الاتصال. تحقق من الاشتراك أو الشبكة الداعمة.", 404 => "المحتوى أو الرابط غير متاح لدى المصدر.", 429 => "طلبات كثيرة. انتظر قليلًا ثم أعد المحاولة.", _ => "تعذّر الاتصال بخادم المصدر. أعد المحاولة لاحقًا." };
    public void Dispose() { client.Dispose(); slots.Dispose(); }
}
public static class NetworkExtensions
{
    public static async Task<JsonElement> Json(this INetwork network, Uri uri, string root, CancellationToken token, bool allowHttp = false, bool cache = true)
    { try { using var doc = JsonDocument.Parse(await network.Read(uri, root, token, allowHttp, cache)); return doc.RootElement.Clone(); } catch (JsonException) { throw new CinemaException("أعاد المصدر بيانات غير صالحة. تحقق من العنوان والشبكة."); } }
    public static Uri Url(string root, string path, params (string Key, string Value)[] query) => new(new Uri(root), path + (query.Length == 0 ? "" : "?" + string.Join("&", query.Select(p => Uri.EscapeDataString(p.Key) + "=" + Uri.EscapeDataString(p.Value)))));
}
