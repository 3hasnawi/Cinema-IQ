package com.cinemaplus.rebuilt.ui.mobile

import com.cinemaplus.rebuilt.data.Film
import com.cinemaplus.rebuilt.data.Shelf
import com.cinemaplus.rebuilt.data.relatedFilms

/** Keep the phone entry point while sharing recommendation rules with TV. */
fun phoneRelatedFilms(film: Film, shelves: List<Shelf>): List<Film> = relatedFilms(film, shelves)
