// Cinema IQ external demo API — Cloudflare Worker
// Only licensed/public demonstration assets. No scraping or proxying.
const movies = [
  { id: "big-buck-bunny", title: "Big Buck Bunny", type: "movie", year: "2008", duration: "10", quality: "HLS",
    overview: "فيلم الرسوم المتحركة المفتوح من Blender Foundation.",
    poster: "https://upload.wikimedia.org/wikipedia/commons/c/c5/Big_buck_bunny_poster_big.jpg",
    backdrop: "https://upload.wikimedia.org/wikipedia/commons/c/c5/Big_buck_bunny_poster_big.jpg",
    stream: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8" },
  { id: "sintel", title: "Sintel", type: "movie", year: "2010", duration: "1", quality: "720p",
    overview: "العرض الدعائي لفيلم Sintel المفتوح من Blender.",
    poster: "https://upload.wikimedia.org/wikipedia/commons/8/8f/Sintel_poster.jpg",
    backdrop: "https://upload.wikimedia.org/wikipedia/commons/8/8f/Sintel_poster.jpg",
    stream: "https://download.blender.org/durian/trailer/sintel_trailer-720p.mp4" },
  { id: "tears-of-steel", title: "Tears of Steel", type: "movie", year: "2012", duration: "12", quality: "720p",
    overview: "فيلم خيال علمي مفتوح من Blender Foundation.",
    poster: "https://upload.wikimedia.org/wikipedia/commons/7/70/Tos-poster.png",
    backdrop: "https://mango.blender.org/wp-content/uploads/2013/05/01_thom_celia_bridge.jpg",
    stream: "https://download.blender.org/demo/movies/ToS/tears_of_steel_720p.mov" },
  { id: "elephants-dream", title: "Elephants Dream", type: "movie", year: "2006", duration: "11", quality: "720p",
    overview: "فيلم رسوم متحركة مفتوح المصدر.",
    poster: "https://upload.wikimedia.org/wikipedia/commons/0/0c/ElephantsDreamPoster.jpg",
    backdrop: "https://upload.wikimedia.org/wikipedia/commons/0/0c/ElephantsDreamPoster.jpg",
    stream: "https://download.blender.org/ED/elephantsdream-720-h264-st-aac.mov" }
];
const categoryNames = [{ id: "animation", title: "رسوم متحركة" }, { id: "shorts", title: "أفلام قصيرة" }];
const fields = item => { const { stream, ...publicItem } = item; return publicItem; };
const json = (body, status = 200, method = "GET") => new Response(method === "HEAD" ? null : JSON.stringify(body), {
  status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "public, max-age=60",
    "access-control-allow-origin": "*", "x-content-type-options": "nosniff" }
});
function route(request) {
  if (!["GET", "HEAD"].includes(request.method)) return json({ error: "Method not allowed" }, 405);
  const url = new URL(request.url);
  const p = url.pathname.replace(/\\/+$/, "").split("/").filter(Boolean);
  // Works at root or behind /cinema-iq/v1/
  const i = p.findIndex(x => ["home", "catalog", "categories", "items", "health"].includes(x));
  const parts = i < 0 ? [] : p.slice(i);
  const method = request.method;
  if (parts.length === 1 && parts[0] === "health") return json({ status: "ok", provider: "cinema-iq-demo" }, 200, method);
  if (parts.length === 1 && parts[0] === "home") return json({ shelves: [
    { id: "featured", title: "أفلام مفتوحة", items: movies.map(fields) },
    { id: "shorts", title: "أفلام قصيرة", items: movies.slice(0, 3).map(fields) }
  ] }, 200, method);
  if (parts.length === 1 && parts[0] === "categories") return json({ categories: categoryNames }, 200, method);
  if (parts.length === 1 && parts[0] === "catalog") {
    const type = url.searchParams.get("type") || "movie";
    const q = (url.searchParams.get("q") || "").trim().toLowerCase();
    const category = url.searchParams.get("category") || "";
    const page = Number(url.searchParams.get("page") || "1");
    if (!Number.isInteger(page) || page < 1 || page > 500) return json({ error: "Invalid page" }, 400, method);
    let result = type === "series" ? [] : movies;
    if (q) result = result.filter(x => (x.title + " " + x.overview).toLowerCase().includes(q));
    if (category === "animation") result = result.filter(x => ["big-buck-bunny", "sintel", "elephants-dream"].includes(x.id));
    else if (category && category !== "shorts") result = [];
    const size = 2, from = (page - 1) * size;
    return json({ items: result.slice(from, from + size).map(fields), page, hasMore: from + size < result.length }, 200, method);
  }
  if (parts[0] === "items" && parts.length >= 2 && parts.length <= 3) {
    const item = movies.find(x => x.id === parts[1]);
    if (!item) return json({ error: "Not found" }, 404, method);
    if (parts.length === 2) return json({ item: fields(item), seasons: [] }, 200, method);
    if (parts[2] === "streams") return json({ streams: [{ url: item.stream, quality: item.quality }], subtitles: [] }, 200, method);
  }
  return json({ error: "Not found" }, 404, method);
}
export default { fetch(request) { try { return route(request); } catch { return json({ error: "Server error" }, 500); } } };
