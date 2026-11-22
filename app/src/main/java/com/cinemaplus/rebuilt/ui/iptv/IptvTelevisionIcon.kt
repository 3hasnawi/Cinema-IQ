package com.cinemaplus.rebuilt.ui.iptv

import androidx.compose.foundation.Canvas
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import com.cinemaplus.rebuilt.R

@Composable internal fun IptvSectionIcon(modifier: Modifier, color: Color) {
    Icon(painterResource(R.drawable.ic_live_television), contentDescription = null, modifier = modifier, tint = color)
}

@Composable internal fun IptvTelevisionIcon(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val stroke = w * .07f
        drawRoundRect(color, Offset(w * .08f, h * .12f), Size(w * .84f, h * .6f), CornerRadius(w * .09f), style = Stroke(stroke))
        drawLine(color, Offset(w * .5f, h * .74f), Offset(w * .5f, h * .88f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * .3f, h * .9f), Offset(w * .7f, h * .9f), stroke, StrokeCap.Round)
    }
}
