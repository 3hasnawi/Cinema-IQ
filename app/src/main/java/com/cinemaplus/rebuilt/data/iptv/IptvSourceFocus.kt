package com.cinemaplus.rebuilt.data.iptv

enum class IptvSourceControl { Type, Name, Address, User, Password, Output, Http, Connect, Cancel }

/** Hidden Xtream controls and a disabled Connect button must never become focus destinations. */
object IptvSourceFocus {
    fun order(kind: IptvKind, canConnect: Boolean): List<IptvSourceControl> = buildList {
        addAll(listOf(IptvSourceControl.Type, IptvSourceControl.Name, IptvSourceControl.Address))
        if (kind == IptvKind.Xtream) addAll(listOf(IptvSourceControl.User, IptvSourceControl.Password, IptvSourceControl.Output))
        add(IptvSourceControl.Http)
        if (canConnect) add(IptvSourceControl.Connect)
        add(IptvSourceControl.Cancel)
    }
    fun move(from: IptvSourceControl, step: Int, kind: IptvKind, canConnect: Boolean, wrap: Boolean = false): IptvSourceControl {
        val controls = order(kind, canConnect)
        val index = controls.indexOf(from).takeIf { it >= 0 } ?: 0
        val next = if (wrap) Math.floorMod(index + step, controls.size) else (index + step).coerceIn(0, controls.lastIndex)
        return controls[next]
    }
}
