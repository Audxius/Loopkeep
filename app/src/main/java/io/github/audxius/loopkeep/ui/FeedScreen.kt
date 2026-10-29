package io.github.audxius.loopkeep.ui

import android.content.Intent
import androidx.core.net.toUri
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Removed from library", color = Color.White) }
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
            )
        }

        IconButton(onClick = { vm.back() }, modifier = Modifier.statusBarsPadding().padding(4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
        }
        Text(
            "${current + 1} / ${order.size}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 48.dp))
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
) {
    Box(Modifier.fillMaxSize()) {
        when {
            item.type == PostType.SLIDESHOW.value && item.imageUriList.isNotEmpty() -> Slideshow(item)
            player != null -> VideoSurface(item, player, isCurrent)
            else -> TextOnlyCard(item, onRetry)
        }
        // Readability gradient behind the caption.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.35f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB0000000)))),
        )
        CaptionOverlay(item, Modifier.align(Alignment.BottomStart).padding(end = 72.dp))
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
        AnimatedVisibility(isCurrent && !playing, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            Icon(Icons.Default.PlayArrow, "Paused", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(72.dp))
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
            delay(100)
        }
    }
    fun seekTo(f: Float) {
        val d = player.duration
        if (d > 0) player.seekTo((f.coerceIn(0f, 1f) * d).toLong())
    }
    BoxWithConstraints(
        modifier.fillMaxWidth().height(20.dp)
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
        val h = if (dragging) 4.dp else 2.dp
        Box(Modifier.fillMaxWidth().height(h).background(Color.White.copy(alpha = 0.25f)))
        Box(Modifier.width(maxWidth * fraction).height(h).background(Color.White))
    }
}

@Composable
private fun CaptionOverlay(item: VideoEntity, modifier: Modifier) {
    var expanded by remember(item.tiktokId) { mutableStateOf(false) }
    Column(modifier.navigationBarsPadding().padding(start = 14.dp, bottom = 24.dp)) {
        val handle = item.authorHandle.ifBlank { null }?.let { "@$it" }
        Text(
            listOfNotNull(handle ?: item.author.ifBlank { null }, item.saved?.let { "saved $it" }).joinToString(" · "),
            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
        )
        Spacer(Modifier.height(4.dp))
        if (expanded) {
            Box(
                Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                    .clickable { expanded = false },
            ) {
                SelectionContainer {
                    Column {
                        Text(item.caption, color = Color.White, fontSize = 14.sp)
                        if (item.tagList.isNotEmpty()) {
                            Text(item.tagList.joinToString(" ") { "#$it" }, color = Color(0xFF9EE7E4), fontSize = 13.sp)
                        }
                        if (item.notes.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Notes", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(item.notes, color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        } else if (item.caption.isNotBlank()) {
            Text(
                item.caption, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = true },
            )
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
        modifier.navigationBarsPadding().padding(end = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (hasSound) {
            RoundAction(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (muted) "Unmute" else "Mute", onToggleMute)
        }
        RoundAction(Icons.Default.Edit, "Tags and notes", onEdit)
        if (item.sourceUrl.isNotBlank()) {
            RoundAction(Icons.AutoMirrored.Filled.OpenInNew, "Open original") {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, item.sourceUrl.toUri())) }
            }
        }
        RoundAction(Icons.Default.Delete, "Delete", onDelete)
    }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.clip(CircleShape).background(Color(0x55000000))) {
        Icon(icon, label, tint = Color.White)
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
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(images.size) { i ->
                Box(
                    Modifier.size(6.dp).clip(CircleShape)
                        .background(if (i == state.currentPage) Color.White else Color.White.copy(alpha = 0.4f)),
                )
            }
        }
    }
}

@Composable
private fun TextOnlyCard(item: VideoEntity, onRetry: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        item.coverUri?.let {
            AsyncImage(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(), alpha = 0.5f)
        }
        Column(
            Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                when (item.status) {
                    NoteStatus.UNAVAILABLE.value -> "This post is no longer available on TikTok"
                    NoteStatus.TEXT_ONLY.value -> "Only the caption was saved"
                    else -> "Video file missing from media/"
                },
                color = Color.White, fontWeight = FontWeight.Bold,
            )
            if (item.sourceUrl.isNotBlank()) {
                Button(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, item.sourceUrl.toUri())) } }) {
                    Text("Open on TikTok")
                }
            }
            OutlinedButton(onClick = onRetry) { Text("Retry download", color = Color.White) }
        }
    }
}
