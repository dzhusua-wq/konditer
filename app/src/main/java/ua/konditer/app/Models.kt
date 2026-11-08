package ua.konditer.app

// Моделі даних. Без залежностей від Android, щоб логіку можна було перевіряти юніт-тестами.

/** Опис одного зображення: вектор нейромережі (L2-нормований) + колірний опис. */
class Shot(val emb: FloatArray, val color: FloatArray)

/** Плоский (топер, печиво з малюнком) чи об'ємний (квітка, бантик, безе). */
enum class Kind { FLAT, VOLUME }

/**
 * Один еталон виробу: збережене фото (кроп рамки 256×256) і його вектори.
 * shots[0] — оригінал, далі — повороти на 90/180/270°, щоб виріб упізнавався під будь-яким кутом.
 */
class Sample(
    val id: String,
    val source: String,
    val createdAt: Long,
    @Volatile var shots: List<Shot> = emptyList()
) {
    companion object {
        const val SRC_CAMERA = "camera"
        const val SRC_GALLERY = "gallery"
        const val SRC_FEEDBACK = "feedback"
    }
}

class Item(
    val id: String,
    var name: String,
    var category: String = "",
    var kind: Kind = Kind.VOLUME,
    var weight: String = "",
    var article: String = "",
    var price: String = "",
    var description: String = "",
    val samples: MutableList<Sample> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis()
) {
    /** Скільки еталонів рекомендовано зняти. */
    val targetSamples: Int get() = if (kind == Kind.FLAT) 6 else 12

    /** Короткий рядок «вага · артикул · ціна» для списків. */
    fun shortInfo(): String = listOf(
        weight.takeIf { it.isNotBlank() },
        article.takeIf { it.isNotBlank() }?.let { "арт. $it" },
        price.takeIf { it.isNotBlank() }?.let { "$it грн" }
    ).filterNotNull().joinToString(" · ")
}

/** Чутливість упізнавання (налаштування). */
enum class Sensitivity(val label: String, val relLow: Float, val needConf: Float) {
    STRICT("Сувора — менше помилок, частіше «не впізнано»", 0.85f, 0.60f),
    NORMAL("Звичайна", 0.78f, 0.50f),
    SOFT("М'яка — впізнає охочіше, можливі помилки", 0.70f, 0.40f)
}
