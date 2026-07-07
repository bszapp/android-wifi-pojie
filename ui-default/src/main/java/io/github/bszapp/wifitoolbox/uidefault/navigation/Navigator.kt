package io.github.bszapp.wifitoolbox.uidefault.navigation

import android.os.Parcelable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavKey
import kotlinx.parcelize.Parcelize

sealed interface Route : NavKey, Parcelable {
    @Parcelize
    data object Main : Route

    @Parcelize
    data object ColorPalette : Route
}

class Navigator(initialKey: NavKey) {
    val backStack: SnapshotStateList<NavKey> = mutableStateListOf(initialKey)

    fun push(key: NavKey) {
        backStack.add(key)
    }

    fun replace(key: NavKey) {
        if (backStack.isNotEmpty()) {
            backStack[backStack.lastIndex] = key
        } else {
            backStack.add(key)
        }
    }

    fun pop() {
        backStack.removeLastOrNull()
    }

    fun current(): NavKey? = backStack.lastOrNull()

    fun backStackSize(): Int = backStack.size

    companion object {
        val Saver: Saver<Navigator, Any> = listSaver(
            save = { navigator -> navigator.backStack.toList() },
            restore = { savedList ->
                val initialKey = savedList.firstOrNull() ?: Route.Main
                Navigator(initialKey).apply {
                    backStack.clear()
                    backStack.addAll(savedList)
                }
            },
        )
    }
}

@Composable
fun rememberNavigator(startRoute: NavKey): Navigator = rememberSaveable(startRoute, saver = Navigator.Saver) {
    Navigator(startRoute)
}

val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("LocalNavigator not provided")
}
