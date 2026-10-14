package com.clhs.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class ArchitectureBoundaryTest {
    @Test
    fun alarmFallbackPreservesUserPreference() {
        for (path in listOf(
            "notifications/CurrentCourseNotifications.kt",
            "widget/WidgetUpdateReceiver.kt",
        )) {
            val source = readSource("app/src/main/java/com/clhs/score/$path")
            assertFalse(path, source.contains("setPreferExactCourseAlarms(false)"))
            assertFalse(path, source.contains("exactUserPreference = false"))
            assertTrue(path, source.contains("exactSystemAccess = false"))
        }
    }

    @Test
    fun widgetAlarmRegistrationFailureReachesRecovery() {
        val source = readSource("app/src/main/java/com/clhs/score/widget/WidgetUpdateReceiver.kt")
        val scheduling = source.substringAfter("suspend fun scheduleNextUpdate(")
            .substringBefore("private fun logSchedule(")
        assertTrue(scheduling.substringAfter("} catch (error: Exception) {").contains("throw error"))
        assertTrue(source.contains("scheduleRecoveryUpdate(context, \"sync_failure\")"))
    }

    @Test
    fun alarmCapabilityReceiverBoundsRearmAndRecoversOnTimeout() {
        val source = readSource("app/src/main/java/com/clhs/score/reminders/AlarmCapabilityChangedReceiver.kt")
        assertTrue(source.contains("withTimeout(8_000)"))
        assertTrue(source.contains("catch (_: TimeoutCancellationException)"))
        assertTrue(source.contains("withTimeoutOrNull(1_000)"))
        assertTrue(source.contains("receiverTimedOut(fromRecovery = false)"))
        assertTrue(source.contains("WidgetUpdateReceiver.scheduleRecoveryUpdate("))
        assertTrue(source.contains("pending.finish()"))
    }

    @Test
    fun backgroundPauseSkipsOnlyNetworkChecksAfterCleanup() {
        val grade = readSource("app/src/main/java/com/clhs/score/reminders/GradeReminderWorker.kt")
        val announcement = readSource("app/src/main/java/com/clhs/score/reminders/AnnouncementReminderWorker.kt")
        val gate = "if (pause.contains(Instant.now())) return Result.success()"
        for (source in listOf(grade, announcement)) {
            assertTrue(source.contains(gate))
            assertTrue(source.contains("SettingsRepository(applicationContext).settings.first().backgroundPause"))
            val load = source.substringAfter("val pause = try {").substringBefore(gate)
            assertTrue(load.contains("catch (_: IOException)"))
            assertTrue(load.contains("return Result.retry()"))
        }
        assertTrue(grade.indexOf("retryReminderCleanup()") < grade.indexOf(gate))
        assertTrue(grade.indexOf("now >= state.expiresAtMillis") < grade.indexOf(gate))
        assertTrue(grade.indexOf("if (session == null)") < grade.indexOf(gate))
        assertTrue(grade.indexOf(gate) < grade.indexOf("repository.fetchGrades("))
        assertTrue(grade.indexOf(gate) < grade.indexOf("reminderRepository.mutate"))
        assertTrue(announcement.indexOf("canPostNotifications()") < announcement.indexOf(gate))
        assertTrue(announcement.indexOf(gate) < announcement.indexOf("val announcements = loadAnnouncementReminderSnapshot("))
        assertTrue(announcement.indexOf(gate) < announcement.indexOf("reminderRepository.recordCheck("))
    }

    @Test
    fun announcementCardsReceiveSharedUnreadState() {
        val shell = readSource("app/src/main/java/com/clhs/score/ui/AuthenticatedApp.kt")
        val screen = readSource("app/src/main/java/com/clhs/score/ui/announcements/SchoolAnnouncementsScreen.kt")

        assertTrue(shell.contains("unreadIds = announcementReminderUiState.unreadIds"))
        assertFalse(shell.contains("announcementReminderUiState.reminder.unreadIds"))
        assertTrue(screen.contains("unreadIds = unreadIds"))
        assertTrue(screen.contains("isUnread = announcement.id in unreadIds"))
        assertTrue(screen.contains("if (isUnread)"))
        assertTrue(screen.contains("AnnouncementBadge(\"未讀\", unread = true)"))
    }

    @Test
    fun appUiLocaleKeepsMaterialAccessibilityStringsInTraditionalChinese() {
        val source = readSource("app/src/main/java/com/clhs/score/MainActivity.kt")

        assertTrue(source.contains("setLocale(Locale.forLanguageTag(\"zh-TW\"))"))
        assertTrue(source.contains("newBase.createConfigurationContext(configuration)"))
    }

    @Test
    fun releaseKeepsBiweeklyClassNameUsedForRelativeResourceLookup() {
        val rules = readSource("app/proguard-rules.pro")

        assertTrue(rules.contains("-keepnames class biweekly.Biweekly"))
    }

    @Test
    fun releaseKeepsBiweeklyParameterMembersUsedByReflection() {
        val rules = readSource("app/proguard-rules.pro")

        assertTrue(
            rules.contains(
                """
                -keepclassmembers class biweekly.parameter.** extends biweekly.parameter.EnumParameterValue {
                  <init>(java.lang.String);
                  <init>(java.lang.String, biweekly.ICalVersion[]);
                  public static <fields>;
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun releaseKeepsAppProtoFieldNamesUsedByLiteReflection() {
        val rules = readSource("app/proguard-rules.pro")

        assertTrue(
            rules.contains(
                """
                -keep class com.clhs.score.data.proto.** extends com.google.protobuf.GeneratedMessageLite {
                  *;
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun sessionStorageKeepsCryptoMigrationAndBackupBoundaries() {
        val sessionStore = readSource("app/src/main/java/com/clhs/score/data/SessionStore.kt")
        val crypto = readSource("app/src/main/java/com/clhs/score/data/SessionCrypto.kt")
        val legacy = readSource("app/src/main/java/com/clhs/score/data/LegacySessionMigration.kt")
        val worker = readSource("app/src/main/java/com/clhs/score/reminders/GradeReminderWorker.kt")
        val proto = readSource("app/src/main/proto/session_storage.proto")
        val backupRules = readSource("app/src/main/res/xml/backup_rules.xml")
        val extractionRules = readSource("app/src/main/res/xml/data_extraction_rules.xml")

        assertFalse(sessionStore.contains("import androidx.security.crypto"))
        assertFalse(legacy.contains("androidx.security.crypto"))
        assertTrue(legacy.contains("deleteSharedPreferences(\"score_session\")"))
        assertTrue(crypto.contains("AES/GCM/NoPadding"))
        assertTrue(crypto.contains("setKeySize(256)"))
        assertTrue(sessionStore.contains("app/session/general/v1"))
        assertTrue(sessionStore.contains("app/session/reminder/v1"))
        assertTrue(sessionStore.contains("runtime.generation"))
        assertFalse(sessionStore.contains("legacySource.readGeneral()"))
        assertFalse(sessionStore.contains("legacySource.readReminder()"))
        assertTrue(sessionStore.contains("reminderWriteGeneration"))
        assertFalse(proto.contains("token"))
        assertFalse(proto.contains("cookie"))
        assertFalse(proto.contains("student"))
        assertFalse("$sessionStore\n$crypto\n$legacy".contains("AnalyticsLogger"))
        assertFalse("$sessionStore\n$crypto\n$legacy".contains("android.util.Log"))
        assertTrue(worker.contains("loadReminderSession(now, state.studentNo)"))
        assertFalse(worker.contains("loadSession()"))
        listOf("session_storage.pb", "score_biometric_session.xml", "score_session.xml").forEach { file ->
            assertTrue(backupRules.contains(file))
            assertTrue(extractionRules.contains(file))
        }
    }

    @Test
    fun schoolCalendarIsPublicAndNeverUsesSchoolAuthentication() {
        val dataSource = readSource("app/src/main/java/com/clhs/score/data/SchoolCalendar.kt")
        val viewModelSource = readSource("app/src/main/java/com/clhs/score/viewmodel/SchoolCalendarViewModel.kt")
        val combined = "$dataSource\n$viewModelSource"

        assertTrue(dataSource.contains("CookieJar.NO_COOKIES"))
        assertTrue(dataSource.contains("https://calendar.google.com/calendar/ical/"))
        listOf("SchoolGradeClient", "SessionStore", "AuthenticatedSession", "SchoolCookieJar").forEach { term ->
            assertFalse("Public calendar must not access school authentication: $term", combined.contains(term))
        }
    }

    @Test
    fun schoolCalendarHasLoadingEmptyErrorAndCachedStates() {
        val source = readSource("app/src/main/java/com/clhs/score/ui/calendar/SchoolCalendarScreen.kt")
        val shimmerSource = readSource("app/src/main/java/com/clhs/score/ui/LoadingShimmer.kt")

        assertTrue(source.contains("uiState.isInitialLoading"))
        assertTrue(source.contains("CalendarLoadingList()"))
        assertTrue(shimmerSource.contains("rememberInfiniteTransition"))
        assertTrue(source.contains("Brush.linearGradient"))
        assertTrue(source.contains("contentDescription = \"正在載入行事曆\""))
        assertTrue(source.contains("暫時無法取得行事曆"))
        assertTrue(source.contains("目前沒有接下來的活動"))
        assertTrue(source.contains("PullToRefreshBox"))
        assertTrue(source.contains("PullToRefreshDefaults.LoadingIndicator"))
        assertTrue(source.contains("OutlinedTextField("))
        assertTrue(source.contains("item(key = \"calendar-search\")"))
        assertFalse(source.contains("CalendarSummaryCard"))
        assertFalse(source.contains("更新於"))
        assertTrue(source.contains("animateScrollToItem(0)"))
        assertTrue(source.contains("FloatingActionButton("))
        assertTrue(source.contains("!listState.isScrollInProgress"))
        assertFalse(source.contains("scaleIn("))
        assertTrue(source.contains("slideInVertically("))
        assertTrue(source.contains("Box(modifier = Modifier.padding(12.dp))"))
        assertTrue(source.contains("icon = \"keyboard_arrow_up\""))
        assertTrue(source.contains("contentDescription = \"回到頂端\""))
        assertTrue(source.contains("Text(\"Google 行事曆\")"))
    }

    @Test
    fun scheduleWidgetDoesNotDependOnAuthenticationState() {
        val source = readSource("app/src/main/java/com/clhs/score/widget/ScheduleWidget.kt")

        assertTrue(source.contains("loadWidgetScheduleReport()"))

        val forbiddenTerms = listOf(
            "SessionStore",
            "loadSession(",
            "loadSessionWithPin",
            "loadBiometricSession",
            "saveSession(",
            "clearNormalSession",
            "clearBiometricSession",
            "apiToken",
            "cookies",
        )
        forbiddenTerms.forEach { term ->
            assertFalse("ScheduleWidget must not depend on authentication state: $term", source.contains(term))
        }
        assertTrue(source.contains("課表已過期"))
        assertTrue(source.contains("點擊此處更新"))
    }

    @Test
    fun pinUnlockActivatesSessionBeforeReleasingLock() {
        val source = readSource("app/src/main/java/com/clhs/score/MainActivity.kt")
        val pinUnlockBlock = source
            .substringAfter("onUnlockWithPin = { pin ->")
            .substringAfter("if (session != null) {")
            .substringBefore("if (isBiometricInvalidated.value)")

        val activateIndex = pinUnlockBlock.indexOf("scoreVm.loginWithBiometricSession(session)")
        val unlockIndex = pinUnlockBlock.indexOf("isAppLocked.value = false")

        assertTrue("PIN unlock path must activate the in-memory session", activateIndex >= 0)
        assertTrue("PIN unlock path must release the lock", unlockIndex >= 0)
        assertTrue("Session must be active before UI can render", activateIndex < unlockIndex)
    }

    @Test
    fun biometricPromptIsSingleFlightAndDoesNotCountAsBackgrounding() {
        val source = readSource("app/src/main/java/com/clhs/score/MainActivity.kt")

        assertTrue(source.contains("private var isBiometricPromptShowing = false"))
        assertTrue(source.contains("if (wasInBackground && !isBiometricPromptShowing)"))
        assertTrue(source.contains("if (!isChangingConfigurations && !isBiometricPromptShowing)"))

        val unlockPromptBlock = source
            .substringAfter("private fun showBiometricUnlockPrompt(")
            .substringBefore("private fun showBiometricEnrollPrompt(")

        assertTrue(
            "Unlock prompt must ignore duplicate authenticate requests",
            unlockPromptBlock.contains("if (isBiometricPromptShowing) return"),
        )
        assertTrue(
            "Unlock prompt must mark the prompt as visible before authenticate",
            unlockPromptBlock.indexOf("isBiometricPromptShowing = true") <
                unlockPromptBlock.indexOf("biometricPrompt.authenticate"),
        )
        assertTrue(
            "Unlock prompt must clear prompt state on terminal callbacks",
            unlockPromptBlock.countOccurrences("isBiometricPromptShowing = false") >= 3,
        )
    }

    @Test
    fun singleTaskScheduleDeepLinkIsHeldUntilScheduleScreenCanOpen() {
        val activitySource = readSource("app/src/main/java/com/clhs/score/MainActivity.kt")
        val appSource = readSource("app/src/main/java/com/clhs/score/ui/ScoreApp.kt")
        val authenticatedSource = readSource("app/src/main/java/com/clhs/score/ui/AuthenticatedApp.kt")
        val manifest = readSource("app/src/main/AndroidManifest.xml")

        assertTrue(manifest.contains("android:scheme=\"scoreapp\""))
        assertTrue(manifest.contains("android:host=\"schedule\""))
        assertTrue(manifest.contains("android.intent.category.BROWSABLE"))
        assertTrue(activitySource.contains("private val pendingLaunchTarget = mutableStateOf<AppLaunchTarget?>(null)"))
        assertTrue(activitySource.contains("data?.scheme == \"scoreapp\" && data.host == \"schedule\""))
        assertTrue(activitySource.contains("pendingLaunchTarget.value = AppLaunchTarget.Schedule"))
        assertTrue(activitySource.contains("launchTarget = pendingLaunchTarget.value"))
        assertTrue(activitySource.contains("onLaunchTargetHandled = { pendingLaunchTarget.value = null }"))
        assertTrue(activitySource.contains("outState.putLaunchTarget(pendingLaunchTarget.value)"))
        assertTrue(authenticatedSource.contains("LaunchedEffect(launchTarget, navigator, settings.hasCompletedOnboarding)"))
        assertTrue(authenticatedSource.contains("if (!settings.hasCompletedOnboarding) return@LaunchedEffect"))
        assertTrue(authenticatedSource.contains("AppLaunchTarget.Schedule ->"))
        assertTrue(authenticatedSource.contains("navigator.selectTopLevel(TopLevelDestination.Schedule)"))
        assertTrue(authenticatedSource.contains("onLaunchTargetHandled()"))
        assertTrue(appSource.contains("launchTarget = launchTarget"))
        assertFalse("Composable must not mutate Activity intent data", appSource.contains("intent?.data = null"))
    }

    @Test
    fun biometricLockStateSurvivesConfigurationChange() {
        val source = readSource("app/src/main/java/com/clhs/score/MainActivity.kt")

        assertTrue(source.contains("override fun onSaveInstanceState(outState: Bundle)"))
        assertTrue(source.contains("outState.putBoolean(KEY_APP_LOCKED, isAppLocked.value)"))
        assertTrue(source.contains("savedInstanceState?.getBoolean(KEY_APP_LOCKED, false) == true"))
        assertTrue(source.contains("hasBiometricSession"))
        assertTrue(source.contains("isInitialLockResolved.value = !shouldLockOnInitialReady || isAppLocked.value"))
        assertTrue(source.contains("const val KEY_APP_LOCKED = \"app_locked\""))
    }

    @Test
    fun logoutDisablesBiometricSettingWhenItsSessionIsDeleted() {
        val logoutBlock = readSource("app/src/main/java/com/clhs/score/MainActivity.kt")
            .substringAfter("ScoreApp(")
            .substringAfter("onLogout = {")
            .substringBefore("onToggleSubject")

        assertTrue(logoutBlock.contains("scoreVm.logout()"))
        assertTrue(logoutBlock.contains("settingsVm.setBiometricEnabled(false)"))
    }

    @Test
    fun widgetAlarmRearmsForSystemChangesAndHasOneOwner() {
        val manifest = readSource("app/src/main/AndroidManifest.xml")
        val widgetReceiver = readSource("app/src/main/java/com/clhs/score/widget/ScheduleWidgetReceiver.kt")
        val updateReceiver = readSource("app/src/main/java/com/clhs/score/widget/WidgetUpdateReceiver.kt")
        val widgetSource = readSource("app/src/main/java/com/clhs/score/widget/ScheduleWidget.kt")

        val courseReceiver = manifest.substringAfter("<receiver android:name=\".notifications.CourseNotificationReceiver\"")
            .substringBefore("</receiver>")
        val widgetUpdateReceiver = manifest.substringAfter("<receiver\n            android:name=\".widget.WidgetUpdateReceiver\"")
            .substringBefore("</receiver>")
        assertTrue(courseReceiver.contains("android:exported=\"false\""))
        assertTrue(courseReceiver.contains("android.intent.action.BOOT_COMPLETED"))
        assertTrue(widgetUpdateReceiver.contains("android.intent.action.BOOT_COMPLETED"))
        assertTrue(widgetUpdateReceiver.contains("android.intent.action.MY_PACKAGE_REPLACED"))
        assertTrue(widgetUpdateReceiver.contains("android.intent.action.TIME_SET"))
        assertTrue(widgetUpdateReceiver.contains("android.intent.action.TIMEZONE_CHANGED"))
        assertEquals(2, manifest.countOccurrences("android.intent.action.BOOT_COMPLETED"))
        assertTrue(widgetReceiver.contains("override fun onDisabled(context: Context)"))
        assertTrue(widgetReceiver.contains("WidgetUpdateReceiver.cancelUpdate(context)"))
        assertTrue(widgetReceiver.contains("context.sendBroadcast(Intent(context, WidgetUpdateReceiver::class.java)"))
        val provideGlance = widgetSource.substringAfter("override suspend fun provideGlance")
            .substringBefore("override suspend fun providePreview")
        val fullSync = widgetSource.substringAfter("suspend fun syncAllScheduleWidgets")
            .substringBefore("internal fun shouldRefreshScheduleWidgetPreview")
        assertFalse(provideGlance.contains("scheduleNextUpdate"))
        assertEquals(1, fullSync.countOccurrences("scheduleNextUpdate("))
        assertTrue(updateReceiver.contains("Intent.ACTION_BOOT_COMPLETED"))
        assertTrue(updateReceiver.contains("syncAllScheduleWidgets(context)"))
    }

    @Test
    fun currentCourseUsesExactAlarmsOnlyForUserOptedInBoundaries() {
        val source = readSource("app/src/main/java/com/clhs/score/notifications/CurrentCourseNotifications.kt")
        val manifest = readSource("app/src/main/AndroidManifest.xml")
        listOf("setExact(", "setAlarmClock(", "setRepeating(",
            "startForeground", "WorkManager", "SchoolGradeClient", "loadSession(", "WidgetUpdateReceiver")
            .forEach { assertFalse(it, source.contains(it)) }
        assertTrue(source.contains("setExactAndAllowWhileIdle("))
        assertTrue(source.contains("CourseAlarmPurpose.RECOVERY"))
        assertTrue(source.contains("selectCourseAlarmPrecision("))
        assertTrue(manifest.contains("android.permission.SCHEDULE_EXACT_ALARM"))
        assertFalse(manifest.contains("android.permission.USE_EXACT_ALARM"))
        assertTrue(manifest.contains("android.permission.POST_PROMOTED_NOTIFICATIONS"))
        assertTrue(source.contains("getBroadcast(context, REQUEST_CODE"))
        assertTrue(source.contains("setAction(BOUNDARY)"))
        assertTrue(source.contains("EXTRA_RECOVERY"))
    }

    @Test
    fun widgetAlarmUsesOptionalExactPolicyAndKeepsOnePendingIntent() {
        val source = readSource("app/src/main/java/com/clhs/score/widget/WidgetUpdateReceiver.kt")

        assertTrue(source.contains(".setAndAllowWhileIdle("))
        assertTrue(source.contains("AlarmManager.RTC_WAKEUP,"))
        assertFalse(source.contains("alarmManager.set("))
        assertTrue(source.contains("setExactAndAllowWhileIdle("))
        assertTrue(source.contains("CourseAlarmPurpose.BOUNDARY"))
        assertTrue(source.contains("selectCourseAlarmPrecision("))
        val manifest = readSource("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android.permission.SCHEDULE_EXACT_ALARM"))
        assertFalse(manifest.contains("android.permission.USE_EXACT_ALARM"))
        assertTrue(source.contains("syncAllScheduleWidgets(context)"))
        assertTrue(source.contains("FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE"))
        assertTrue(source.contains("FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE"))
        assertTrue(source.contains("synchronized(alarmLock)"))
        assertTrue(source.contains("activeWidgetIds(context)"))
        assertTrue(source.contains("shouldScheduleWidgetAlarm(activeCount)"))
        val widgetSource = readSource("app/src/main/java/com/clhs/score/widget/ScheduleWidget.kt")
        assertTrue(widgetSource.contains("cacheStore.loadWidgetScheduleReport()"))
    }

    @Test
    fun batteryExemptionRequestIsUserTriggeredAndUsesDeclaredSystemCapability() {
        val screen = readSource("app/src/main/java/com/clhs/score/ui/ScheduleReliabilitySettingsScreen.kt")
        val helper = readSource("app/src/main/java/com/clhs/score/reminders/BatteryOptimizationHelper.kt")
        val manifest = readSource("app/src/main/AndroidManifest.xml")
        val clickAction = screen.substringAfter("onManageBattery = {").substringBefore("snackbarHost =")

        assertTrue(clickAction.contains("BatteryOptimizationHelper.openBatteryOptimizationRequest(context)"))
        assertTrue(clickAction.contains("if (batteryExempt)"))
        assertTrue(helper.contains("Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        assertTrue(helper.contains("package:\${context.packageName}"))
        assertTrue(helper.contains("isIgnoringBatteryOptimizations(context.packageName)"))
        assertTrue(manifest.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
    }

    @Test
    fun coroutineCancellationIsNotConvertedToUiOrWorkerErrors() {
        val mainActivity = readSource("app/src/main/java/com/clhs/score/MainActivity.kt")
        val scoreViewModel = readSource("app/src/main/java/com/clhs/score/viewmodel/ScoreViewModel.kt")
        val subjectTrendOwner = readSource(
            "app/src/main/java/com/clhs/score/viewmodel/SubjectTrendStateOwner.kt",
        )
        val scheduleViewModel = readSource("app/src/main/java/com/clhs/score/viewmodel/ScheduleViewModel.kt")
        val schoolClient = readSource("app/src/main/java/com/clhs/score/data/SchoolGradeClient.kt")
        val reminderWorker = readSource("app/src/main/java/com/clhs/score/reminders/GradeReminderWorker.kt")
        val updateChecker = readSource("app/src/main/java/com/clhs/score/data/UpdateChecker.kt")
        val settingsViewModel = readSource("app/src/main/java/com/clhs/score/viewmodel/SettingsViewModel.kt")

        assertTrue(mainActivity.countOccurrences("error.throwIfCancellation()") >= 3)
        assertTrue(mainActivity.contains("private fun Throwable.throwIfCancellation()"))
        assertTrue(scoreViewModel.contains("import kotlinx.coroutines.CancellationException"))
        assertTrue(
            scoreViewModel.countOccurrences("error.throwIfCancellation()") +
                subjectTrendOwner.countOccurrences("error.throwIfCancellation()") >= 8,
        )
        assertTrue(scoreViewModel.contains("private fun Throwable.throwIfCancellation()"))
        assertTrue(subjectTrendOwner.contains("import kotlinx.coroutines.CancellationException"))
        assertTrue(subjectTrendOwner.contains("private fun Throwable.throwIfCancellation()"))

        assertTrue(scheduleViewModel.contains("import kotlinx.coroutines.CancellationException"))
        assertTrue(scheduleViewModel.countOccurrences("e.throwIfCancellation()") >= 4)
        assertTrue(scheduleViewModel.contains("private fun Throwable.throwIfCancellation()"))
        assertTrue(schoolClient.contains("error is CancellationException"))

        assertTrue(reminderWorker.contains("import kotlinx.coroutines.CancellationException"))
        assertTrue(reminderWorker.contains("if (error is CancellationException) throw error"))

        assertTrue(updateChecker.contains("catch (e: CancellationException)"))
        assertTrue(updateChecker.contains("throw e"))
        assertTrue(settingsViewModel.contains("catch (error: CancellationException)"))
        assertTrue(settingsViewModel.contains("throw error"))
    }

    @Test
    fun gradeExportBuildAndMediaStoreWriteRunOnIoDispatcher() {
        val source = readSource("app/src/main/java/com/clhs/score/viewmodel/ScoreViewModel.kt")
        val exportBlock = source
            .substringAfter("fun exportGrades(")
            .substringBefore("fun dismissExportResult()")

        assertTrue(exportBlock.contains("withContext(Dispatchers.IO)"))
        assertTrue(exportBlock.indexOf("withContext(Dispatchers.IO)") < exportBlock.indexOf("GradeExporter.buildCsvContent"))
        assertTrue(exportBlock.indexOf("withContext(Dispatchers.IO)") < exportBlock.indexOf("GradeExporter.saveCsvToDownloads"))
    }

    @Test
    fun gradeBatchesCancelOldJobsAndUseBoundedHttpDispatcher() {
        val viewModel = readSource("app/src/main/java/com/clhs/score/viewmodel/ScoreViewModel.kt")
        val subjectTrendOwner = readSource(
            "app/src/main/java/com/clhs/score/viewmodel/SubjectTrendStateOwner.kt",
        )
        val client = readSource("app/src/main/java/com/clhs/score/data/SchoolGradeClient.kt")

        assertTrue(viewModel.contains("exportJob?.cancel()"))
        assertTrue(viewModel.contains("historyJob?.cancel()"))
        assertTrue(subjectTrendOwner.contains("loadJob?.cancel()"))
        assertTrue(client.contains("maxRequests = MAX_CONCURRENT_SCHOOL_REQUESTS"))
        assertTrue(client.contains("maxRequestsPerHost = MAX_CONCURRENT_SCHOOL_REQUESTS"))
    }

    @Test
    fun notificationOnlyActionsRequireOneTimeCapability() {
        val mainActivity = readSource("app/src/main/java/com/clhs/score/MainActivity.kt")
        val capabilities = readSource(
            "app/src/main/java/com/clhs/score/notifications/NotificationActionCapabilities.kt",
        )
        val messagingService = readSource(
            "app/src/main/java/com/clhs/score/notifications/ScoreFirebaseMessagingService.kt",
        )
        val reminderNotifier = readSource(
            "app/src/main/java/com/clhs/score/reminders/GradeReminderNotifier.kt",
        )

        val handler = mainActivity.substringAfter("private fun handleIntent(").substringBefore("private fun showBiometricUnlockPrompt(")
        assertTrue(handler.contains("consumeNotificationAction(applicationContext, intent)"))
        assertFalse(handler.contains("getStringExtra(\"from\")"))
        assertFalse(handler.contains("getBooleanExtra"))
        assertTrue(capabilities.contains("UUID.randomUUID()"))
        assertTrue(capabilities.contains("removeToken(token)"))
        assertTrue(messagingService.contains("NotificationActionCapabilities.issueUpdate(this)"))
        assertTrue(reminderNotifier.contains("NotificationActionCapabilities.issueGradeReminder"))
    }

    @Test
    fun exportedDebugToolsRequireShellPermissionAndCaptureIsSingleFlight() {
        val debugManifest = readSource("app/src/debug/AndroidManifest.xml")
        val releaseManifest = readSource("app/src/main/AndroidManifest.xml")
        val captureActivity = readSource(
            "app/src/debug/java/com/clhs/score/widget/ScheduleWidgetPreviewCaptureActivity.kt",
        )

        assertEquals(2, debugManifest.countOccurrences("android:permission=\"android.permission.DUMP\""))
        assertFalse(releaseManifest.contains("GradeReminderDebugReceiver"))
        assertFalse(releaseManifest.contains("ScheduleWidgetPreviewCaptureActivity"))
        assertTrue(captureActivity.contains("AtomicBoolean(false)"))
        assertTrue(captureActivity.contains("captureInProgress.compareAndSet(false, true)"))
        assertTrue(captureActivity.contains("captureInProgress.set(false)"))
    }

    @Test
    fun encryptedSessionDataStoreRecoversFromMalformedProto() {
        val source = readSource("app/src/main/java/com/clhs/score/data/SessionProtoStorage.kt")

        assertTrue(source.contains("corruptionHandler = ReplaceFileCorruptionHandler"))
        assertTrue(source.contains("SessionStorage.getDefaultInstance()"))
    }

    @Test
    fun webViewLoginBridgeIsLimitedToTrustedSchoolLoginPage() {
        val source = readSource("app/src/main/java/com/clhs/score/ui/WebViewLoginScreen.kt")
        val mainActivity = readSource("app/src/main/java/com/clhs/score/MainActivity.kt")

        assertTrue(source.contains("shouldOverrideUrlLoading"))
        assertTrue(source.contains("return !isTrustedSchoolUrl(url)"))
        assertTrue(source.contains("if (loginHandled || !isTrustedLoginPage)"))
        assertTrue(source.contains("isTrustedLoginPage = isTrustedSchoolLoginUrl(url)"))
        assertTrue(source.contains("uri.scheme == \"https\""))
        assertTrue(source.contains("SCHOOL_DOMAIN"))
        assertTrue(source.contains("uri.port == -1 || uri.port == 443"))
        assertFalse(source.contains("CookieManager.getInstance().flush()"))
        assertFalse(mainActivity.contains("CookieManager.getInstance().flush()"))
    }

    @Test
    fun publicSchoolErrorActionsRemainUsableAtLargeTextSizes() {
        val announcementSource = readSource(
            "app/src/main/java/com/clhs/score/ui/announcements/SchoolAnnouncementsScreen.kt",
        )
        val calendarSource = readSource(
            "app/src/main/java/com/clhs/score/ui/calendar/SchoolCalendarScreen.kt",
        )
        val announcementState = announcementSource
            .substringAfter("private fun AnnouncementMessageState(")
            .substringBefore("private fun AnnouncementsLoadingList(")
        val calendarState = calendarSource
            .substringAfter("private fun CalendarMessageState(")
            .substringBefore("@Composable")

        assertFalse(announcementState.contains("Row(horizontalArrangement"))
        assertTrue(announcementState.countOccurrences("fillMaxWidth()") >= 3)
        assertFalse(calendarState.contains("Row(horizontalArrangement"))
        assertTrue(calendarState.countOccurrences("fillMaxWidth()") >= 3)
    }

    @Test
    fun authenticatedSchoolWebsiteUsesActiveSessionAndTrustedDomain() {
        val webViewSource = readSource("app/src/main/java/com/clhs/score/ui/WebViewLoginScreen.kt")
        val appSource = readSource("app/src/main/java/com/clhs/score/ui/AuthenticatedApp.kt")
        val campusSource = readSource("app/src/main/java/com/clhs/score/ui/HubScreens.kt")

        assertTrue(campusSource.contains("title = \"欣河智慧校園平台\""))
        assertTrue(appSource.contains("AuthGate("))
        assertTrue(appSource.contains("scoreViewModel.getCurrentSession()?.let"))
        assertTrue(webViewSource.contains("session.cookies.forEach"))
        assertTrue(webViewSource.contains("return !isTrustedSchoolUrl(url)"))
        assertTrue(webViewSource.contains("clearSchoolWebData()"))
        assertFalse(webViewSource.contains("SessionStore"))
    }

    @Test
    fun schoolAnnouncementsArePublicAndHandleEveryDataState() {
        val dataSource = readSource("app/src/main/java/com/clhs/score/data/SchoolAnnouncements.kt")
        val viewModelSource = readSource("app/src/main/java/com/clhs/score/viewmodel/SchoolAnnouncementsViewModel.kt")
        val screenSource = readSource("app/src/main/java/com/clhs/score/ui/announcements/SchoolAnnouncementsScreen.kt")
        val combined = "$dataSource\n$viewModelSource"

        assertTrue(dataSource.contains("CookieJar.NO_COOKIES"))
        assertTrue(dataSource.contains("followRedirects(false)"))
        listOf("SchoolGradeClient", "SessionStore", "AuthenticatedSession", "SchoolCookieJar").forEach { term ->
            assertFalse("Public announcements must not access school authentication: $term", combined.contains(term))
        }
        assertTrue(screenSource.contains("uiState.isInitialLoading"))
        assertTrue(screenSource.contains("正在載入學校消息"))
        assertTrue(screenSource.contains("目前沒有最新消息"))
        assertTrue(screenSource.contains("暫時無法取得學校消息"))
        assertTrue(screenSource.contains("uiState.loadMoreError"))
        assertTrue(screenSource.contains("PullToRefreshDefaults.LoadingIndicator"))
        assertTrue(screenSource.contains("contentDescription = image.description"))
        assertTrue(screenSource.contains("clickable(onClickLabel = \"開啟原圖\")"))
        assertTrue(screenSource.contains("PullToRefreshBox"))
        assertFalse(screenSource.contains("AnnouncementSummaryCard"))
        assertFalse(screenSource.contains("更新於"))
    }

    @Test
    fun announcementSearchFieldStaysBelowTitleWithoutSearchToggle() {
        val source = readSource("app/src/main/java/com/clhs/score/ui/announcements/SchoolAnnouncementsScreen.kt")
        val listSource = source
            .substringAfter("private fun AnnouncementList(")
            .substringBefore("private fun AnnouncementCard(")

        assertTrue(source.contains("more_vert"))
        assertFalse(source.contains("isSearchExpanded"))
        assertTrue(source.contains("title = \"學校公告\""))
        assertTrue(source.contains("OutlinedTextField("))
        val searchFieldIndex = listSource.indexOf("item(key = \"announcement-search\") { searchField(Modifier) }")
        val stateSwitchIndex = listSource.indexOf("when {", searchFieldIndex)
        assertTrue(searchFieldIndex >= 0)
        assertTrue(searchFieldIndex < stateSwitchIndex)
    }

    @Test
    fun announcementDetailLoadingKeepsTheHeaderCornerShape() {
        val source = readSource("app/src/main/java/com/clhs/score/ui/announcements/SchoolAnnouncementsScreen.kt")
        val detailLoading = source.substringAfter("private fun AnnouncementDetailLoading()")
            .substringBefore("private fun AnnouncementLoadingBlock(")

        assertTrue(detailLoading.contains("shape = MaterialTheme.shapes.largeIncreased"))
    }

    @Test
    fun announcementHtmlLinksRemainClickable() {
        val source = readSource("app/src/main/java/com/clhs/score/ui/announcements/SchoolAnnouncementsScreen.kt")
        val textViewSetup = source.substringAfter("TextView(context).apply {")
            .substringBefore("textSize = 16f")

        assertTrue(textViewSetup.contains("movementMethod = LinkMovementMethod.getInstance()"))
        assertTrue(textViewSetup.contains("linksClickable = true"))
        assertFalse(
            "Text selection replaces LinkMovementMethod and makes announcement links unclickable",
            textViewSetup.contains("setTextIsSelectable(true)"),
        )
    }

    @Test
    fun campusAndPersonalDestinationsOwnFormerDrawerEntrances() {
        val gradesSource = readSource("app/src/main/java/com/clhs/score/ui/GradesScreen.kt")
        val hubSource = readSource("app/src/main/java/com/clhs/score/ui/HubScreens.kt")
        val personalSource = readSource("app/src/main/java/com/clhs/score/ui/PersonalScreen.kt")
        val navigationSource = readSource("app/src/main/java/com/clhs/score/ui/navigation/AppNavigation.kt")
        val settingsSource = readSource("app/src/main/java/com/clhs/score/ui/SettingsScreen.kt")
        val advancedSource = readSource("app/src/main/java/com/clhs/score/ui/AdvancedComponents.kt")

        assertTrue(hubSource.contains("title = \"欣河智慧校園平台\""))
        assertTrue(hubSource.contains("\"學校公告\""))
        assertTrue(hubSource.contains("title = \"行事曆\""))
        assertTrue(hubSource.contains("OverviewItemKind.Announcement"))
        assertTrue(hubSource.contains("OverviewItemKind.CalendarEvent"))
        assertFalse(hubSource.contains("CampusDestination("))
        assertTrue(personalSource.contains("LazyColumn("))
        assertTrue(personalSource.contains("SettingsSection(\"外觀\""))
        assertTrue(personalSource.contains("SettingsSection(\"關於\""))
        assertTrue(personalSource.contains("Text(\"登出帳號\")"))
        assertFalse(navigationSource.contains("data object SettingsRoute"))
        assertFalse(navigationSource.contains("data object AboutRoute"))
        assertTrue(navigationSource.contains("Overview(OverviewRoute, \"首頁\""))
        assertTrue(navigationSource.contains("Schedule(ScheduleRoute, \"課表\""))
        assertTrue(navigationSource.contains("Grades(GradesRoute, \"成績\""))
        assertTrue(navigationSource.contains("Campus(CampusRoute, \"校園\""))
        assertFalse(gradesSource.contains("ModalNavigationDrawer"))
        assertFalse(gradesSource.contains("WideNavigationRail"))
        assertFalse(gradesSource.contains("ShortNavigationBar"))
        assertFalse(gradesSource.contains("BuildConfig.VERSION_NAME"))
        assertTrue(personalSource.contains("title = \"App 更新\""))
        assertTrue(personalSource.contains("title = \"開源授權\""))
        val dataPrivacySection = personalSource
            .substringAfter("SettingsSection(\"資料與隱私\")")
            .substringBefore("SettingsSection(\"關於\")")
        assertTrue(dataPrivacySection.contains("title = \"使用統計\""))
        assertTrue(
            dataPrivacySection.indexOf("title = \"使用統計\"") >
                dataPrivacySection.lastIndexOf("title = \"生物識別解鎖\""),
        )
        assertFalse(
            personalSource.substringAfter("SettingsSection(\"關於\")")
                .substringBefore("SettingsSection(\"開發者選項\")")
                .contains("title = \"使用統計\""),
        )
        assertFalse(gradesSource.contains("鍥而不舍，金石可鏤。"))
        assertFalse(settingsSource.contains("title = \"開啟校務系統\""))
        assertFalse(advancedSource.contains("SchoolCalendarEntryCard"))
        assertFalse(advancedSource.contains("ScheduleEntryCard"))
        assertFalse(advancedSource.contains("我的課表"))
    }

    @Test
    fun announcementReminderEntryLivesInAnnouncementsOverflowMenu() {
        val announcementsSource = readSource("app/src/main/java/com/clhs/score/ui/announcements/SchoolAnnouncementsScreen.kt")
        val personalSource = readSource("app/src/main/java/com/clhs/score/ui/PersonalScreen.kt")
        val reminderMenu = announcementsSource
            .substringAfter("DropdownMenuItem(")
            .substringBefore("onClick = {")

        assertTrue(announcementsSource.contains("more_vert"))
        assertTrue(announcementsSource.contains("DropdownMenuItem("))
        assertTrue(announcementsSource.contains("text = { Text(\"公告更新提醒\") }"))
        assertFalse(reminderMenu.contains("leadingIcon"))
        assertTrue(announcementsSource.contains("onOpenAnnouncementReminder()"))
        assertFalse(personalSource.contains("公告更新提醒"))
        assertFalse(personalSource.contains("onOpenAnnouncementReminder"))
    }

    @Test
    fun gradesPagerSupportsSwipeAndKeepsTabsInSync() {
        val source = readSource("app/src/main/java/com/clhs/score/ui/GradesScreen.kt")

        assertFalse(source.contains("userScrollEnabled = false"))
        assertFalse(source.contains("snapshotFlow { pagerState.settledPage }"))
        assertTrue(source.contains("selectedTabIndex = pagerState.currentPage"))
        assertTrue(source.contains("beyondViewportPageCount = GradesDestination.entries.lastIndex"))
    }

    @Test
    fun announcementReminderWorkerUsesOnlyPublicAnnouncementData() {
        val worker = readSource("app/src/main/java/com/clhs/score/reminders/AnnouncementReminderWorker.kt")

        assertTrue(worker.contains("NetworkSchoolAnnouncementsRepository"))
        assertTrue(worker.contains("AnnouncementReminderRepository"))
        assertTrue(worker.contains("school_announcement_poll"))
        listOf("SessionStore", "SchoolGradeClient", "SchoolCookieJar", "AuthenticatedSession").forEach { term ->
            assertFalse("Announcement reminder must not access login state: $term", worker.contains(term))
        }
    }

    @Test
    fun announcementReminderScheduleUpdatesExistingPeriodicWork() {
        val worker = readSource("app/src/main/java/com/clhs/score/reminders/AnnouncementReminderWorker.kt")

        assertTrue(worker.contains("ExistingPeriodicWorkPolicy.UPDATE"))
        assertFalse(worker.contains("ExistingPeriodicWorkPolicy.KEEP"))
    }

    @Test
    fun announcementReminderAppliesEnableAndUnitChangesOnlyAfterSuccessfulSync() {
        val viewModel = readSource("app/src/main/java/com/clhs/score/viewmodel/AnnouncementReminderViewModel.kt")

        assertTrue(viewModel.indexOf("val announcements = loadSnapshot") < viewModel.indexOf("repository.enableWithBaseline"))
        assertTrue(viewModel.indexOf("val announcements = loadSnapshot", viewModel.indexOf("fun applyUnits")) < viewModel.indexOf("repository.applySelectedUnitIds"))
    }

    @Test
    fun workManagerInfoShowsEveryAppWorkWithoutTagFiltering() {
        val screen = readSource("app/src/main/java/com/clhs/score/ui/WorkManagerInfoScreen.kt")

        assertTrue(screen.contains("WorkQuery.fromStates(WorkInfo.State.entries)"))
        assertTrue(screen.contains("getWorkInfosFlow(query)"))
        assertFalse(screen.contains("getWorkInfosByTagFlow"))
    }

    @Test
    fun predictiveBackUsesNavigation3DisplayWithoutCompetingHandlers() {
        val appSource = readSource("app/src/main/java/com/clhs/score/ui/ScoreApp.kt")
        val authenticatedSource = readSource("app/src/main/java/com/clhs/score/ui/AuthenticatedApp.kt")
        val navigationMotionSource = readSource("app/src/main/java/com/clhs/score/ui/navigation/AppNavigationMotion.kt")
        val loginSource = readSource("app/src/main/java/com/clhs/score/ui/WebViewLoginScreen.kt")
        val gradesSource = readSource("app/src/main/java/com/clhs/score/ui/GradesScreen.kt")

        assertTrue(authenticatedSource.contains("entry<WebViewLoginRoute>"))
        assertTrue(authenticatedSource.contains("onBack = { navigator.goBack() }"))
        assertFalse(appSource.contains("rememberNavBackStack"))
        assertFalse(appSource.contains("AnimatedContent"))
        assertFalse(loginSource.contains("BackHandler"))
        assertTrue(authenticatedSource.contains("NavDisplay("))
        assertTrue(authenticatedSource.contains("entryProvider<NavKey>"))
        assertFalse(authenticatedSource.contains("NavController"))
        assertTrue(authenticatedSource.contains("topLevelNavigationMetadata()"))
        val pocketMotionSource = readSource("app/src/main/java/com/clhs/score/ui/theme/PocketMotion.kt")
        assertTrue(navigationMotionSource.contains("PocketMotion.Navigation"))
        assertTrue(navigationMotionSource.contains("position"))
        assertTrue(pocketMotionSource.contains("defaultSpatialSpec()"))
        assertTrue(pocketMotionSource.contains("defaultEffectsSpec()"))
        assertTrue(pocketMotionSource.contains("fastEffectsSpec()"))
        assertFalse(navigationMotionSource.contains("scaleIn("))
        assertTrue(navigationMotionSource.contains("EnterTransition.None togetherWith ExitTransition.None"))
        val rootScaffold = readSource("app/src/main/java/com/clhs/score/ui/RootFloatingScaffold.kt")
        assertTrue(rootScaffold.contains("LocalNavAnimatedContentScope.current"))
        assertTrue(rootScaffold.contains("this@rootBodyMotion.animateEnterExit("))
        assertTrue(navigationMotionSource.contains("NavDisplay.PredictivePopTransitionKey"))
        assertFalse(navigationMotionSource.contains("tween("))
        assertFalse(gradesSource.contains("BackHandler"))
    }

    @Test
    fun successfulScheduleQueryRefreshesSharedOverview() {
        val source = readSource("app/src/main/java/com/clhs/score/ui/AuthenticatedApp.kt")
        val reportEffect = source
            .substringAfter("LaunchedEffect(uiState.report)")
            .substringBefore("com.clhs.score.ui.schedule.ScheduleScreen")

        assertTrue(reportEffect.contains("overviewViewModel.refresh()"))
    }

    @Test
    fun onboardingAndLoginRoutesHaveSeparateEntryPoints() {
        val appSource = readSource("app/src/main/java/com/clhs/score/ui/AuthenticatedApp.kt")
            .replace("\r\n", "\n")
        val navigationSource = readSource("app/src/main/java/com/clhs/score/ui/navigation/AppNavigation.kt")

        assertTrue(appSource.contains("entry<WelcomeRoute>"))
        assertTrue(appSource.contains("entry<AccountRoute>"))
        assertTrue(appSource.contains("if (!useFakeData) navigator.openWebViewLogin()"))
        assertTrue(appSource.contains("onLogin = ::requestLogin"))
        assertTrue(appSource.contains("if (!useFakeData && authState is AuthState.Authenticated && schoolWebsiteInBackStack)"))
        assertTrue(navigationSource.contains("@Serializable data object WebViewLoginRoute"))
        assertTrue(navigationSource.contains("@Serializable data object WelcomeRoute"))
    }

    @Test
    fun developerSettingsCanRestartOnboardingWithoutChangingSessionOwnership() {
        val developerSource = readSource("app/src/main/java/com/clhs/score/ui/DeveloperSettingsScreen.kt")
        val settingsSource = readSource("app/src/main/java/com/clhs/score/viewmodel/SettingsViewModel.kt")
        val appSource = readSource("app/src/main/java/com/clhs/score/ui/AuthenticatedApp.kt")

        assertTrue(developerSource.contains("重跑 onboarding"))
        assertTrue(developerSource.contains("onRestartOnboarding"))
        assertTrue(settingsSource.contains("fun restartOnboarding()"))
        assertTrue(appSource.contains("navigator.restartOnboarding()"))
    }

    @Test
    fun webViewProcessingOverlayStaysBelowNavigationBar() {
        val source = readSource("app/src/main/java/com/clhs/score/ui/WebViewLoginScreen.kt")
            .replace("\r\n", "\n")

        assertFalse(
            source.contains(
                "            }\n        }\n\n        androidx.compose.animation.AnimatedVisibility(\n            visible = isProcessingLogin",
            ),
        )
        assertTrue(source.contains(
            "                androidx.compose.animation.AnimatedVisibility(\n                    visible = isProcessingLogin",
        ))
    }

    @Test
    fun scheduleErrorsExposeRefreshAction() {
        val source = readSource("app/src/main/java/com/clhs/score/ui/schedule/ScheduleScreen.kt")

        assertTrue(source.contains("onClick = onRefresh"))
        assertTrue(source.contains("Text(\"重新整理\")"))
    }

    @Test
    fun currentWeekScheduleExposesForceReloadAction() {
        val source = readSource("app/src/main/java/com/clhs/score/ui/schedule/ScheduleScreen.kt")

        assertTrue(source.contains("if (uiState.report?.scope == ScheduleScope.CURRENT_WEEK)"))
        assertTrue(source.contains("contentDescription = \"強制重新載入本週課表\""))
        assertTrue(source.contains("enabled = !uiState.isLoading"))
    }

    @Test
    fun subjectCardExpansionKeepsTheValidatedCentralTweenTransition() {
        val componentSource = readSource("app/src/main/java/com/clhs/score/ui/SubjectComponents.kt")
        assertTrue(componentSource.contains("enter = PocketMotion.subjectDisclosureEnter()"))
        assertTrue(componentSource.contains("exit = PocketMotion.subjectDisclosureExit()"))

        val source = readSource("app/src/main/java/com/clhs/score/ui/theme/PocketMotion.kt")
        val visibilityTransition = source.substringAfter("internal fun subjectDisclosureEnter()")
            .substringBefore("data class Navigation")

        assertTrue(visibilityTransition.contains("durationMillis = 140"))
        assertTrue(visibilityTransition.contains("delayMillis = 40"))
        assertTrue(visibilityTransition.contains("durationMillis = 300"))
        assertTrue(visibilityTransition.contains("durationMillis = 90"))
        assertTrue(visibilityTransition.contains("durationMillis = 220"))
        assertTrue(visibilityTransition.contains("easing = LinearOutSlowInEasing"))
        assertTrue(visibilityTransition.countOccurrences("easing = FastOutSlowInEasing") == 3)
        assertFalse(visibilityTransition.contains("motion."))
    }

    @Test
    fun subjectTrendLegendSelectionCoversItsMinimumTouchTarget() {
        val source = readSource("app/src/main/java/com/clhs/score/ui/SubjectTrendScreen.kt")
        val legendItem = source
            .substringAfter("groupedLegend.forEach")
            .substringBefore("val filtersSection")

        val toggleableIndex = legendItem.indexOf(".toggleable(")
        val backgroundIndex = legendItem.indexOf(".background(")
        val minimumSizeIndex = legendItem.indexOf(".minimumInteractiveComponentSize()")
        val paddingIndex = legendItem.indexOf(".padding(horizontal = 4.dp, vertical = 2.dp)")

        assertTrue(toggleableIndex >= 0)
        assertTrue(backgroundIndex > toggleableIndex)
        assertTrue(minimumSizeIndex > backgroundIndex)
        assertTrue(paddingIndex > minimumSizeIndex)
    }

    @Test
    fun updateDialogKeepsFullMarkdownScrollable() {
        val source = readSource("app/src/main/java/com/clhs/score/ui/UpdateResultDialog.kt")

        assertTrue(source.contains("verticalScroll(rememberScrollState())"))
        assertTrue(source.contains("Markdown("))
        assertTrue(source.contains("content = result.releaseNotes"))
        assertFalse(source.contains("result.releaseNotes.take("))
    }

    @Test
    fun automaticUpdateCheckIsRootOwnedAndNonIntrusive() {
        val activity = readSource("app/src/main/java/com/clhs/score/MainActivity.kt")
        val personal = readSource("app/src/main/java/com/clhs/score/ui/PersonalScreen.kt")
        val settingsRepository = readSource("app/src/main/java/com/clhs/score/data/SettingsRepository.kt")
        val settingsViewModel = readSource("app/src/main/java/com/clhs/score/viewmodel/SettingsViewModel.kt")
        val uiUtils = readSource("app/src/main/java/com/clhs/score/ui/UiUtils.kt")

        assertTrue(activity.contains("LaunchedEffect(settingsVm)"))
        assertTrue(activity.contains("settingsVm.checkUpdateAutomatically()"))
        assertTrue(settingsRepository.contains("longPreferencesKey(\"lastUpdateCheckTime\")"))
        assertTrue(settingsRepository.contains("setLastKnownUpdate"))
        assertTrue(settingsRepository.contains("getLastKnownUpdate"))
        assertTrue(settingsViewModel.contains("showResult = false"))
        assertTrue(settingsViewModel.contains("restoreLastKnownUpdate"))
        assertTrue(uiUtils.contains("tint = MaterialTheme.colorScheme.onSurfaceVariant"))
        assertTrue(personal.contains("iconBadge = updateState is UpdateState.UpdateAvailable"))
        assertTrue(personal.contains("value = updateValue"))
        assertFalse(personal.contains("trailingBadge ="))
        assertTrue(personal.contains("MaterialTheme.colorScheme.error"))
    }

    @Test
    fun demoBiometricDisableDoesNotPersistFakeSession() {
        val source = readSource("app/src/main/java/com/clhs/score/MainActivity.kt")

        assertTrue(source.contains("if (!useFakeData && currentSession != null)"))
        assertTrue(source.contains("sessionStore.clearBiometricSession()"))
    }

    @Test
    fun emptyScheduleReportShowsActionableState() {
        val source = readSource("app/src/main/java/com/clhs/score/ui/schedule/ScheduleScreen.kt")

        assertTrue(source.contains("uiState.report.items.isEmpty()"))
        assertTrue(source.contains("\"查無課表資料\""))
        assertTrue(source.contains("onClick = onClearSelection"))
    }

    @Test
    fun scheduleUsesFixedSubjectAccentsWithoutPostProcessing() {
        val modelSource = readSource("app/src/main/java/com/clhs/score/data/ScheduleModels.kt")
        val screenSource = readSource("app/src/main/java/com/clhs/score/ui/schedule/ScheduleScreen.kt")

        assertTrue(modelSource.contains("darkPredefinedColors"))
        assertTrue(modelSource.contains("isDarkTheme: Boolean"))
        assertTrue(screenSource.contains("val isDarkTheme = scheduleColorScheme.background.luminance() < 0.5f"))
        assertTrue(screenSource.contains("accent = Color(subjectColor)"))
        assertFalse(screenSource.contains("rawBgColor"))
        assertFalse(screenSource.contains("accentColor"))
    }

    @Test
    fun gradeComparisonTextShowsUnsignedDifference() {
        val source = readSource("app/src/main/java/com/clhs/score/ui/GradesScreen.kt")
        val comparisonText = source
            .substringAfter("private fun diffSentence(")
            .substringBefore("@Composable")

        assertFalse(comparisonText.contains("signedValue"))
        assertTrue(comparisonText.contains("abs(diff)"))
    }

    @Test
    fun gradesOverviewDoesNotRenderLearningAdvice() {
        val source = readSource("app/src/main/java/com/clhs/score/ui/GradesScreen.kt")

        assertFalse(source.contains("PriorityInsightCard"))
        assertFalse(source.contains("學習建議"))
    }

    @Test
    fun widgetPreferencesAreStoredPerGlanceId() {
        val widgetSource = readSource("app/src/main/java/com/clhs/score/widget/ScheduleWidget.kt")
        val appSource = readSource("app/src/main/java/com/clhs/score/ui/ScoreApp.kt")
        val scheduleSource = readSource("app/src/main/java/com/clhs/score/ui/schedule/ScheduleScreen.kt")

        assertTrue(widgetSource.contains("getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)"))
        assertTrue(widgetSource.contains("state[WidgetShowTeacherKey] = preferences.showTeacher"))
        assertTrue(widgetSource.contains("state[WidgetAfterLastClassKey] = preferences.afterLastClass"))
        assertFalse(widgetSource.contains("cacheStore.getWidgetPreferences()"))
        assertFalse(widgetSource.contains("cacheStore.getWidgetAfterLastClass()"))
        assertFalse(appSource.contains("WidgetSettingsRoute"))
        assertFalse(scheduleSource.contains("Widget 設定"))
    }

    @Test
    fun widgetConfigurationUsesFullGlanceSyncWithoutBlockingComposition() {
        val activitySource = readSource("app/src/main/java/com/clhs/score/widget/WidgetConfigurationActivity.kt")
        val widgetSource = readSource("app/src/main/java/com/clhs/score/widget/ScheduleWidget.kt")

        val setContentBlock = activitySource.substringAfter("setContent {")
        assertTrue("Widget configuration must use the Android SplashScreen API while loading settings", activitySource.contains("installSplashScreen()"))
        assertTrue(activitySource.contains("splashScreen.setKeepOnScreenCondition { launchConfiguration.value == null }"))
        assertTrue(activitySource.contains("splashScreen.setOnExitAnimationListener"))
        assertTrue(activitySource.contains("val iconView = runCatching { splashScreenView.iconView }.getOrNull()"))
        assertTrue(activitySource.contains("if (iconView == null)"))
        assertFalse(activitySource.contains("splashScreenView.iconView.animate()"))
        assertFalse("Splash exit must not fade the whole splash window over app content", activitySource.contains("splashScreenView.view.animate()"))
        assertTrue(activitySource.contains(".alpha(0f)"))
        assertTrue(activitySource.contains(".scaleX(0.96f)"))
        assertTrue(activitySource.contains(".scaleY(0.96f)"))
        assertTrue(activitySource.contains(".setDuration(200L)"))
        assertTrue(activitySource.contains("DecelerateInterpolator()"))
        assertTrue(activitySource.contains(".withEndAction { splashScreenView.remove() }"))
        assertFalse("Widget configuration must not block inside composition", setContentBlock.contains("runBlocking"))
        assertFalse("Widget configuration must not block the main thread while loading settings", activitySource.contains("runBlocking"))
        assertTrue(activitySource.contains("lifecycleScope.launch"))
        assertTrue(activitySource.contains("collectAsStateWithLifecycle"))
        assertFalse("Widget configuration must not render the first frame with a guessed default theme", activitySource.contains("initialValue = AppSettings()"))
        assertTrue(activitySource.contains("initialValue = configuration.settings"))
        assertTrue(activitySource.contains("loadScheduleWidgetPreferences(applicationContext, appWidgetId)"))
        assertTrue(activitySource.contains("saveScheduleWidgetPreferences("))
        assertTrue(activitySource.contains("syncAllScheduleWidgets(applicationContext)"))
        assertFalse(activitySource.contains("ScheduleWidget().update(applicationContext, glanceId)"))
        assertTrue(widgetSource.contains("getGlanceIdBy(appWidgetId)"))
        assertTrue(widgetSource.contains("if (this[WidgetShowTeacherKey] == null)"))
        assertFalse(widgetSource.contains("preferences: ScheduleWidgetPreferences? = null"))
    }

    @Test
    fun widgetConfigurationCancellationKeepsTheWidgetIdResult() {
        val source = readSource("app/src/main/java/com/clhs/score/widget/WidgetConfigurationActivity.kt")
        val dismissBlock = source.substringAfter("onDismiss = {").substringBefore("},")

        assertFalse(dismissBlock.contains("setResult(RESULT_CANCELED)"))
    }

    @Test
    fun widgetConfigurationDoesNotKeepSplashForeverWhenLoadingFails() {
        val source = readSource("app/src/main/java/com/clhs/score/widget/WidgetConfigurationActivity.kt")
        val loadingBlock = source
            .substringAfter("lifecycleScope.launch {")
            .substringBefore("setContent {")

        assertTrue(loadingBlock.contains("catch (error: Exception)"))
        assertTrue(loadingBlock.contains("if (error is CancellationException) throw error"))
        assertTrue(loadingBlock.contains("finish()"))
    }

    @Test
    fun existingGlobalWidgetPreferencesMigrateToPerWidgetState() {
        val cacheSource = readSource("app/src/main/java/com/clhs/score/data/GradeCacheStore.kt")
        val widgetSource = readSource("app/src/main/java/com/clhs/score/widget/ScheduleWidget.kt")

        assertTrue(cacheSource.contains("loadLegacyWidgetPreferences"))
        assertTrue(cacheSource.contains("clearLegacyWidgetPreferences"))
        assertTrue(widgetSource.contains("legacyPreferences"))
        assertTrue(widgetSource.contains("if (this[WidgetShowTeacherKey] == null)"))
        assertTrue(widgetSource.contains("if (this[WidgetShowClassroomKey] == null)"))
        assertTrue(widgetSource.contains("if (this[WidgetShowTimeKey] == null)"))
        assertTrue(widgetSource.contains("cacheStore.clearLegacyWidgetPreferences()"))
        val loadPreferences = widgetSource
            .substringAfter("internal suspend fun loadScheduleWidgetPreferences(")
            .substringBefore("internal suspend fun saveScheduleWidgetPreferences(")
        assertFalse(loadPreferences.contains("loadLegacyWidgetPreferences"))
    }

    @Test
    fun widgetConfigurationSaveIsSingleFlightAndReportsFailure() {
        val source = readSource("app/src/main/java/com/clhs/score/ui/schedule/WidgetSettingsScreen.kt")

        assertTrue(source.contains("enabled = !isSaving"))
        assertTrue(source.contains("if (!isSaving)"))
        assertTrue(source.contains("if (error is CancellationException) throw error"))
        assertTrue(source.contains("snackbarHostState.showSnackbar(\"儲存失敗，請再試一次\")"))
    }

    @Test
    fun scheduleRefreshBoundaryUsesObservableTimeState() {
        val source = readSource("app/src/main/java/com/clhs/score/ui/schedule/ScheduleScreen.kt")

        assertTrue(source.contains("var scheduleNow by remember(uiState.report)"))
        assertTrue(source.contains("delay(Duration.between(scheduleNow, refreshAt).toMillis().milliseconds)"))
        assertFalse(source.contains("shouldRefreshAt(LocalDateTime.now())"))
    }

    @Test
    fun widgetSynchronizationRunsOffTheMainThread() {
        val source = readSource("app/src/main/java/com/clhs/score/widget/ScheduleWidget.kt")

        val syncEntry = source.substringAfter("suspend fun syncAllScheduleWidgets(")
        assertTrue(syncEntry.startsWith("\n    context: Context,"))
        assertTrue(syncEntry.contains(") = withContext(Dispatchers.IO) {"))
        assertTrue(syncEntry.contains("scheduleWidgetSyncMutex.withLock"))
    }

    @Test
    fun appThemeChangesRefreshScheduleWidgets() {
        val source = readSource("app/src/main/java/com/clhs/score/MainActivity.kt")

        assertTrue(source.contains("LaunchedEffect(appSettings.themeMode, appSettings.dynamicColor, appSettings.amoledBlack)"))
        assertTrue(source.contains("com.clhs.score.widget.syncAllScheduleWidgets(applicationContext, appSettings)"))
        assertTrue(source.contains("com.clhs.score.widget.refreshScheduleWidgetPreview(applicationContext, appSettings)"))
    }

    @Test
    fun mainActivityUsesRealSettingsForFirstFrameTheme() {
        val activitySource = readSource("app/src/main/java/com/clhs/score/MainActivity.kt")
        val settingsViewModelSource = readSource("app/src/main/java/com/clhs/score/viewmodel/SettingsViewModel.kt")

        val setContentIndex = activitySource.indexOf("setContent {")
        val scoreThemeIndex = activitySource.indexOf("ScoreTheme(")
        val readyGateIndex = activitySource.indexOf("if (!isReady)", scoreThemeIndex)

        assertTrue("MainActivity must use the Android SplashScreen API while loading settings", activitySource.contains("installSplashScreen()"))
        assertTrue(activitySource.contains("launchSettings.value == null"))
        assertFalse(activitySource.contains("isSessionRestoreComplete"))
        assertTrue(activitySource.contains("val authState by scoreVm.authState.collectAsStateWithLifecycle()"))
        assertTrue(activitySource.contains("splashScreen.setOnExitAnimationListener"))
        assertTrue(activitySource.contains("val iconView = runCatching { splashScreenView.iconView }.getOrNull()"))
        assertTrue(activitySource.contains("if (iconView == null)"))
        assertFalse(activitySource.contains("splashScreenView.iconView.animate()"))
        assertFalse("Splash exit must not fade the whole splash window over app content", activitySource.contains("splashScreenView.view.animate()"))
        assertTrue(activitySource.contains(".alpha(0f)"))
        assertTrue(activitySource.contains(".scaleX(0.96f)"))
        assertTrue(activitySource.contains(".scaleY(0.96f)"))
        assertTrue(activitySource.contains(".setDuration(200L)"))
        assertTrue(activitySource.contains("DecelerateInterpolator()"))
        assertTrue(activitySource.contains(".withEndAction {"))
        assertTrue("MainActivity must load persisted settings asynchronously", activitySource.contains("lifecycleScope.launch"))
        assertTrue("MainActivity must not block the main thread while loading settings", !activitySource.contains("runBlocking"))
        assertTrue("MainActivity must wait for persisted settings before creating app content", setContentIndex < scoreThemeIndex)
        assertTrue(activitySource.contains("val initialSettings = launchSettings.value ?: return@setContent"))
        assertTrue(activitySource.contains("SettingsViewModel.factory(applicationContext, initialSettings)"))
        assertTrue("Readiness gate must be inside ScoreTheme so the window does not flash the manifest light theme", scoreThemeIndex in 0 until readyGateIndex)
        assertTrue(settingsViewModelSource.contains("initialSettings: AppSettings = AppSettings()"))
        assertTrue(settingsViewModelSource.contains("private val _settings = MutableStateFlow(initialSettings)"))
    }

    @Test
    fun splashExitReappliesPersistedSystemBarAppearance() {
        val source = readSource("app/src/main/java/com/clhs/score/MainActivity.kt")
        val splashExitBlock = source
            .substringAfter("splashScreen.setOnExitAnimationListener")
            .substringBefore("super.onCreate(savedInstanceState)")
        val nullIconBlock = splashExitBlock
            .substringAfter("if (iconView == null)")
            .substringBefore("return@setOnExitAnimationListener")
        val animatedExitBlock = splashExitBlock
            .substringAfter(".withEndAction {")
            .substringBefore("}", missingDelimiterValue = "")
        val systemBarAppearanceBlock = source
            .substringAfter("private fun applyLaunchSystemBarAppearance()")
            .substringBefore("private fun clearWidgetScheduleCache()")

        assertTrue(
            "Android 12 splash exit reapplies the manifest theme, so the app theme must win afterward",
            nullIconBlock.indexOf("splashScreenView.remove()") in
                0 until nullIconBlock.indexOf("applyLaunchSystemBarAppearance()"),
        )
        assertTrue(
            "Animated splash exit must reapply the app theme after removing the splash view",
            animatedExitBlock.indexOf("splashScreenView.remove()") in
                0 until animatedExitBlock.indexOf("applyLaunchSystemBarAppearance()"),
        )
        assertTrue(systemBarAppearanceBlock.contains("ThemeMode.DARK -> true"))
        assertTrue(systemBarAppearanceBlock.contains("ThemeMode.LIGHT -> false"))
        assertTrue(systemBarAppearanceBlock.contains("Configuration.UI_MODE_NIGHT_MASK"))
        assertTrue(systemBarAppearanceBlock.contains("isAppearanceLightStatusBars = !useDark"))
        assertTrue(systemBarAppearanceBlock.contains("isAppearanceLightNavigationBars = !useDark"))
    }

    @Test
    fun scheduleWidgetReadsPreferencesFromGlanceState() {
        val source = readSource("app/src/main/java/com/clhs/score/widget/ScheduleWidget.kt")

        assertTrue("Widget must sync data to Glance State to support reactive updates", source.contains("updateAppWidgetState"))
        assertTrue("Widget content must read preferences using currentState", source.contains("currentState(key ="))
        assertTrue("Each widget must load its own preferences from Glance state", source.contains("getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)"))
        assertTrue("Widget theme mode must be part of Glance state", source.contains("WidgetThemeModeKey"))
        assertTrue("Widget dynamic color setting must be part of Glance state", source.contains("WidgetDynamicColorKey"))
        assertTrue("Widget AMOLED setting must be part of Glance state", source.contains("WidgetAmoledBlackKey"))
        assertTrue("Widget colors must use the current Glance theme settings", source.contains("getWidgetColorProviders(context, currentWidgetSettings(AppSettings()))"))
    }

    @Test
    fun smallScheduleWidgetPrioritizesOneUsefulLesson() {
        val source = readSource("app/src/main/java/com/clhs/score/widget/ScheduleWidget.kt")
        val previewSource = readSource("app/src/main/java/com/clhs/score/ui/schedule/WidgetSettingsScreen.kt")

        assertTrue(source.contains("widgetSize.height < 160.dp"))
        assertTrue(source.contains("widgetSize.width < 220.dp"))
        assertTrue(source.contains("sections.prioritized.take(1)"))
        assertTrue(source.contains("showTeacher && !isShort && !isNarrow"))
        assertTrue(source.contains("showClassroom && !isShort && !isNarrow"))
        assertFalse(source.contains("text = \"上課中\""))
        assertFalse(previewSource.contains("text = \"上課中\""))
        assertFalse(previewSource.contains("PreviewTitleBar("))
        assertTrue(source.contains("horizontalPadding = if (isShort) 8.dp else 16.dp"))
        assertTrue(previewSource.contains("horizontal = if (isShort) 8.dp else 16.dp"))
        assertTrue(source.contains(".padding(top = if (isShort) 8.dp else 12.dp, bottom = if (isShort) 4.dp else 8.dp)"))
        assertTrue(previewSource.contains(".padding(top = if (isShort) 8.dp else 12.dp, bottom = if (isShort) 4.dp else 8.dp)"))
        assertTrue(previewSource.contains("android.R.dimen.system_app_widget_background_radius"))
    }

    @Test
    fun scheduleWidgetKeepsTierOnePlatformIntegration() {
        val source = readSource("app/src/main/java/com/clhs/score/widget/ScheduleWidget.kt")
        val provider = readSource("app/src/main/res/xml/schedule_widget_info.xml")
        val providerV31 = readSource("app/src/main/res/xml-v31/schedule_widget_info.xml")
        val manifest = readSource("app/src/main/AndroidManifest.xml")

        assertTrue(source.contains("Scaffold("))
        assertFalse(source.contains("TitleBar("))
        assertTrue(source.contains("override val previewSizeMode = SizeMode.Responsive("))
        assertTrue(source.contains("override suspend fun providePreview("))
        assertTrue(source.contains("setWidgetPreviews(ScheduleWidgetReceiver::class)"))
        assertTrue(source.contains("generatedPreviewCategories"))
        assertTrue(source.contains("ScheduleWidgetPreviewRevision"))
        assertTrue(source.contains("actionStartActivity(intent)"))

        assertTrue(provider.contains("android:minResizeWidth=\"180dp\""))
        assertTrue(provider.contains("android:minResizeHeight=\"110dp\""))
        assertTrue(providerV31.contains("android:maxResizeWidth=\"460dp\""))
        assertTrue(providerV31.contains("android:maxResizeHeight=\"500dp\""))
        assertTrue(provider.contains("android:initialLayout=\"@layout/glance_default_loading_layout\""))
        assertTrue(provider.contains("android:previewImage=\"@drawable/schedule_widget_preview\""))
        assertTrue(provider.contains("android:description=\"@string/schedule_widget_description\""))
        assertTrue(provider.contains("android:widgetFeatures=\"reconfigurable\""))
        assertTrue(manifest.contains("android:label=\"@string/schedule_widget_name\""))
    }

    @Test
    fun analyticsLayerDoesNotDefineSensitiveParameters() {
        val sources = listOf(
            "app/src/main/java/com/clhs/score/analytics/AnalyticsEvents.kt",
            "app/src/main/java/com/clhs/score/analytics/AnalyticsLogger.kt",
            "app/src/main/java/com/clhs/score/analytics/AnalyticsParameterSanitizer.kt",
            "app/src/main/java/com/clhs/score/analytics/FirebaseAnalyticsLogger.kt",
            "app/src/main/java/com/clhs/score/analytics/UsageStatisticsStore.kt",
        ).joinToString("\n") { path -> readSource(path) }

        val forbiddenTerms = listOf(
            "setUserId",
            "studentNo",
            "studentName",
            "className",
            "seatNo",
            "apiToken",
            "cookies",
            "rawResult",
            "scoreValue",
            "url",
        )
        forbiddenTerms.forEach { term ->
            assertFalse("Analytics layer must not expose sensitive data: $term", sources.contains(term))
        }
    }

    @Test
    fun developerDiagnosticsAreNotSharedThroughBroadTextIntent() {
        val source = readSource("app/src/main/java/com/clhs/score/ui/DeveloperSettingsScreen.kt")

        assertTrue(source.contains("ClipData.newPlainText(\"CLHS Pocket 診斷包\", text)"))
        assertFalse("Diagnostic reports must not be sent through ACTION_SEND", source.contains("ACTION_SEND"))
        assertFalse("Diagnostic reports must not be embedded in EXTRA_TEXT", source.contains("EXTRA_TEXT"))
        assertFalse("Diagnostic reports must not keep a broad share helper", source.contains("shareText("))
    }

    @Test
    fun recurringOnlyCalendarStillExplainsSkippedEvents() {
        val source = readSource("app/src/main/java/com/clhs/score/ui/calendar/SchoolCalendarScreen.kt")
        val emptyState = source
            .substringAfter("private fun CalendarAgenda(")
            .substringBefore("private fun CalendarEventCard(")

        assertTrue(emptyState.contains("uiState.skippedRecurringEvents"))
        assertTrue(emptyState.contains("部分重複活動"))
    }

    private fun readSource(relativePath: String): String {
        val root = findAndroidRoot()
        return Files.readString(root.resolve(relativePath)).replace("\r\n", "\n")
    }

    private fun findAndroidRoot(): Path {
        var current = Paths.get("").toAbsolutePath()
        while (true) {
            if (Files.exists(current.resolve("settings.gradle.kts")) &&
                Files.exists(current.resolve("app/src/main/java/com/clhs/score/MainActivity.kt"))
            ) {
                return current
            }
            current = current.parent ?: error("Unable to locate Android project root")
        }
    }

    private fun String.countOccurrences(term: String): Int =
        windowed(term.length).count { it == term }
}
