package com.clhs.score.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

internal const val TARGET_PACKAGE = "com.clhs.score"
internal const val UI_TIMEOUT_MILLIS = 5_000L
private const val DEMO_SKIP_TIMEOUT_MILLIS = 3_000L

internal fun MacrobenchmarkScope.ensureDemoSession() {
    startActivityAndWait()
    if (device.findObject(By.desc("總覽")) == null) {
        device.wait(Until.findObject(By.text("跳過")), DEMO_SKIP_TIMEOUT_MILLIS)?.click()
        device.wait(Until.hasObject(By.desc("總覽")), UI_TIMEOUT_MILLIS)
    }
    pressHome()
}

internal fun MacrobenchmarkScope.openDemoDestination(
    destinationDescription: String,
    contentText: String,
): Boolean {
    if (device.findObject(By.desc(destinationDescription)) == null) {
        device.wait(Until.findObject(By.text("跳過")), DEMO_SKIP_TIMEOUT_MILLIS)?.click()
            ?: return false
    }

    val destination = device.wait(
        Until.findObject(By.desc(destinationDescription)),
        UI_TIMEOUT_MILLIS,
    ) ?: return false
    destination.click()
    return device.wait(Until.hasObject(By.text(contentText)), UI_TIMEOUT_MILLIS)
}

internal fun MacrobenchmarkScope.scrollContent() {
    device.findObject(By.text("時間"))?.setGestureMargin(device.displayWidth / 5)
    device.swipe(
        device.displayWidth / 2,
        device.displayHeight * 4 / 5,
        device.displayWidth / 2,
        device.displayHeight / 5,
        400,
    )
}

internal fun MacrobenchmarkScope.scrollGradesContent() {
    device.findObject(By.desc("成績"))?.setGestureMargin(device.displayWidth / 5)
    device.swipe(
        device.displayWidth / 2,
        device.displayHeight * 4 / 5,
        device.displayWidth / 2,
        device.displayHeight / 5,
        400,
    )
}
