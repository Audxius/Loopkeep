package io.github.audxius.loopkeep.ui

import android.content.Intent
import android.os.Build
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import io.github.audxius.loopkeep.core.NoteStatus
import io.github.audxius.loopkeep.core.PostType
import io.github.audxius.loopkeep.data.VideoEntity
import kotlinx.coroutines.delay

private fun VideoEntity.playableUri(): String? = when {
    status == NoteStatus.FULL.value && type == PostType.VIDEO.value -> videoUri
    type == PostType.SLIDESHOW.value -> audioUri
    else -> null
}

/** Text over video: white with a soft shadow so it reads on any frame. */
private val overlayShadow = Shadow(Color(0x99000000), blurRadius = 8f)

@Composable
fun FeedScreen(vm: AppViewModel, startTiktokId: String?) {
    val live by vm.items.collectAsStateWithLifecycle()
    // Freeze the order while watching, so new downloads or edits don't make the pager jump.
    val order = remember { live.orEmpty().map { it.tiktokId }.toMutableStateList() }
    val byId = remember(live) { live.orEmpty().associateBy { it.tiktokId } }
    val startIndex = remember { order.indexOf(startTiktokId).coerceAtLeast(0) }
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(vm, snackbar)

    if (order.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Nothing to show", color = Color.White)
        }
        return
    }

    val context = LocalContext.current
    var muted by remember { mutableStateOf(vm.muted) }
    val pool = remember { PlayerPool(context, muted) }
    DisposableEffect(Unit) { onDispose { pool.release() } }

    val pagerState = rememberPagerState(initialPage = startIndex) { order.size }
    var editing by remember { mutableStateOf<VideoEntity?>(null) }
    var deleting by remember { mutableStateOf<VideoEntity?>(null) }
    var resumed by remember { mutableStateOf(true) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> { resumed = false; pool.pauseAll() }
                Lifecycle.Event.ON_RESUME -> resumed = true
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val current = pagerState.settledPage.coerceAtMost(order.lastIndex)
    val currentUri = byId[order[current]]?.playableUri()
    LaunchedEffect(current, currentUri, resumed, editing) {
        if (resumed && editing == null) pool.activate(current, currentUri) else pool.pauseAll()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            key = { order[it] },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = byId[order[page]]
            if (item == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Removed from library", color = Brand.TextSecondary) }
                return@VerticalPager
            }
            val uri = item.playableUri()
            LaunchedEffect(uri) { if (uri != null) pool.prepare(page, uri) }
            FeedPage(
                item = item,
                player = if (uri != null) pool.playerFor(page) else null,
                isCurrent = page == current,
                muted = muted,
                onToggleMute = { muted = !muted; pool.muted = muted; vm.muted = muted },
                onEdit = { editing = item },
                onDelete = { deleting = item },
                onRetry = { vm.retry(item) },
                onCollection = { name -> vm.setCollection(name); vm.back() },
            )
        }

        // Top scrim + controls.
        Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color(0x88000000), Color.Transparent))))
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color(0x40000000)).clickable { vm.back() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            Text(
                "${current + 1} / ${order.size}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).background(Color(0x40000000)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp))
    }

    editing?.let { e ->
        EditSheet(vm, byId[e.tiktokId] ?: e, onDismiss = { editing = null })
    }
    deleting?.let { d ->
        DeleteDialog(d, onConfirm = {
            vm.delete(d)
            order.remove(d.tiktokId)
            if (order.isEmpty()) vm.back()
        }, onDismiss = { deleting = null })
    }
}

