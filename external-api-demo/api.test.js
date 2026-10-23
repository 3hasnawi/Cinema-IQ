import test from "node:test";
import assert from "node:assert/strict";
import worker from "./src/index.js";
const get = async (path) => {
  const response = await worker.fetch(new Request("https://demo.example.com/" + path));
  return { status: response.status, data: await response.json() };
};
test("home shelves have compatible media", async () => {
  const { status, data } = await get("home");
  assert.equal(status, 200);
  assert.ok(data.shelves[0].items.length > 0);
  assert.ok(data.shelves[0].items[0].id);
  assert.equal(data.shelves[0].items[0].stream, undefined);
});
test("catalog filters and paginates", async () => {
  const a = await get("catalog?type=movie&page=1");
  assert.equal(a.data.items.length, 2);
  assert.equal(a.data.hasMore, true);
  const b = await get("catalog?type=movie&page=2&q=Sintel");
  assert.equal(b.data.items.length, 0);
});
test("detail and streams match Cinema IQ schema", async () => {
  const d = await get("items/big-buck-bunny");
  assert.equal(d.data.item.title, "Big Buck Bunny");
  const s = await get("items/big-buck-bunny/streams");
  assert.match(s.data.streams[0].url, /^https:\/\/.+\.m3u8$/);
});
test("invalid paths and pages give explicit status", async () => {
  assert.equal((await get("items/not-real")).status, 404);
  assert.equal((await get("catalog?page=0")).status, 400);
});
