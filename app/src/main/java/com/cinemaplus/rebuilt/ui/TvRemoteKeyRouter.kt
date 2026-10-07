package com.cinemaplus.rebuilt.ui

/** Pure remote routing policy, independent of Android Views and Media3. */
internal class TvRemoteKeyRouter {
    enum class Key { Select, PlayPause, Play, Pause, Left, Right, Up, Down, Other }
    enum class Command { TogglePlayback, Play, Pause, SeekBack, SeekForward, Reveal }
    data class Result(val consumed: Boolean, val commands: List<Command> = emptyList())
    private var consumedPress: Key? = null

    fun route(key: Key, keyDown: Boolean, repeat: Int, controlsVisible: Boolean): Result {
        if (!keyDown) {
            if (consumedPress == key) { consumedPress = null; return Result(true) }
            return Result(false)
        }
        // A reveal changes focus before key-up. Consume the entire press to avoid a second click.
        if (consumedPress == key || repeat > 0 && key in listOf(Key.PlayPause, Key.Play, Key.Pause)) return Result(true)
        val commands = when (key) {
            Key.PlayPause -> listOf(Command.TogglePlayback, Command.Reveal)
            Key.Play -> listOf(Command.Play, Command.Reveal)
            Key.Pause -> listOf(Command.Pause, Command.Reveal)
            Key.Select -> if (!controlsVisible) listOf(Command.TogglePlayback, Command.Reveal) else emptyList()
            Key.Left -> if (!controlsVisible) listOf(Command.SeekBack, Command.Reveal) else emptyList()
            Key.Right -> if (!controlsVisible) listOf(Command.SeekForward, Command.Reveal) else emptyList()
            Key.Up, Key.Down -> if (!controlsVisible) listOf(Command.Reveal) else emptyList()
            Key.Other -> emptyList()
        }
        if (commands.isEmpty()) return Result(false)
        consumedPress = key
        return Result(true, commands)
    }
}
