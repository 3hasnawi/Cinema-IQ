package com.cinemaplus.rebuilt.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.cinemaplus.rebuilt.data.Profile
import java.io.File

/** Resolution-independent built-in portraits also serve as the fallback for missing imported images. */
@Composable
fun ProfileAvatar(profile: Profile, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(Color(profile.color))) {
        val variant = profile.avatar.removePrefix("avatar-").toIntOrNull()?.coerceIn(0, 5) ?: 0
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val skin = listOf(0xFFFFDCB7, 0xFFD69A70, 0xFFF5CBA9, 0xFFAE7652, 0xFFFFDCB7, 0xFFEAB68B)[variant]
            drawCircle(Color.White.copy(alpha = .12f), w * .45f, Offset(w * .70f, h * .22f))
            drawOval(Color(0xFF233446), Offset(w * .15f, h * .73f), androidx.compose.ui.geometry.Size(w * .70f, h * .60f))
            drawRoundRect(Color(0xFF2B2230), Offset(w * .24f, h * .17f), androidx.compose.ui.geometry.Size(w * .52f, h * .59f), androidx.compose.ui.geometry.CornerRadius(w * .20f))
            drawOval(Color(skin), Offset(w * .28f, h * .25f), androidx.compose.ui.geometry.Size(w * .44f, h * .51f))
            if(variant % 2 == 0) drawArc(Color(0xFF2B2230), 180f, 180f, true, Offset(w * .25f, h * .13f), androidx.compose.ui.geometry.Size(w * .50f, h * .38f))
            else drawRoundRect(Color(0xFF2B2230), Offset(w * .27f, h * .18f), androidx.compose.ui.geometry.Size(w * .46f, h * .18f), androidx.compose.ui.geometry.CornerRadius(w * .06f))
            val eye = Color(0xFF29212B)
            drawCircle(eye, w * .025f, Offset(w * .41f, h * .48f)); drawCircle(eye, w * .025f, Offset(w * .59f, h * .48f))
            if(variant == 1 || variant == 4) {
                drawCircle(eye, w * .09f, Offset(w * .40f, h * .48f), style = Stroke(w * .025f))
                drawCircle(eye, w * .09f, Offset(w * .60f, h * .48f), style = Stroke(w * .025f))
                drawLine(eye, Offset(w * .49f, h * .48f), Offset(w * .51f, h * .48f), w * .025f)
            }
            drawArc(Color(0xFF9A4C45), 10f, 160f, false, Offset(w * .43f, h * .57f), androidx.compose.ui.geometry.Size(w * .14f, h * .09f), style = Stroke(w * .022f, cap = StrokeCap.Round))
            if(variant == 2 || variant == 5) drawRoundRect(Color(0xFF345B72), Offset(w * .23f, h * .18f), androidx.compose.ui.geometry.Size(w * .56f, h * .15f), androidx.compose.ui.geometry.CornerRadius(w * .04f))
        }
        if(!profile.avatar.startsWith("avatar-")) AsyncImage(File(profile.avatar), profile.name,
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
