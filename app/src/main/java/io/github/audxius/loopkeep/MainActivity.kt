package io.github.audxius.loopkeep

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.audxius.loopkeep.ui.AppViewModel
import io.github.audxius.loopkeep.ui.FeedScreen
import io.github.audxius.loopkeep.ui.LibraryScreen
import io.github.audxius.loopkeep.ui.LoopkeepTheme
import io.github.audxius.loopkeep.ui.QueueScreen
import io.github.audxius.loopkeep.ui.Screen
import io.github.audxius.loopkeep.ui.SettingsScreen
import io.github.audxius.loopkeep.ui.SetupScreen

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent { LoopkeepTheme { Root(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_QUEUE, false) == true) vm.open(Screen.Queue)
        intent?.getStringExtra(EXTRA_MESSAGE)?.let { vm.showMessage(it) }
    }

    companion object {
        const val EXTRA_OPEN_QUEUE = "open_queue"
        const val EXTRA_MESSAGE = "message"
    }
}

@Composable
private fun Root(vm: AppViewModel) {
    val folder by vm.folder.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()

    // Downloads report progress in a notification; ask once (Android 13+).
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    if (folder == null) {
        SetupScreen(vm)
        return
    }
    BackHandler(enabled = screen != Screen.Library) { vm.back() }
    when (val s = screen) {
        Screen.Library -> LibraryScreen(vm)
        is Screen.Feed -> FeedScreen(vm, s.startTiktokId)
        Screen.Queue -> QueueScreen(vm)
        Screen.Settings -> SettingsScreen(vm)
    }
}
