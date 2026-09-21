package com.sbro.emucoreh.ui.achievements

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.sbro.emucoreh.R
import com.sbro.emucoreh.data.AchievementItem
import com.sbro.emucoreh.data.RetroAchievementsGameData
import com.sbro.emucoreh.data.RetroAchievementsLibraryGame
import com.sbro.emucoreh.data.RetroAchievementsState
import com.sbro.emucoreh.ui.common.GameCoverArt
import com.sbro.emucoreh.ui.common.GameCoverAspectRatio
import com.sbro.emucoreh.ui.common.ScreenTopBar
import com.sbro.emucoreh.ui.common.appScreenTopPadding
import com.sbro.emucoreh.ui.common.navigationBarsHorizontalPaddingValues
import com.sbro.emucoreh.ui.settings.SettingsSection
import com.sbro.emucoreh.ui.settings.ToggleItem
import com.sbro.emucoreh.ui.theme.ScreenHorizontalPadding
import com.sbro.emucoreh.ui.theme.neon.neonShape
import java.io.File

@Composable
fun AchievementsScreen(
    onBackClick: () -> Unit,
    viewModel: AchievementsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val ready by viewModel.ready.collectAsState()
    val libraryGames by viewModel.libraryGames.collectAsState()
    val libraryError by viewModel.libraryError.collectAsState()
    val libraryLoading by viewModel.libraryLoading.collectAsState()
    val selectedGame by viewModel.selectedGame.collectAsState()
    val gameData by viewModel.gameData.collectAsState()
    val gameDataLoading by viewModel.gameDataLoading.collectAsState()
    val topInset = appScreenTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val horizontalSystemBarPadding = navigationBarsHorizontalPaddingValues()
    // Kept outside the detail branch so returning from a game restores the
    // exact scroll position of the hub list.
    val hubListState = rememberLazyListState()

    selectedGame?.let { game ->
        // The system gesture must close the game details, not the whole screen.
        BackHandler { viewModel.closeGame() }
        GameAchievementsDetail(
            game = game,
            data = gameData,
            loading = gameDataLoading,
            topInset = topInset,
            bottomInset = bottomInset,
            horizontalSystemBarPadding = horizontalSystemBarPadding,
            onBackClick = viewModel::closeGame
        )
        return
    }

    LazyColumn(
        state = hubListState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontalSystemBarPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(
            start = ScreenHorizontalPadding,
            end = ScreenHorizontalPadding,
            top = 0.dp,
            bottom = 24.dp + bottomInset
        )
    ) {
        item {
            ScreenTopBar(
                title = stringResource(R.string.achievements_title),
                onBackClick = onBackClick,
                modifier = Modifier.padding(top = topInset, bottom = 4.dp)
            )
        }

        if (!ready) {
            item { AchievementsSkeleton() }
            return@LazyColumn
        }

        if (!state.available) {
            item { NoticeCard(text = stringResource(R.string.achievements_unavailable)) }
            return@LazyColumn
        }

        item {
            AccountSection(
                state = state,
                onLogin = viewModel::login,
                onLogout = viewModel::logout
            )
        }

        item {
            SettingsSection(title = stringResource(R.string.achievements_options)) {
                ToggleItem(
                    icon = Icons.Rounded.EmojiEvents,
                    title = stringResource(R.string.achievements_enable),
                    subtitle = stringResource(R.string.achievements_enable_desc),
                    checked = state.enabled,
                    onCheckedChange = viewModel::setEnabled
                )
                ToggleItem(
                    icon = Icons.Rounded.SportsEsports,
                    title = stringResource(R.string.achievements_hardcore),
                    subtitle = stringResource(R.string.achievements_hardcore_desc),
                    checked = state.hardcore,
                    onCheckedChange = viewModel::setHardcore,
                    enabled = state.enabled
                )
                ToggleItem(
                    icon = Icons.Rounded.EmojiEvents,
                    title = stringResource(R.string.achievements_unofficial),
                    subtitle = stringResource(R.string.achievements_unofficial_desc),
                    checked = state.unofficial,
                    onCheckedChange = viewModel::setUnofficial,
                    enabled = state.enabled
                )
                ToggleItem(
                    icon = Icons.Rounded.EmojiEvents,
                    title = stringResource(R.string.achievements_encore),
                    subtitle = stringResource(R.string.achievements_encore_desc),
                    checked = state.encore,
                    onCheckedChange = viewModel::setEncore,
                    enabled = state.enabled
                )
            }
        }

        if (state.unsupportedImage) {
            item { NoticeCard(text = stringResource(R.string.achievements_unsupported_image)) }
        }
        if (state.imageReadError) {
            item { NoticeCard(text = stringResource(R.string.achievements_game_unavailable), isError = true) }
        }
        state.lastError?.takeUnless { state.unsupportedImage || state.imageReadError }?.let { error ->
            item { NoticeCard(text = stringResource(R.string.achievements_error, error), isError = true) }
        }

        if (!state.enabled) {
            item { NoticeCard(text = stringResource(R.string.achievements_disabled)) }
            return@LazyColumn
        }

        if (!state.loggedIn) {
            item { NoticeCard(text = stringResource(R.string.achievements_not_signed_in)) }
            return@LazyColumn
        }

        item {
            SectionHeader(text = stringResource(R.string.achievements_library_header))
        }

        if (libraryError) {
            item {
                NoticeCard(text = stringResource(R.string.achievements_library_error), isError = true)
                TextButton(onClick = { viewModel.refreshLibrary(force = true) }) {
                    Text(stringResource(R.string.hub_retry))
                }
            }
        }
        when {
            libraryGames.isEmpty() && libraryError -> Unit
            libraryGames.isEmpty() && libraryLoading -> {
                item { LibrarySkeleton() }
            }

            libraryGames.isEmpty() -> {
                item { NoticeCard(text = stringResource(R.string.achievements_library_empty)) }
            }

            else -> {
                items(libraryGames, key = { it.path.ifBlank { "id:${it.gameId}" } }) { game ->
                    LibraryGameCard(game = game, onClick = { viewModel.openGame(game) })
                }
            }
        }
    }
}

