package com.sbro.emucoreh.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sbro.emucoreh.core.EmulationPlatform
import com.sbro.emucoreh.ui.common.GameCoverArt

@Composable
internal fun LibraryCoverArt(coverPath: String?, fallbackTitle: String, modifier: Modifier = Modifier,
    platform: String? = null) {
    GameCoverArt(coverPath, fallbackTitle, modifier,
        trimTransparentPadding = EmulationPlatform.fromName(platform)?.usesPlatformTouchLayouts == true)
}
