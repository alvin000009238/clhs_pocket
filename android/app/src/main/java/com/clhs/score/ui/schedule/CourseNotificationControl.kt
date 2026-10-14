package com.clhs.score.ui.schedule

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clhs.score.notifications.*
import com.clhs.score.viewmodel.CourseNotificationViewModel

internal data class CourseNotificationControl(
    val enabled: Boolean,
    val pausedToday: Boolean,
    val ready: Boolean,
    val busy: Boolean,
    val blocked: Boolean,
    val available: Boolean,
    val denied: Boolean,
    val canToggle: Boolean,
    val toggle: (Boolean) -> Unit,
    val openNotificationSettings: () -> Unit,
    val remindBeforeClass: Boolean,
    val setRemindBeforeClass: (Boolean) -> Unit,
)

@Composable
internal fun CourseNotificationMenu(onOpen: () -> Unit, dismiss: () -> Unit) {
    DropdownMenuItem(
        text = { Text("當前課程通知") },
        onClick = { dismiss(); onOpen() },
    )
}

/** Holds permission state outside transient UI and applies enablement through the shared owner. */
@Composable
internal fun rememberCourseNotificationControl(allowed: Boolean): CourseNotificationControl {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val vm: CourseNotificationViewModel = viewModel(factory = CourseNotificationViewModel.factory(context))
    val state by vm.state.collectAsStateWithLifecycle()
    val currentAllowed by rememberUpdatedState(allowed)
    var denied by rememberSaveable { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf(false) }
    fun sync() {
        if (context.hasPostNotificationsPermission()) denied = false
        vm.sync()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending = false
        denied = !granted
        if (granted && currentAllowed) vm.setEnabled(true)
        else vm.message("需要開啟通知權限，功能尚未啟用")
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) sync() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { sync() }
    LaunchedEffect(state.message) {
        state.message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); vm.message(null) }
    }
    return CourseNotificationControl(
        enabled = state.preference.enabled,
        pausedToday = state.preference.pausedUntil > System.currentTimeMillis(),
        ready = state.ready,
        busy = state.busy || pending,
        blocked = state.blocked,
        available = state.available,
        denied = denied,
        canToggle = state.ready && !state.busy && !pending &&
            (currentAllowed || state.preference.enabled),
        toggle = { enabled ->
            if (!enabled) vm.setEnabled(false)
            else vm.prepareEnable(currentAllowed) {
                if (context.canPostNotifications()) vm.setEnabled(true)
                else if (Build.VERSION.SDK_INT >= 33 && !context.hasPostNotificationsPermission() && !denied) {
                    pending = true
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else vm.message("需要開啟通知權限，請使用本頁的通知權限設定，再重新開啟功能")
            }
        },
        openNotificationSettings = context::openAppNotificationSettings,
        remindBeforeClass = state.preference.remindBeforeClass,
        setRemindBeforeClass = { vm.setRemindBeforeClass(it) },
    )
}
