# 測試策略

預設只執行本機編譯、單元測試與 Lint；只有使用者明確要求時才操作實機／emulator、安裝 APK、截圖或產生 Baseline Profile。編譯通過不代表完成視覺驗收。

## 分層

| 類型 | 位置／命令 | 目的 |
| --- | --- | --- |
| JVM unit tests | `android/app/src/test/`、`.\gradlew.bat test` | parser、分析、ViewModel、cache 與安全規則 |
| 架構邊界測試 | `ArchitectureBoundaryTest` | 防止 Widget、WebView、session、通知邊界被繞過 |
| Guest／導航／首次設定 | `OverviewAuthTest`、`AppNavigationTest`、`SettingsRepositoryTest`（JVM）；`AuthGateTest`、`AppNavigationStateTest`（androidTest，需明確要求） | 驗證公開內容、受保護 route、四個 stack 與 v2 首次設定旗標 |
| 公告更新提醒／總覽／天氣 | `AnnouncementReminderRepositoryTest`、`AnnouncementReminderScreenTest`、`AnnouncementWorkInfoTest`、`OverviewCoordinatorTest`、`WeatherRepositoryTest` | 驗證公開資料背景工作、未讀基準、來源快取與部分失敗 |
| Lint | `.\gradlew.bat lint` | Android 靜態品質檢查 |
| Release／效能 | `:app:assembleRelease`、`:app:generateBaselineProfile -PuseFakeData=true`、`:benchmark:connectedBenchmarkReleaseAndroidTest` | 驗證縮減版與 Baseline Profile；Macrobenchmark 數據以實體裝置為準 |
| 裝置測試 | `android/app/src/androidTest/`、`.\gradlew.bat connectedDebugAndroidTest` | Android framework / Compose 行為 |
| 手動驗證 | 模擬器或實機 | Widget、通知、WebView、匯出與視覺調整 |

## 變更對應檢查

- 改 repository、parser 或 ViewModel：新增／更新 unit test，跑 `test`。
- 改 WebView、session、Widget、deep link、提醒或通知：先跑 `ArchitectureBoundaryTest`，再跑對應本機測試；真實入口的裝置驗證需使用者明確要求，且不得紀錄真實憑證。
- 改登入／導航／首次設定：補跑 OverviewAuthTest、AppNavigationTest 與 SettingsRepositoryTest；AuthGateTest／AppNavigationStateTest 屬於 androidTest，需明確要求裝置驗證。
- 改公告更新提醒／總覽／天氣：補跑公告更新提醒、OverviewCoordinator 與 WeatherRepository 相關測試；公告更新提醒不得用校務 Session 或 FCM topic。
- 改 Compose UI：跑 `lint`、`assembleDebug`，有明確裝置驗證要求時，再以 fake data 做截圖或互動檢查。
- 改 workflow／文件：檢查 YAML 或 Markdown links，並保留既有 release 流程的 secrets 邊界。

CI 會在 `main` push 與 PR 執行 Debug unit tests、lint 和 Debug assemble；它不是裝置測試的替代品。
