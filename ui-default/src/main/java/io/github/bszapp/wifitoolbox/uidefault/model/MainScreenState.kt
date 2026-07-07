package io.github.bszapp.wifitoolbox.uidefault.model

import androidx.compose.foundation.pager.PagerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class MainScreenState(
    val pagerState: PagerState,
    private val coroutineScope: CoroutineScope,
) {
    val selectedPage: Int get() = pagerState.currentPage

    fun animateToPage(index: Int) {
        coroutineScope.launch { pagerState.animateScrollToPage(index) }
    }
}
