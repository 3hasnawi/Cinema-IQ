package com.cinemaplus.rebuilt.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.cinemaplus.rebuilt.data.Film

val LocalFilmTitle = staticCompositionLocalOf<(Film) -> String> { { it.title } }

@Composable
fun mediaTitle(film: Film): String = LocalFilmTitle.current(film)
