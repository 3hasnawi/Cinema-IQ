package com.cinemaplus.rebuilt.data

enum class SourceChoice(val label: String) {
    BOTH("سينمانا وVODU"), CINEMANA("سينمانا"), VODU("VODU"), DAWWIRLI("دورلي");

    fun <T> requests(cinemana: suspend () -> T, vodu: List<suspend () -> T>, daw: List<suspend () -> T> = emptyList()): List<suspend () -> T> = when(this) {
        BOTH -> listOf(cinemana) + vodu
        CINEMANA -> listOf(cinemana)
        VODU -> vodu
        DAWWIRLI -> daw
    }
    fun accepts(id: String): Boolean = when(this) {
        BOTH -> !DawwirliMapper.owns(id)
        CINEMANA -> !VoduApi.owns(id) && !DawwirliMapper.owns(id)
        VODU -> VoduApi.owns(id)
        DAWWIRLI -> DawwirliMapper.owns(id)
    }

    companion object {
        fun restore(value: String?) = entries.firstOrNull { it.name == value } ?: BOTH
    }
}
