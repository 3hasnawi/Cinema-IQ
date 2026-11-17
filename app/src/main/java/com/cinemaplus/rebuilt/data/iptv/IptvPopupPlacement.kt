package com.cinemaplus.rebuilt.data.iptv

data class IptvPopupPlacement(val x: Int, val y: Int, val pivotX: Float)

object IptvPopupPosition {
    fun above(windowWidth: Int, windowHeight: Int, anchorLeft: Int, anchorTop: Int, anchorRight: Int,
        popupWidth: Int, popupHeight: Int, margin: Int, gap: Int): IptvPopupPlacement {
        val center = if (anchorRight > anchorLeft) (anchorLeft.toLong() + anchorRight) / 2 else windowWidth / 2L
        val x = (center - popupWidth / 2).toInt().coerceIn(margin, maxOf(margin, windowWidth - popupWidth - margin))
        val y = (anchorTop - popupHeight - gap).coerceIn(margin, maxOf(margin, windowHeight - popupHeight - margin))
        return IptvPopupPlacement(x, y, ((center - x).toFloat() / popupWidth.coerceAtLeast(1)).coerceIn(.08f, .92f))
    }
}
