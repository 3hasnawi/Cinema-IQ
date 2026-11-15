namespace CinemaIQ.Core;
public sealed class Combined(IReadOnlyDictionary<SourceKind, IContentProvider> providers) : IContentProvider
{
    private async Task<IReadOnlyList<T>> Both<T>(Func<IContentProvider, Task<IReadOnlyList<T>>> read)
    {
        var errors = 0;
        async Task<IReadOnlyList<T>> Safe(SourceKind kind) { try { return await read(providers[kind]); } catch (OperationCanceledException) { throw; } catch { Interlocked.Increment(ref errors); return []; } }
        var result = await Task.WhenAll(Safe(SourceKind.Cinemana), Safe(SourceKind.Vodu));
        if (errors == 2) throw new CinemaException("تعذّر تحميل المحتوى من المصدرين. تحقق من الشبكة أو اختر مصدرًا من الإعدادات.");
        return result.SelectMany(x => x).ToArray();
    }
    public Task<IReadOnlyList<Shelf>> Home(CancellationToken token) => Both(p => p.Home(token));
    public async Task<IReadOnlyList<MediaItem>> Catalog(bool series, string query, int page, CancellationToken token) => (await Both(p => p.Catalog(series, query, page, token))).DistinctBy(m => m.Id).ToArray();
    public Task<MediaItem> Detail(MediaItem item, CancellationToken token) => providers[item.Source].Detail(item, token);
    public Task<IReadOnlyList<Season>> Seasons(MediaItem item, CancellationToken token) => providers[item.Source].Seasons(item, token);
    public Task<IReadOnlyList<StreamOption>> Streams(MediaItem item, CancellationToken token) => providers[item.Source].Streams(item, token);
}