@Composable
private fun GameAchievementsDetail(
    game: RetroAchievementsLibraryGame,
    data: RetroAchievementsGameData?,
    loading: Boolean,
    topInset: androidx.compose.ui.unit.Dp,
    bottomInset: androidx.compose.ui.unit.Dp,
    horizontalSystemBarPadding: PaddingValues,
    onBackClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontalSystemBarPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(
            start = ScreenHorizontalPadding,
            end = ScreenHorizontalPadding,
            top = 0.dp,
            bottom = 24.dp + bottomInset
        )
    ) {
        item {
            ScreenTopBar(
                title = game.title,
                subtitle = game.serial?.takeIf { it.isNotBlank() },
                onBackClick = onBackClick,
                modifier = Modifier.padding(top = topInset, bottom = 4.dp)
            )
        }

        if (loading && data == null) {
            item { GameDetailSkeleton() }
            return@LazyColumn
        }

        if (data == null) {
            item { NoticeCard(text = stringResource(R.string.achievements_game_unavailable), isError = true) }
            return@LazyColumn
        }

        item {
            SummaryRow(
                firstValue = "${data.earnedCount}/${data.totalCount}",
                firstLabel = stringResource(R.string.achievements_title),
                secondValue = "${data.earnedPoints}/${data.totalPoints}",
                secondLabel = stringResource(R.string.achievements_points_label)
            )
        }

        if (data.totalCount > 0) {
            item {
                LinearProgressIndicator(
                    progress = { data.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                )
            }
        }

        val visibleAchievements = data.achievements.filterNot { it.isWarning }
        if (visibleAchievements.isEmpty()) {
            item { NoticeCard(text = stringResource(R.string.achievements_none)) }
            return@LazyColumn
        }

        val unlocked = visibleAchievements.filter { it.unlocked }
        val locked = visibleAchievements.filterNot { it.unlocked }
        if (unlocked.isNotEmpty()) {
            item { SectionHeader(text = stringResource(R.string.achievements_unlocked_header)) }
            items(unlocked, key = { it.id }) { item -> AchievementCard(item = item) }
        }
        if (locked.isNotEmpty()) {
            item { SectionHeader(text = stringResource(R.string.achievements_locked_header)) }
            items(locked, key = { it.id }) { item -> AchievementCard(item = item) }
        }
    }
}

