package com.cinemaplus.rebuilt.data

enum class SourceChoice(val label: String) {
    BOTH("سينمانا وVODU"), CINEMANA("سينمانا"), VODU("VODU");

    fun <T> requests(cinemana: suspend () -> T, vodu: List<suspend () -> T>): List<suspend () -> T> =
        (if (this != VODU) listOf(cinemana) else emptyList()) +
            (if (this != CINEMANA) vodu else emptyList())

    companion object {
        fun restore(value: String?) = entries.firstOrNull { it.name == value } ?: BOTH
    }
}
