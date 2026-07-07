package io.github.bszapp.wifitoolbox.uidefault.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

enum class Route {
    Main,
    ColorPalette,
}

class Navigator(initialRoute: Route) {
    private val stack = mutableStateListOf(initialRoute)
    val current: Route get() = stack.last()

    fun push(route: Route) {
        if (current == route) return
        stack.add(route)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun replace(route: Route) {
        stack.clear()
        stack.add(route)
    }
}

@Composable
fun rememberNavigator(startRoute: Route): Navigator = remember { Navigator(startRoute) }

val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("LocalNavigator not provided")
}
