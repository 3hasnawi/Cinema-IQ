package com.cinemaplus.rebuilt.ui.mobile

import android.content.Context
import android.content.res.Configuration

/** Hardware mode, not screen width, chooses remote versus touch navigation. */
fun usesTelevisionInterface(context: Context): Boolean =
    context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
