package com.cinemaplus.rebuilt.data

/** Playback speed steps shared by the TV and phone players, plus the next-episode countdown length. */
object PlaybackSpeed {
    val steps = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    const val AUTO_NEXT_SECONDS = 8

    /** The step after [current]; an unknown value restarts from normal speed, and the last step wraps to the first. */
    fun next(current: Float): Float {
        val index = steps.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
        return if (index < 0) 1f else steps[(index + 1) % steps.size]
    }

    /** Arabic-friendly label such as "1.25×" or "1×". */
    fun label(speed: Float): String {
        val text = if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString().trimEnd('0')
        return "$text×"
    }
}
