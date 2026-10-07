package com.cinemaplus.rebuilt.data

object Demo {
    val films = listOf(
        Film("demo-1", "رحلة إلى المجهول", "file:///android_asset/desert.jpg", "file:///android_asset/desert.jpg", "رحلة عبر آفاق بعيدة، حيث يبدأ اكتشاف العالم باكتشاف أنفسنا. محتوى توضيحي لتجربة التصميم.", "2026", "7680", "8.4"),
        Film("demo-2", "المدينة الأخيرة", "file:///android_asset/city.jpg", "file:///android_asset/city.jpg", "بين أضواء المدينة وظلالها، تتقاطع حكايات لم يكن مقدرًا لها أن تلتقي.", "2026", "6720", "8.1"),
        Film("demo-3", "أسرار الشمال", "file:///android_asset/north.jpg", "file:///android_asset/north.jpg", "في قرية تحيط بها الجبال، تكشف عودة غامضة أسرارًا من الماضي.", "2025", "2880", "8.7", true),
        Film("demo-4", "بين العوالم", "file:///android_asset/space.jpg", "file:///android_asset/space.jpg", "قصة افتراضية لاستكشاف تجربة العرض.", "2026", "6300", "7.9"),
        Film("demo-5", "النداء الأخير", "file:///android_asset/ocean.jpg", "file:///android_asset/ocean.jpg", "حكاية تتجاوز حدود الأفق.", "2025", "5760", "8.0"),
        Film("demo-6", "ممر الظلال", "file:///android_asset/forest.jpg", "file:///android_asset/forest.jpg", "كل طريق يحمل قصة مختلفة.", "2025", "3120", "8.2", true)
    )
    val shelves get() = listOf(Shelf("new", "أضيف حديثًا", films), Shelf("cinema", "ليلة سينمائية", films.filterNot { it.series }), Shelf("series", "مسلسلات تستحق المتابعة", films.filter { it.series }))
    fun seasons(f: Film) = (1..3).map { n -> Season(n, (1..8).map { e -> f.copy(id = "${f.id}-$n-$e", title = "الحلقة $e", season = n, episode = e, duration = "2880", series = false) }) }
}
