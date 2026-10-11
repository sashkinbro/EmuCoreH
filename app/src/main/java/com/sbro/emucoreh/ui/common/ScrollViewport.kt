package com.sbro.emucoreh.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Matches the library picker: viewport reaches the card, inset belongs inside the scroll. */
internal fun Modifier.fullBleedScrollViewport(horizontalPadding: Dp): Modifier {
    if (horizontalPadding == 0.dp) return this
    return layout { measurable, constraints ->
        val sidePadding = horizontalPadding.roundToPx()
        val expanded = constraints.copy(maxWidth = if (constraints.hasBoundedWidth) {
            constraints.maxWidth + sidePadding * 2
        } else constraints.maxWidth)
        val placeable = measurable.measure(expanded)
        layout(constraints.maxWidth, placeable.height) {
            placeable.placeRelative(-sidePadding, 0)
        }
    }
}
