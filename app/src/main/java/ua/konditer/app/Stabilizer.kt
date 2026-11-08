package ua.konditer.app

/**
 * Згладжує результат між кадрами, щоб назва не «миготіла»:
 * виріб «фіксується», коли переміг у [lockVotes] з останніх [window] кадрів,
 * і відпускається, коли лишилось менше [keepVotes] перемог.
 */
class Stabilizer(
    private val window: Int = 6,
    private val lockVotes: Int = 4,
    private val keepVotes: Int = 2
) {
    private val history = ArrayDeque<String?>()
    private val confidences = ArrayDeque<Float>()

    var lockedId: String? = null
        private set

    /** Скільки останніх кадрів поспіль були «не впізнано». */
    var unknownStreak = 0
        private set

    /** Повертає true, якщо щойно зафіксувався новий виріб (для вібрації). */
    fun push(matchedId: String?, confidence: Float): Boolean {
        history.addLast(matchedId)
        if (history.size > window) history.removeFirst()
        unknownStreak = if (matchedId == null) unknownStreak + 1 else 0

        val counts = history.filterNotNull().groupingBy { it }.eachCount()
        val prev = lockedId
        if (prev != null && (counts[prev] ?: 0) < keepVotes) lockedId = null

        val leader = counts.maxByOrNull { it.value }
        if (leader != null && leader.value >= lockVotes && leader.key != lockedId) {
            lockedId = leader.key
        }
        if (lockedId != prev) confidences.clear()
        if (lockedId != null && matchedId == lockedId) {
            confidences.addLast(confidence)
            if (confidences.size > window) confidences.removeFirst()
        }
        return lockedId != null && lockedId != prev
    }

    /** Середня впевненість для зафіксованого виробу. */
    val confidence: Float get() = if (confidences.isEmpty()) 0f else confidences.average().toFloat()

    fun reset() {
        history.clear(); confidences.clear(); lockedId = null; unknownStreak = 0
    }
}
