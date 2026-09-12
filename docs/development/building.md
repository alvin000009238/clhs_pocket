# 建置與執行

所有 Gradle 指令都在 `android/` 執行。

| 目的 | 指令 |
| --- | --- |
| Debug APK | `.\gradlew.bat assembleDebug` |
| 安裝 Debug APK | `.\gradlew.bat installDebug` |
| 假資料 Debug APK | `.\gradlew.bat assembleDebug -PuseFakeData=true` |
| JVM unit tests | `.\gradlew.bat test` |
| Android lint | `.\gradlew.bat lint` |
| Release-like 縮減版 | `.\gradlew.bat :app:assembleRelease` |
| Baseline Profile | `.\gradlew.bat :app:generateBaselineProfile -PuseFakeData=true` |
| Macrobenchmark（實體裝置） | `.\gradlew.bat :benchmark:connectedBenchmarkReleaseAndroidTest` |
| 裝置測試 | `.\gradlew.bat connectedDebugAndroidTest` |
| signed release（CI） | 由 `v*` tag 觸發 release workflow |

Debug build 不需要 release keystore。release 只在 GitHub Actions 使用 repository secrets；不要在本機建立、提交或貼出任何正式 keystore 與密碼。

Release workflow 會由 `CHANGELOG.md` 取出與 tag 對應的版本說明、建置 arm64-v8a APK、驗證簽章與 ABI，再建立 GitHub Release。推送 tag 前先完成並請維護者確認 changelog。

Macrobenchmark 數據應在實體 Android 裝置取得；模擬器可用於功能檢查，但不把模擬器數字當成正式效能基準。
