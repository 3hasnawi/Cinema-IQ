# Cinema IQ — external API smoke test

This is a **separate, deployable REST API** for the iPhone app's external provider feature. It is not a video proxy and uses only openly licensed/demo video sources.

## Deploy (Cloudflare Workers)

1. Create/sign in to a Cloudflare account.
2. On a computer with Node.js installed, from this directory run `npm install`.
3. Run `npm test` to verify the JSON endpoints.
4. Run `npx wrangler login`, then `npm run deploy`.
5. Wrangler prints your HTTPS URL, typically `https://cinema-iq-external-demo.<your-subdomain>.workers.dev`. Do not enter the angle-bracket example literally.

## iPhone

In Cinema IQ: **الإعدادات ← المصادر الخارجية ← إضافة مصدر**.
- Name: `Cinema IQ API Test`
- API URL: the HTTPS Workers URL from the deployment, **without** `/home`
- Token: leave blank
- Tap **فحص وإضافة**. Pick the source and test catalog, search and playback.

## Contract

- `GET /home` — shelves
- `GET /catalog?type=movie&page=1&q=...` — catalog, 2 items per page
- `GET /categories` — categories
- `GET /items/{id}` — detail
- `GET /items/{id}/streams` — direct publicly accessible test MP4/MOV/HLS, no retransmission

The endpoint accepts optional deployment prefixes (for example `/cinema-iq/v1/home`). Items use fixed URL-safe identifiers. All playback addresses are HTTPS and end with formats supported by the app. A missing title returns 404. Playback URLs are returned on demand.

## Boundaries

This sample does not integrate MissAV or any adult website, and does not grant any rights to third-party catalogs. Direct film/media URLs can disappear, so test playback on the target iPhone. Production versions should add authentication, per-provider quotas, access logging with privacy protections, monitoring and proper licensing verification.
