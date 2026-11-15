using AngleSharp.Html.Parser;
using CinemaIQ.Core;
using System.Text;
using System.Text.Json;
using Xunit;
namespace CinemaIQ.Tests;

public sealed class CoreTests
{
    private static JsonElement Parse(string s) { using var doc = JsonDocument.Parse(s); return doc.RootElement.Clone(); }
    private static MediaItem Episode(int season, int episode) => new() { Id = $"{season}{episode}", Title = "مسلسل", IsSeries = true, SeriesId = "100", Season = season, Episode = episode };
    [Fact] public void CinemanaUsesZeroBasedSourcePageAndUtf8Base64Search()
    { var path = Cinemana.CatalogPath(false, "بحث عربي", 3); Assert.Contains("pageNumber/2/", path); Assert.Contains(Uri.EscapeDataString(Convert.ToBase64String(Encoding.UTF8.GetBytes("بحث عربي"))), path); Assert.Throws<ArgumentOutOfRangeException>(() => Cinemana.CatalogPath(false, "", 0)); }
    [Fact] public void CinemanaMapsMetadataAndRejectsInsecureArtwork()
    { var m = Cinemana.Parse(Parse("""{"nb":12,"ar_title":"عمل","en_title":"Work","stars":82,"kind":2,"episodeNummer":4,"videoQuality":"1080p","imgObjUrl":"http://unsafe/p.jpg"}"""))!; Assert.Equal("12", m.Id); Assert.Equal("8.2", m.Rating); Assert.True(m.IsSeries); Assert.Equal(4, m.Episode); Assert.Empty(m.Backdrop); }
    [Fact] public void CinemanaSubtitleNamesHandleNestedLanguagesAndFilenameFallbackWithoutDuplicates()
    { var subs = Cinemana.Subtitles(Parse("""[{"file":"https://cdn.example/one.srt","language":{"code":"ara"}},{"file":"https://cdn.example/one.srt","languageName":"Arabic"},{"file":"https://cdn.example/movie.eng.srt","language":"123"},{"file":"https://cdn.example/two.srt","languageName":"Arabic"}]""")); Assert.Equal(3, subs.Count); Assert.Equal(2, subs.Count(s => s.Language == "ar")); Assert.Contains(subs, s => s.Title == "الإنجليزية" && s.Language == "en"); Assert.Contains(subs, s => s.Title == "العربية · 1"); Assert.Contains(subs, s => s.Title == "العربية · 2"); }
    [Fact] public void VoduParsesCatalogAndEveryAdvertisedQuality()
    { var d = new HtmlParser().ParseDocument("""<div class="myitem"><img src="/a.jpg"><div class="mytitle"><a href="index.php?id=42">Title</a></div></div><div class="postcontent"><a class="play" data-url="https://cdn.example/720.mp4" data-url1080="https://cdn.example/1080.mp4" data-url4k="https://cdn.example/4k.mp4"></a></div>"""); var m = Assert.Single(Vodu.ParseCatalog(d, false)); Assert.Equal("vodu:42", m.Id); Assert.Equal("https://movie.vodu.me/a.jpg", m.Poster); Assert.Equal(3, Vodu.ParseStreams(d.QuerySelector("a.play")).Count); }
    [Theory] [InlineData("vodu:3:9", "3")] [InlineData("vodu:3", "3")] public void VoduEpisodeHasStableParent(string id, string parent) => Assert.Equal(parent, Vodu.Parent(id));
    [Theory] [InlineData("vodu:../4")] [InlineData("daw:movie:../5:mp4")] public void ProviderIdsRejectTraversal(string id) { if (id.StartsWith("vodu")) Assert.Throws<CinemaException>(() => Vodu.Parent(id)); else Assert.Throws<CinemaException>(() => Dawwirli.Key(id)); }
    [Fact] public void DawwirliOwnKindWinsOverSeriesHint()
    { var m = Dawwirli.Parse(Parse("""{"id":"77","kind":"movie","name":"EN | Title - 2020","ext":"mkv"}"""), true)!; Assert.Equal("daw:movie:77:mkv", m.Id); Assert.False(m.IsSeries); Assert.Equal("Title", m.Title); }
    [Fact] public async Task DawwirliBuildsOnlyTheProviderVodRoute()
    { var api = new Dawwirli(new FakeNetwork("{}")); var m = new MediaItem { Id = "daw:episode:21:77:ts" }; var s = Assert.Single(await api.Streams(m, default)); Assert.StartsWith(Dawwirli.Root + "vod?", s.Url); Assert.Contains("kind=series", s.Url); Assert.Contains("id=77", s.Url); Assert.Contains("ext=ts", s.Url); }
    [Fact] public void DawwirliMapsExternalSubtitleLanguageAndRelativeUrl()
    { var sub = Assert.Single(Dawwirli.Subtitles(Parse("""{"tracks":[{"url":"/subtitles/1.vtt","lang":"ar","label":"العربية"},{"url":"http://unsafe/2.srt"}]}"""))); Assert.Equal("ar", sub.Language); Assert.Equal("العربية", sub.Title); Assert.Equal(Dawwirli.Root + "subtitles/1.vtt", sub.Url); }
    [Theory] [InlineData("file:///etc/passwd")] [InlineData("https://user:pass@example.com/a")] [InlineData("https://example.com/#x")] [InlineData("http://example.com/a")] public void StreamPolicyRejectsInvalidOrUnapprovedUrls(string url) => Assert.Throws<CinemaException>(() => Network.Validate(url));
    [Fact] public void XtreamNormalizesRepeatedSchemeAndEncodesCredentials()
    { var s = new IptvSource { Name = "test", Kind = IptvKind.Xtream, Address = "http://http://example.com:8099", Username = "a+b", Password = "p/a?ss", AllowHttp = true }; Assert.Equal("http://example.com:8099/", s.Validate().AbsoluteUri); Assert.Contains("username=a%2Bb", IptvClient.Api(s).AbsoluteUri); Assert.Contains("a%2Bb/p%2Fa%3Fss/", IptvClient.Live(s, "12")); Assert.DoesNotContain(s.Password, s.ToString()); }
    [Fact] public void XtreamRejectsApiPageAsServer()
    { var s = new IptvSource { Name = "test", Kind = IptvKind.Xtream, Address = "https://example.com/player_api.php?username=x", Username = "x", Password = "y" }; Assert.Throws<CinemaException>(() => s.Validate()); }
    [Theory] [InlineData("https://example.com:8099/next", true)] [InlineData("https://example.com:8100/next", false)] [InlineData("https://other.example.com:8099/next", false)] [InlineData("http://example.com:8099/next", false)]
    public void SubscriptionRedirectKeepsExactOrigin(string url, bool allowed) => Assert.Equal(allowed, Network.AllowsRedirect(new Uri(url), "https://example.com:8099"));
    [Fact] public void PlaylistKeepsQuotedCommasAndStableIdentityAcrossTokenRefresh()
    { var source = new IptvSource { Id = "one", Name = "List", Address = "https://example.com/list.m3u" }; var text = "#EXTM3U\n#EXTINF:-1 group-title=\"News, World\" tvg-id=\"n1\",Arabic News\nhttps://example.com/live/a.m3u8?token=one"; var a = Assert.Single(IptvClient.ParseM3u(text, source)); var b = Assert.Single(IptvClient.ParseM3u(text.Replace("token=one", "token=two"), source)); Assert.Equal("News, World", a.Group); Assert.Equal(a.Id, b.Id); }
    [Fact] public void PlaylistRejectsHtmlAndUnapprovedHttp()
    { var source = new IptvSource { Name = "List", Address = "https://example.com/list" }; Assert.Throws<CinemaException>(() => IptvClient.ParseM3u("<html>error</html>", source)); Assert.Throws<CinemaException>(() => IptvClient.ParseM3u("#EXTM3U\n#EXTINF:-1,Channel\nhttp://example.com/live", source)); }
    [Fact] public void XtreamAuthenticationRejectsExpiredSubscription()
    { Assert.Throws<CinemaException>(() => IptvClient.ValidateAccount(Parse("""{"user_info":{"auth":1,"status":"Active","exp_date":"100"}}"""), DateTimeOffset.UtcNow)); IptvClient.ValidateAccount(Parse("""{"user_info":{"auth":true,"status":"Active"}}"""), DateTimeOffset.UtcNow); }
    [Fact] public void QualityGroupsDoNotMixChannelNumbers()
    { Channel C(string name) => new() { Id = name, SourceId = "s", Name = name, Group = "Sports", Url = "https://example.com/" + Uri.EscapeDataString(name) }; Assert.Equal(2, IptvClient.Qualities(C("Sport 1 HD"), [C("Sport 1 HD"), C("Sport 1 FHD"), C("Sport 2 FHD")]).Count); }
    [Fact] public void EpisodeNumberRemainsWhenTitleExists() => Assert.Equal("الحلقة 4 · الاسم", (Episode(1, 4) with { EpisodeTitle = "الاسم" }).EpisodeLabel);
    [Fact] public void NextEpisodeCrossesSeasonBoundary() => Assert.Equal("21", LibraryStore.Next(Episode(1, 9), [Episode(1, 8), Episode(2, 1), Episode(2, 2)])?.Id);
    [Fact] public void CloudFilmWirePreservesProviderIdentityAndEpisodeNumber()
    { var m = Episode(2, 3) with { Id = "vodu:42:99", SeriesId = "vodu:42", ImdbId = "tt12345" }; var copy = FilmWire.Decode(Parse(FilmWire.Encode(m)))!; Assert.Equal(m.Id, copy.Id); Assert.Equal(SourceKind.Vodu, copy.Source); Assert.Equal(3, copy.Episode); Assert.Equal("tt12345", copy.ImdbId); }
    [Fact] public void MovieCompletionRemovesContinueAndDoesNotLoop()
    { InStore(store => { var m = new MediaItem { Id = "1", Title = "Movie" }; store.Checkpoint(m, 99000, 100000, ended: true); Assert.Empty(store.Current.Recent); Assert.True(store.Current.Progress["1"].Completed); Assert.Equal(0, store.Position(m)); }); }
    [Fact] public void SeriesCompletionShowsNextAndRetainsLastCheckpoint()
    { InStore(store => { var a = Episode(1, 1); var b = Episode(1, 2); store.Checkpoint(a, 10000, 10000, [a, b], true); Assert.Equal(b.Id, store.Current.Recent[a.GroupId].Id); Assert.Equal(a.Id, store.Current.Followed[a.GroupId].Id); store.Checkpoint(b, 10000, 10000, [a, b], true); Assert.Empty(store.Current.Recent); Assert.Equal(b.Id, store.Current.Followed[a.GroupId].Id); }); }
    [Fact] public void ProfilesHaveSeparateLibrariesAndDurableOutboxes()
    { InStore(store => { var m = new MediaItem { Id = "1", Title = "Movie" }; store.ToggleFavorite(m); Assert.Contains("favorite/1", store.Current.Outbox.Keys); store.State.ActiveProfile = "other"; Assert.Empty(store.Current.Favorites); store.State.ActiveProfile = "main"; Assert.Single(store.Current.Favorites); }); }
    [Fact] public void PendingLocalEditWinsCloudPullAndTombstoneRemovesFavorite()
    { InStore(store => { var m = new MediaItem { Id = "1", Title = "Movie" }; store.ToggleFavorite(m); store.ApplyCloud(new Dictionary<string, string> { ["favorite/1"] = "" }); Assert.Single(store.Current.Favorites); store.Current.Outbox.Clear(); store.ApplyCloud(new Dictionary<string, string> { ["favorite/1"] = "" }); Assert.Empty(store.Current.Favorites); }); }
    [Fact] public void CorruptPrimaryFallsBackWithoutOverwritingValidBackup()
    { var dir = Path.Combine(Path.GetTempPath(), "cinema-test-" + Guid.NewGuid()); try { var store = new LibraryStore(dir); store.ToggleFavorite(new() { Id = "1", Title = "Keep" }); store.Save(); File.WriteAllText(Path.Combine(dir, "library-v1.json"), "{broken"); var recovered = new LibraryStore(dir); Assert.Single(recovered.Current.Favorites); recovered.Save(); Assert.Single(new LibraryStore(dir).Current.Favorites); Assert.Contains("Keep", File.ReadAllText(Path.Combine(dir, "library-v1.json.backup"))); } finally { Directory.Delete(dir, true); } }
    [Fact] public async Task NetworkFailureIsRedactedAndCancellationPropagates()
    { var provider = new Cinemana(new FailingNetwork()); var error = await Assert.ThrowsAsync<CinemaException>(() => provider.Home(default)); Assert.DoesNotContain("password", error.Message); using var cts = new CancellationTokenSource(); cts.Cancel(); await Assert.ThrowsAsync<OperationCanceledException>(() => provider.Home(cts.Token)); }
    private static void InStore(Action<LibraryStore> test) { var dir = Path.Combine(Path.GetTempPath(), "cinema-test-" + Guid.NewGuid()); try { test(new LibraryStore(dir)); } finally { Directory.Delete(dir, true); } }
    private sealed class FakeNetwork(string response) : INetwork { public Task<byte[]> Read(Uri uri, string root, CancellationToken token, bool allowHttp = false, bool cache = true, int maximumBytes = 8388608) { token.ThrowIfCancellationRequested(); return Task.FromResult(Encoding.UTF8.GetBytes(response)); } }
    private sealed class FailingNetwork : INetwork { public Task<byte[]> Read(Uri uri, string root, CancellationToken token, bool allowHttp = false, bool cache = true, int maximumBytes = 8388608) { token.ThrowIfCancellationRequested(); throw new CinemaException("تعذّر الاتصال بالمصدر."); } }
}