@Composable
private fun AccountSection(
    state: RetroAchievementsState,
    onLogin: (String, String) -> Unit,
    onLogout: () -> Unit
) {
    SettingsSection(title = stringResource(R.string.achievements_account)) {
        if (state.loggedIn) {
            val user = state.user
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenHorizontalPadding),
                shape = neonShape(21.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (!user?.avatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = user?.avatarUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Person, contentDescription = null)
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = user?.displayName?.takeIf { it.isNotBlank() }
                                ?: user?.username.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(
                                R.string.achievements_score,
                                if (state.hardcore) {
                                    user?.score ?: 0
                                } else {
                                    user?.softcoreScore ?: 0
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onLogout) {
                        Icon(Icons.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.achievements_sign_out))
                    }
                }
            }
        } else {
            var username by rememberSaveable { mutableStateOf("") }
            var password by rememberSaveable { mutableStateOf("") }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenHorizontalPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.achievements_username)) },
                    singleLine = true,
                    shape = neonShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.achievements_password)) },
                    singleLine = true,
                    shape = neonShape(18.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { onLogin(username.trim(), password) },
                    enabled = username.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.achievements_sign_in))
                }
            }
        }
    }
}

@Composable
private fun LibraryGameCard(game: RetroAchievementsLibraryGame, onClick: () -> Unit) {
    val coverPath = game.coverArtPath?.takeIf { File(it).isFile }
        ?: game.imageUrl.takeIf { it.isNotBlank() }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding),
        shape = neonShape(23.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GameCoverArt(
                coverPath = coverPath,
                fallbackTitle = game.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(120.dp)
                    .aspectRatio(GameCoverAspectRatio)
                    .clip(neonShape(12.dp))
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = game.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                game.serial?.takeIf { it.isNotBlank() }?.let { serial ->
                    Text(
                        text = serial,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniBadge(text = "${game.earned}/${game.total}", highlighted = game.earned > 0)
                }
                LinearProgressIndicator(
                    progress = { game.progressFraction },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Icon(
                imageVector = Icons.Rounded.EmojiEvents,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun AchievementCard(item: AchievementItem) {
    val borderColor = if (item.unlocked) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding),
        shape = neonShape(23.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val badge = if (item.unlocked) {
                item.badgeUrl.takeIf { it.isNotBlank() } ?: item.badgeLockedUrl
            } else {
                item.badgeLockedUrl.takeIf { it.isNotBlank() } ?: item.badgeUrl
            }
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(neonShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                if (badge.isNotBlank()) {
                    AsyncImage(
                        model = badge,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(68.dp)
                            .clip(neonShape(18.dp))
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.EmojiEvents,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = if (item.unlocked) 1f else 0.4f)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (item.unlocked) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.description.isNotBlank()) {
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (item.unlocked) 1f else 0.7f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!item.unlocked && item.progressText.isNotBlank()) {
                    Text(
                        text = item.progressText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MiniBadge(
                        text = stringResource(R.string.achievements_points_badge, item.points),
                        highlighted = item.unlocked
                    )
                    MiniBadge(
                        text = stringResource(
                            if (item.unlocked) R.string.achievements_unlocked_header
                            else R.string.achievements_locked_header
                        ),
                        highlighted = item.unlocked
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(
    firstValue: String,
    firstLabel: String,
    secondValue: String,
    secondLabel: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryCard(value = firstValue, label = firstLabel, modifier = Modifier.weight(1f))
        SummaryCard(value = secondValue, label = secondLabel, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SummaryCard(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = neonShape(21.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MiniBadge(text: String, highlighted: Boolean) {
    Surface(
        shape = neonShape(8.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        }
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (highlighted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(
            start = ScreenHorizontalPadding,
            end = ScreenHorizontalPadding,
            top = 4.dp
        )
    )
}

@Composable
private fun NoticeCard(text: String, isError: Boolean = false) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding),
        shape = neonShape(19.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        border = BorderStroke(
            1.dp,
            if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun skeletonAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "achievements_skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.09f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "achievements_skeleton_alpha"
    )
    return alpha
}

@Composable
private fun SkeletonBox(modifier: Modifier, alpha: Float, shape: androidx.compose.ui.graphics.Shape = neonShape(7.dp)) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
    )
}

@Composable
private fun SkeletonCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = neonShape(21.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        content()
    }
}

private fun Modifier.skeletonCardInsets(): Modifier =
    this.fillMaxWidth().padding(horizontal = ScreenHorizontalPadding)

@Composable
private fun AchievementsSkeleton() {
    val alpha = skeletonAlpha()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SkeletonCard(Modifier.skeletonCardInsets()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SkeletonBox(Modifier.fillMaxWidth(0.55f).height(16.dp), alpha)
                    SkeletonBox(Modifier.fillMaxWidth(0.32f).height(11.dp), alpha)
                }
                SkeletonBox(Modifier.width(72.dp).height(30.dp), alpha, neonShape(10.dp))
            }
        }
        SkeletonCard(Modifier.skeletonCardInsets()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SkeletonBox(Modifier.fillMaxWidth(0.45f).height(18.dp), alpha)
                repeat(3) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        SkeletonBox(Modifier.size(40.dp), alpha, neonShape(12.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SkeletonBox(Modifier.fillMaxWidth(0.5f).height(14.dp), alpha)
                            SkeletonBox(Modifier.fillMaxWidth(0.78f).height(11.dp), alpha)
                        }
                        SkeletonBox(Modifier.width(44.dp).height(24.dp), alpha, neonShape(8.dp))
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .padding(horizontal = ScreenHorizontalPadding)
                .width(150.dp)
                .height(17.dp)
        ) {
            SkeletonBox(Modifier.fillMaxSize(), alpha)
        }
        repeat(3) {
            LibraryGameSkeletonCard(alpha)
        }
    }
}

@Composable
private fun LibraryGameSkeletonCard(alpha: Float) {
    SkeletonCard(Modifier.skeletonCardInsets()) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SkeletonBox(Modifier.width(120.dp).aspectRatio(GameCoverAspectRatio), alpha, neonShape(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SkeletonBox(Modifier.fillMaxWidth(0.6f).height(15.dp), alpha)
                SkeletonBox(Modifier.fillMaxWidth(0.28f).height(11.dp), alpha)
                SkeletonBox(Modifier.fillMaxWidth().height(10.dp), alpha, neonShape(5.dp))
            }
            SkeletonBox(Modifier.size(20.dp), alpha, neonShape(6.dp))
        }
    }
}

@Composable
private fun LibrarySkeleton() {
    val alpha = skeletonAlpha()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(3) {
            LibraryGameSkeletonCard(alpha)
        }
    }
}

@Composable
private fun GameDetailSkeleton() {
    val alpha = skeletonAlpha()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SkeletonCard(Modifier.weight(1f)) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBox(Modifier.fillMaxWidth(0.5f).height(22.dp), alpha)
                    SkeletonBox(Modifier.fillMaxWidth(0.7f).height(11.dp), alpha)
                }
            }
            SkeletonCard(Modifier.weight(1f)) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBox(Modifier.fillMaxWidth(0.5f).height(22.dp), alpha)
                    SkeletonBox(Modifier.fillMaxWidth(0.7f).height(11.dp), alpha)
                }
            }
        }
        repeat(4) {
            SkeletonCard(Modifier.skeletonCardInsets()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SkeletonBox(Modifier.size(68.dp), alpha, neonShape(18.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SkeletonBox(Modifier.fillMaxWidth(0.55f).height(15.dp), alpha)
                        SkeletonBox(Modifier.fillMaxWidth(0.9f).height(11.dp), alpha)
                        SkeletonBox(Modifier.fillMaxWidth(0.4f).height(11.dp), alpha)
                    }
                }
            }
        }
    }
}
