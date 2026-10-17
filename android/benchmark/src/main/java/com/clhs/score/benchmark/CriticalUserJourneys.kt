package com.clhs.score.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

internal const val TARGET_PACKAGE = "com.clhs.score.demo"
internal const val UI_TIMEOUT_MILLIS = 5_000L

internal fun MacrobenchmarkScope.ensureDemoSession() {
    startActivityAndWait()
    if (!device.wait(Until.hasObject(By.desc("首頁")), UI_TIMEOUT_MILLIS)) {
        checkNotNull(device.wait(Until.findObject(By.text("開始設定")), UI_TIMEOUT_MILLIS)) {
            "Demo onboarding did not show welcome"
        }.click()
        checkNotNull(device.wait(Until.findObject(By.text("繼續")), UI_TIMEOUT_MILLIS)) {
            "Demo onboarding did not show appearance settings"
        }.click()
        check(device.wait(Until.gone(By.text("選擇喜歡的外觀")), UI_TIMEOUT_MILLIS)) {
            "Demo onboarding did not leave appearance settings"
        }
        checkNotNull(
            device.wait(Until.findObject(By.text("先略過|繼續".toPattern())), UI_TIMEOUT_MILLIS),
        ) {
            "Demo onboarding did not show notification choice"
        }.click()
        checkNotNull(device.wait(Until.findObject(By.text("使用學校帳號登入")), UI_TIMEOUT_MILLIS)) {
            "Demo onboarding did not show demo login"
        }.click()
    }
    check(device.wait(Until.hasObject(By.desc("首頁")), UI_TIMEOUT_MILLIS)) {
        "Demo onboarding did not reach home"
    }
    pressHome()
}

internal fun MacrobenchmarkScope.openDemoDestination(
    destinationDescription: String,
    contentText: String,
) {
    val destination = checkNotNull(
        device.wait(Until.findObject(By.desc(destinationDescription)), UI_TIMEOUT_MILLIS),
    ) {
        "Demo destination not found: $destinationDescription"
    }
    destination.click()
    check(device.wait(Until.hasObject(By.text(contentText)), UI_TIMEOUT_MILLIS)) {
        "Demo destination $destinationDescription did not show $contentText"
    }
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
