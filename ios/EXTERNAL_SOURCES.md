# المصادر الخارجية في Cinema iQ للآيفون

يمكن إضافة مصدر فيديو خارجي من **الإعدادات ← المصادر الخارجية ← إضافة مصدر**: اسم، وعنوان واجهة REST عبر HTTPS، ورمز وصول اختياري. يفحص التطبيق العنوان بطلب `home` قبل الحفظ، ثم يظهر المصدر في قائمة المصادر كباقي المصادر (الرئيسية، التصفح، البحث، صفحة العمل، المشغّل، قائمتي، تابع المشاهدة).

المصدر التجريبي «عروض مفتوحة (تجريبي)» مدمج في التطبيق ويستخدم أفلام Blender المفتوحة (Creative Commons Attribution) وبث HLS التجريبي من Apple.

> استخدم فقط واجهات ومحتوى تملك حق بثّه أو مصرّحًا لك باستخدامه.

## الاتصال

- كل الطلبات `GET` إلى `BASE` + المسار، مثلًا `https://media.example.com/cinema-iq/v1/home`.
- العنوان يجب أن يكون HTTPS، بلا اسم مستخدم أو كلمة مرور فيه، والرد يجب أن يأتي من نفس النطاق أو نطاقاته الفرعية.
- إن وُجد رمز وصول يُرسل `Authorization: Bearer <token>`، ويُحفظ في سلسلة المفاتيح (Keychain) فقط.
- `Accept: application/json`. حجم الرد حتى 4 ميغابايت. المهلة 20 ثانية، مع إعادة محاولة واحدة عند أخطاء 5xx.
- الرموز المعروضة للمستخدم: 401 (رمز غير صالح)، 403، 404، 429 (كثرة الطلبات)، 5xx.
- الردود تُخزَّن مؤقتًا دقيقتين وتُدمج الطلبات المتطابقة، ما عدا روابط التشغيل (`streams`) فتُطلب كل مرة لأنها قد تنتهي صلاحيتها.

## المسارات

| المسار | الوصف |
|---|---|
| `home` | رفوف الصفحة الرئيسية |
| `catalog?type=movie\|series&page=N&q=نص&category=id` | تصفح وبحث وتصنيف، الصفحات تبدأ من 1 |
| `categories` | التصنيفات (اختياري) |
| `items/{id}` | تفاصيل عمل، ومواسم المسلسل وحلقاته |
| `items/{id}/streams` | روابط التشغيل والترجمات |

### العنصر (Item)

```json
{
  "id": "m42",
  "title": "اسم العمل",
  "type": "movie",
  "enTitle": "English Title",
  "poster": "https://img.example.com/m42-poster.jpg",
  "backdrop": "https://img.example.com/m42-wide.jpg",
  "overview": "القصة",
  "year": "2024",
  "duration": "98",
  "rating": "7.4",
  "quality": "1080p"
}
```

- `id`: حروف وأرقام و `. _ ~ -` فقط، حتى 128 حرفًا. يصبح داخل التطبيق `ext:<مزوّد>:<id>` فلا يتعارض مع المصادر الأخرى.
- `type`: `movie` أو `series` أو `episode`. الحلقة تضيف `season` و`episode` و`episodeTitle` و`seriesId`.
- الصور HTTPS فقط، وأي حقل غير صالح يُتجاهل أو يُتجاهل العنصر كله.

### `home`

```json
{ "shelves": [ { "id": "latest", "title": "الأحدث", "items": [ /* Item */ ] } ] }
```

### `catalog`

```json
{ "items": [ /* Item */ ], "page": 1, "hasMore": true }
```

### `categories`

```json
{ "categories": [ { "id": "action", "title": "أكشن" } ] }
```

### `items/{id}`

```json
{
  "item": { /* Item */ },
  "seasons": [
    { "number": 1, "episodes": [ { "id": "s1e1", "episode": 1, "title": "اسم المسلسل", "episodeTitle": "البداية", "duration": "45" } ] }
  ]
}
```

الحلقة تُفتح بطلب صفحة مسلسلها، فيعرف المشغّل الحلقة التالية.

### `items/{id}/streams`

```json
{
  "streams": [
    { "url": "https://cdn.example.com/m42/master.m3u8", "quality": "تلقائي" },
    { "url": "https://cdn.example.com/m42/720.mp4", "quality": "720p" }
  ],
  "subtitles": [
    { "url": "https://cdn.example.com/m42/ar.vtt", "language": "ar", "title": "العربية" }
  ]
}
```

- يقبل المشغّل HTTPS فقط بصيغة `m3u8` (HLS) أو `mp4` / `m4v` / `mov`، وتظهر كل نسخة كخيار في «الجودة». الروابط الأخرى تُرفض.
- الترجمات `vtt` أو `srt`، حتى 2 ميغابايت.

## المزامنة

أعمال المصادر الخارجية (المفضلة، تابع المشاهدة، موضع المشاهدة) تُحفظ على الآيفون ولا تُرسل إلى حساب المزامنة، لأن تطبيقات أندرويد لا تعرف هذه المصادر.

## للمطوّرين

- Core: `ios/Core/Sources/CinemaCore/ExternalSource.swift` (`ExternalProvider`، `ExternalClient`، `ExternalParser`، `ExternalTransport`).
- التطبيق: `ios/CinemaIQ/ExternalSources.swift` (`ProviderStore`، قسم الإعدادات).
- الاختبارات: `ios/Core/Tests/CinemaCoreTests/ExternalSourceTests.swift` مع ملفات `Fixtures/external`.
