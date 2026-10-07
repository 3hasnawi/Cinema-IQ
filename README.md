# Cinema iQ — Android TV

السورس الخاص لتطبيق Cinema iQ. مبني على النسخة المعتمدة 4.2، مع أيقونة CiQ متجهة وإعداد التحديثات من GitHub.

- المصدر الخاص: https://github.com/3hasnawi/Cinema-IQ
- الإصدارات العامة: https://github.com/3hasnawi/Cinema-IQ-Releases
- الحزمة: `com.cinemaplus.rebuilt`
- الواجهة: Kotlin / Jetpack Compose، العربية وRTL وخط Cairo.
- دعم الهاتف والتلفزيون قيد المراجعة: واجهتان حسب نوع الجهاز، مع نفس البيانات والحزمة. راجع `docs/MOBILE-AND-TV.md`.

## التحديثات

يُفحص أحدث إصدار عام عند دخول التطبيق للمقدمة، مع مهلة ست ساعات بين الفحوص، ويمكن الفحص يدوياً من الإعدادات. لا يبدأ التنزيل إلا بأمر المستخدم. يتحقق التطبيق من SHA-256، رقم الإصدار، الحزمة وتوقيع APK قبل فتح شاشة التثبيت.

النسخ السابقة، بما فيها 4.2، لا تحتوي نظام التحديث الجديد؛ يجب تثبيت أول نسخة تتضمنه مرة واحدة. لا توجد حتى الآن أي نسخة جديدة منشورة من هذا الإعداد.

## البناء والنشر بأمر المالك فقط

التدفق `.github/workflows/release.yml` يستخدم `workflow_dispatch` فقط، ولا يعمل عند رفع تعديلات أو tags. كتابة `RELEASE` عند تشغيله تعني موافقة صريحة على البناء. خيار `publish` يحدد النشر إلى المستودع العام.

قبل أي إصدار، زد `versionCode` في `app/build.gradle.kts` وعدّل `versionName`. لا تنشر ملفاً بتوقيع مختلف؛ مستخدمو النسخ السابقة يحتاجون المفتاح نفسه.

### GitHub Actions Secrets

أضف هذه القيم في Settings → Secrets and variables → Actions للمستودع الخاص:

| الاسم | الاستخدام |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | ملف التوقيع السابق، مشفّر Base64 |
| `SIGNING_STORE_PASSWORD` | كلمة مرور ملف التوقيع |
| `SIGNING_KEY_PASSWORD` | كلمة مرور المفتاح |
| `SIGNING_KEY_ALIAS` | اسم المفتاح |
| `TMDB_API_KEY` | مفتاح TMDB الخاص بالمالك |
| `YOUTUBE_API_KEY` | اختياري |
| `RELEASES_TOKEN` | للنشر فقط: Fine-grained PAT بصلاحية Contents: Read and write على Cinema-IQ-Releases وحده |

لا تضع القيم داخل الملفات أو سجل Git. ملف `local.properties` ومفاتيح التوقيع مستبعدة بواسطة `.gitignore`.

### بديل النشر اليدوي

عند بناء وتوقيع APK بموافقة المالك، أنشئ معلومات التحديث من الملف الحقيقي:

```bash
python scripts/release_metadata.py --apk Cinema-iQ-release.apk --aapt /path/to/aapt --tag v4.3 --output update.json
```

ثم ارفع APK و`update.json` إلى نفس Release في المستودع العام. الإصدار في المثال يجب أن يطابق رقم APK الفعلي. لا ترفع السورس أو مفاتيح الخدمات إلى المستودع العام.

## أدوات التطوير

JDK 17، Android SDK 36، Build Tools 36.0.0، Gradle 8.13 عبر wrapper. للتحقق دون إنتاج APK:

```bash
./gradlew :app:testReleaseUnitTest
```

تجربة المصادر، تسجيل الدخول والفيديو تتطلب وصول الجهاز إلى شبكات المصدر. تجارب الوحدة المحلية لا تثبت توفر الخدمة على شبكة أخرى.
