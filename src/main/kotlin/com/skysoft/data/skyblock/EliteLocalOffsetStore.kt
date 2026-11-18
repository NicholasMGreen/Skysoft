package com.skysoft.data.skyblock

/**
 * Local progress accrued between Elite fetches, keyed by profile and board.
 *
 * Deliberately independent of the snapshot cache: a gain must be recorded even if the board has
 * never been fetched (or is not the board currently on screen). Coupling the two is what made gains
 * vanish for any board outside a hardcoded list.
 */
internal object EliteLocalOffsetStore {
    private val offsets = mutableMapOf<OffsetKey, Double>()

    /** Adds [amount] to [boardId] and returns the new total for that board. */
    fun add(profileId: String, boardId: String, amount: Double): Double {
        if (amount <= 0.0) return offsetFor(profileId, boardId)
        val key = OffsetKey(profileId, boardId)
        val updated = (offsets[key] ?: 0.0) + amount
        offsets[key] = updated
        return updated
    }

    fun offsetFor(profileId: String, boardId: String): Double = offsets[OffsetKey(profileId, boardId)] ?: 0.0

    /**
     * Reconciles a freshly fetched [current] amount against the previously cached [previous].
     *
     * Elite is the source of truth, so any server-side increase consumes the equivalent local
     * offset. A decrease means the interval rolled over (or the score was corrected), so the offset
     * is dropped rather than carried into a new period.
     *
     * When there is no [previous] baseline yet (the board's first fetch) the offset is kept: a gain
     * observed before that first response may or may not be included in it, and keeping it is the
     * only way the gain is visible immediately. If Elite had already counted it, the next fetch sees
     * an increased amount and subtracts it here, so the error corrects itself within one interval.
     */
    fun reconcile(profileId: String, boardId: String, previous: Double?, current: Double) {
        val key = OffsetKey(profileId, boardId)
        if (previous == null) return
        if (current == previous) return
        if (current < previous) {
            offsets.remove(key)
            return
        }
        val remaining = (offsetFor(profileId, boardId) - (current - previous)).coerceAtLeast(0.0)
        if (remaining > PROGRESS_EPSILON) offsets[key] = remaining else offsets.remove(key)
    }

    fun clearProfile(profileId: String) {
        offsets.keys.removeIf { key -> key.profileId == profileId }
    }

    fun clear() {
        offsets.clear()
    }

    private data class OffsetKey(val profileId: String, val boardId: String)

    private const val PROGRESS_EPSILON = 1e-6
}