@Composable
private fun FeedPage(
    item: VideoEntity,
    player: ExoPlayer?,
    isCurrent: Boolean,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onCollection: (String) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        when {
            item.type == PostType.SLIDESHOW.value && item.imageUriList.isNotEmpty() -> Slideshow(item)
            player != null -> VideoSurface(item, player, isCurrent)
            else -> UnavailableCard(item, onRetry)
        }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.42f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x66000000), Color(0xCC000000)))),
        )
        CaptionOverlay(item, onCollection, Modifier.align(Alignment.BottomStart).padding(end = 76.dp))
        SideActions(item, player != null, muted, onToggleMute, onEdit, onDelete, Modifier.align(Alignment.BottomEnd))
        if (player != null && item.type == PostType.VIDEO.value) {
            SeekBar(player, isCurrent, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(item: VideoEntity, player: ExoPlayer, isCurrent: Boolean) {
    var showPoster by remember(item.tiktokId) { mutableStateOf(true) }
    var playing by remember { mutableStateOf(player.playWhenReady) }
    DisposableEffect(player, item.tiktokId) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { showPoster = false }
            override fun onVideoSizeChanged(videoSize: VideoSize) { if (videoSize.width > 0) showPoster = false }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
        }
        player.addListener(listener)
        // Catch up on anything that happened before the listener was attached.
        playing = player.playWhenReady
        if (player.videoSize.width > 0) showPoster = false
        onDispose { player.removeListener(listener) }
    }
    Box(
        Modifier.fillMaxSize().pointerInput(player) {
            detectTapGestures(onTap = { player.playWhenReady = !player.playWhenReady })
        },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            update = { view -> if (view.player !== player) view.player = player },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        // TikTok's own cover as the poster frame until the first video frame is drawn.
        if (showPoster && item.coverUri != null) {
            AsyncImage(item.coverUri, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        AnimatedVisibility(
            isCurrent && !playing, enter = fadeIn() + scaleIn(initialScale = 1.3f), exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(Color(0x55000000)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, "Paused", tint = Color.White, modifier = Modifier.size(56.dp))
            }
        }
    }
}

@Composable
private fun SeekBar(player: ExoPlayer, isCurrent: Boolean, modifier: Modifier) {
    var fraction by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(player, isCurrent, dragging) {
        while (isCurrent && !dragging) {
            val d = player.duration
            fraction = if (d > 0) (player.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(80)
        }
    }
    fun seekTo(f: Float) {
        val d = player.duration
        if (d > 0) player.seekTo((f.coerceIn(0f, 1f) * d).toLong())
    }
    BoxWithConstraints(
        modifier.fillMaxWidth().height(22.dp)
            .pointerInput(player) { detectTapGestures { seekTo(it.x / size.width) } }
            .pointerInput(player) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { seekTo(fraction); dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ -> fraction = (change.position.x / size.width).coerceIn(0f, 1f) }
            },
        contentAlignment = Alignment.BottomStart,
    ) {
        val h = if (dragging) 5.dp else 2.dp
        Box(Modifier.fillMaxWidth().height(h).background(Color.White.copy(alpha = 0.2f)))
        Box(Modifier.width(maxWidth * fraction).height(h).background(Brand.GradientHorizontal))
        if (dragging) {
            Box(Modifier.padding(start = (maxWidth * fraction - 7.dp).coerceAtLeast(0.dp)).padding(bottom = 0.dp).size(14.dp).clip(CircleShape).background(Color.White))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptionOverlay(item: VideoEntity, onCollection: (String) -> Unit, modifier: Modifier) {
    var expanded by remember(item.tiktokId) { mutableStateOf(false) }
    val shadow = TextStyle(shadow = overlayShadow)
    Column(modifier.navigationBarsPadding().padding(start = 16.dp, bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val name = item.authorHandle.ifBlank { item.author }
            if (name.isNotBlank()) {
                Avatar(name, 34.dp)
                Spacer(Modifier.width(10.dp))
            }
            Column {
                Text(
                    if (item.authorHandle.isNotBlank()) "@${item.authorHandle}" else item.author.ifBlank { "Unknown creator" },
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, style = shadow, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(item.saved?.let { "Saved $it" }, item.posted?.let { "posted $it" }).joinToString(" · "),
                    color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, style = shadow,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (expanded) {
            Column(
                Modifier.heightIn(max = 340.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x99000000))
                    .clickable { expanded = false }.verticalScroll(rememberScrollState()).padding(12.dp),
            ) {
                SelectionContainer {
                    Column {
                        Text(item.caption, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
                        if (item.notes.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Outlined.Notes, null, tint = Brand.Peach, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Your notes", color = Brand.Peach, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(item.notes, color = Color.White, fontSize = 13.sp, lineHeight = 19.sp)
                        }
                    }
                }
            }
        } else if (item.caption.isNotBlank()) {
            Text(
                item.caption, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = shadow, modifier = Modifier.clickable { expanded = true },
            )
        }
        if (item.tagList.isNotEmpty() || item.collectionList.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item.collectionList.forEach { c ->
                    Row(
                        Modifier.clip(CircleShape).background(Brand.Coral.copy(alpha = 0.85f)).clickable { onCollection(c) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.CollectionsBookmark, null, tint = Color.White, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(c, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                item.tagList.take(6).forEach { t ->
                    Text(
                        "#$t", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(CircleShape).background(Color(0x40FFFFFF)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SideActions(
    item: VideoEntity,
    hasSound: Boolean,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier.navigationBarsPadding().padding(end = 10.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (hasSound) {
            SideAction(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (muted) "Muted" else "Sound", onToggleMute)
        }
        SideAction(Icons.Default.Edit, "Notes", onEdit)
        if (item.sourceUrl.isNotBlank()) {
            SideAction(Icons.AutoMirrored.Filled.OpenInNew, "Original") {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, item.sourceUrl.toUri())) }
            }
        }
        SideAction(Icons.Default.Delete, "Delete", onDelete)
    }
}

@Composable
private fun SideAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(46.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(Color(0x47000000)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, style = TextStyle(shadow = overlayShadow))
    }
}

@Composable
private fun Slideshow(item: VideoEntity) {
    val images = item.imageUriList
    val state = rememberPagerState { images.size }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(state, modifier = Modifier.fillMaxSize(), key = { images[it] }) { i ->
            AsyncImage(images[i], null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Row(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp)
                .clip(CircleShape).background(Color(0x40000000)).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(images.size) { i ->
                val active = i == state.currentPage
                Box(
                    Modifier.height(6.dp).width(if (active) 16.dp else 6.dp).clip(CircleShape)
                        .background(if (active) Color.White else Color.White.copy(alpha = 0.45f)),
                )
            }
        }
    }
}

@Composable
private fun UnavailableCard(item: VideoEntity, onRetry: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        item.coverUri?.let {
            AsyncImage(
                it, null, contentScale = ContentScale.Crop, alpha = 0.55f,
                modifier = Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(24.dp) else Modifier),
            )
        }
        Column(
            Modifier.align(Alignment.Center).padding(28.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xCC111116)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.CloudOff, null, tint = statusColor(item.status), modifier = Modifier.size(40.dp))
            StatusPill(statusLabel(item.status), statusColor(item.status))
            Text(
                when (item.status) {
                    NoteStatus.UNAVAILABLE.value -> "This post was deleted or made private on TikTok. Its note stays in your archive."
                    NoteStatus.TEXT_ONLY.value -> "The caption and cover are saved, but the video couldn't be downloaded yet."
                    else -> "The video file is missing from media/."
                },
                color = Brand.TextSecondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GradientButton("Retry", onClick = onRetry, icon = Icons.Default.Refresh)
                if (item.sourceUrl.isNotBlank()) {
                    Row(
                        Modifier.clip(CircleShape).background(Brand.Surface3)
                            .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, item.sourceUrl.toUri())) } }
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Brand.TextPrimary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("TikTok", color = Brand.TextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
