package com.sbro.emucoreh.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sbro.emucoreh.R
import com.sbro.emucoreh.data.GameDisc
import com.sbro.emucoreh.data.GameItem
import com.sbro.emucoreh.data.launchDiscs
import com.sbro.emucoreh.ui.theme.neon.neonChipShape
import com.sbro.emucoreh.ui.theme.neon.neonShape

@Composable
fun MultiDiscLaunchDialog(
    game: GameItem,
    onLaunch: (GameDisc) -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String? = null
) {
    val discs = remember(game) { game.launchDiscs() }
    var selectedPath by rememberSaveable(game.path) {
        mutableStateOf(discs.firstOrNull { it.path == game.path }?.path ?: discs.first().path)
    }
    val selectedDisc = discs.firstOrNull { it.path == selectedPath } ?: discs.first()
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_choose_disc)) },
        icon = { Icon(Icons.Rounded.Album, contentDescription = null) },
        text = {
            val contentInset = LocalDialogContentHorizontalPadding.current
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = game.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fullBleedScrollViewport(contentInset)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = contentInset)
                        .tvFocusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    discs.forEach { disc ->
                        FilterChip(
                            selected = selectedDisc.path == disc.path,
                            onClick = { selectedPath = disc.path },
                            shape = neonChipShape(),
                            label = {
                                Text(stringResource(R.string.library_disc_number, disc.number),
                                    maxLines = 1, softWrap = false)
                            }
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.library_disc_file),
                        style = MaterialTheme.typography.labelMedium)
                    Text(selectedDisc.fileName, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onLaunch(selectedDisc) }) {
                Text(confirmLabel ?: stringResource(R.string.library_launch_disc))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
internal fun GameDiscCountBadge(game: GameItem, modifier: Modifier = Modifier) {
    if (game.discs.size <= 1) return
    Surface(modifier = modifier, shape = neonShape(6.dp), color = Color.Black.copy(alpha = 0.78f)) {
        Text(
            text = stringResource(R.string.library_disc_count, game.discs.size),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            softWrap = false
        )
    }
}
