# 測試策略

預設只執行本機編譯、單元測試與 Lint；只有使用者明確要求時才操作實機／emulator、安裝 APK、截圖或產生 Baseline Profile。編譯通過不代表完成視覺驗收。

## 測試品質與成本

日常決策以 [AGENTS.md 的 Testing Strategy & Test Quality](../../AGENTS.md#testing-strategy--test-quality) 為準。新增前先列出真實回歸與影響、查既有保護，再選足以驗證的最低成本層級；不以 Coverage、測試數量或每個修改檔案都配一個測試作為目標。既有測試可以整理，但移除前需指出重要斷言的替代保護。

JVM 測試撰寫不需先操作裝置。需要撰寫或修改裝置測試時，先依下方裝置驗證流程確認互動，再使用語意定位與明確等待。記憶體 DAO 驗證 policy，不代表真實 Room transaction；JVM pass 不代表 Android Keystore、Compose 或 OEM 背景行為已驗收。

Coroutine 自有 timer 使用同一 test scheduler 與虛擬時間；實際 HTTP／thread 邊界保留真實同步與有上限的等待。MockWebServer 請求可共用 `data/MockWebServerRequests.kt` 的 `awaitRequest()`，逾時應明確失敗。保留 server、DataStore scope、ViewModel job 與全域 dispatcher／timezone 的清理。

本次套件整理與限制見 [測試套件審查](../audit/test-suite-audit.md)。

## 分層

| 類型 | 位置／命令 | 目的 |
| --- | --- | --- |
| JVM unit tests | `android/app/src/test/`、`.\gradlew.bat :app:testDemoDebugUnitTest` | parser、分析、ViewModel、cache 與安全規則 |
| 架構邊界測試 | `ArchitectureBoundaryTest` | 防止 Widget、WebView、session、通知邊界被繞過 |
| Guest／導航／首次設定 | `OverviewAuthTest`、`AppNavigationTest`、`SettingsRepositoryTest`（JVM）；`AuthGateTest`、`AppNavigationStateTest`（androidTest，需明確要求） | 驗證公開內容、受保護 route、四個 stack 與 v2 首次設定旗標 |
| 公告更新提醒／首頁／天氣 | `AnnouncementReminderRepositoryTest`、`AnnouncementReminderScreenTest`、`AnnouncementWorkInfoTest`、`OverviewCoordinatorTest`、`WeatherRepositoryTest` | 驗證公開資料背景工作、未讀基準、來源快取與部分失敗 |
| Lint | `.\gradlew.bat :app:lintDemoDebug` | Android 靜態品質檢查 |
| Release／效能 | `:app:assembleProductionRelease`、`:app:generateDemoReleaseBaselineProfile`、`:benchmark:connectedBenchmarkReleaseAndroidTest` | 驗證縮減版與 Baseline Profile；Macrobenchmark 數據以實體裝置為準 |
| 裝置測試 | `android/app/src/androidTest/`、`.\gradlew.bat connectedDemoDebugAndroidTest` | Android framework / Compose 行為 |
| 手動驗證 | 模擬器或實機 | Widget、通知、WebView、匯出與視覺調整 |

## 變更對應檢查

- 改 repository、parser 或 ViewModel：先檢查既有測試；只對缺少保護的實際風險新增／更新 unit test，先跑相關 owner tests，再完成既有交付 gate。
- 改 WebView、session、Widget、deep link、提醒或通知：先跑 `ArchitectureBoundaryTest`，再跑對應本機測試；真實入口的裝置驗證需使用者明確要求，且不得紀錄真實憑證。
- 改登入／導航／首次設定：補跑 OverviewAuthTest、AppNavigationTest 與 SettingsRepositoryTest；AuthGateTest／AppNavigationStateTest 屬於 androidTest，需明確要求裝置驗證。
- 改公告更新提醒／首頁／天氣：補跑公告更新提醒、OverviewCoordinator 與 WeatherRepository 相關測試；公告更新提醒不得用校務 Session 或 FCM topic。
- 改 Compose UI：跑 `:app:lintDemoDebug`、`:app:assembleDemoDebug`，有明確裝置驗證要求時，再以 fake data 做截圖或互動檢查。
- 改 workflow／文件：檢查 YAML 或 Markdown links，並保留既有 release 流程的 secrets 邊界。

## 裝置驗證工作流

使用開發環境可用的裝置／模擬器工具。裝置驗證不取代 JVM、Lint、組建或既有 `androidTest`。

1. 先完成相關本機測試與 Debug APK 組建。模擬器驗證使用 `demoDebug`：APK 位於 `android/app/build/outputs/apk/demo/debug/app-demo-debug.apk`，套件 ID 為 `com.clhs.score.demo.debug`。不要使用已移除的 `-PuseFakeData` 或舊的 `com.clhs.score.debug` 套件。
2. 列出可用裝置，多台或目標不明時請使用者選擇，再開啟指定模擬器。用 `adb devices -l` 核對 online serial，不得自行改用實機。
3. 每個操作明確指定同一裝置，保留工具要求的 target／session 設定。優先使用 resource ID、文字或其他語意定位；無動態定位時才使用截圖確認的座標。撰寫裝置測試前先探索相關互動，測試碼使用明確等待條件。
4. 原地更新 Demo APK 並啟動；簽章不符時先比較簽章，保留資料，不自動 uninstall、wipe data 或 factory reset。使用 ADB 操作、Logcat 或檔案傳輸時，明確指定 `-s <serial>`。
5. 啟動失敗先確認 emulator process 與錯誤，不重複啟動同一 AVD。需要額外日誌時捕捉 stdout／stderr；Windows 背景程序使用 `Start-Process -WindowStyle Hidden`。若報 RAM commit 不足，先備份 AVD 設定，再降低該測試 AVD RAM；不要用 Wipe Data 解決。舊 session 鎖定時核對 session 建立時間與裝置是否已重啟，僅關閉確認失效的 session。
6. 留存必要截圖、日誌或其他驗證證據，分別回報 APK、AVD／serial、Android 版本、操作結果、crash 與未驗證項目。測試後還原字體、尺寸與主題等臨時設定。單一模擬器流程通過不等同全面視覺、無障礙或實機驗收。

CI 在 `dev`／`main` push，以及目標為兩者的 PR 執行 `:app:testDemoDebugUnitTest :app:testProductionDebugUnitTest --tests com.clhs.score.FlavorConfigurationTest :app:lintDemoDebug :app:assembleDemoDebug :app:assembleProductionDebug --warning-mode all`。固定 check 名稱為 `Test, lint, and assemble Debug APK`；沒有 workflow path filter 或 job 條件跳過，文件 PR 也會回報。CI 使用 JDK 25、Android API 37、contents read 權限，不讀取 release secrets；它不是裝置測試的替代品。

上述 `--tests` 是 Production test task 的選項，Demo 仍執行完整 JVM suite；它不是全域篩選器。若需要驗證 Production 的完整 suite，省略該 task 的 `--tests`。

JVM 測試失敗時，Gradle 輸出完整 assertion 與 stack trace，包含 coroutine 內的失敗行；不要只用測試函式起始行推測原因。首頁定向刷新測試先等待公開來源載入完成，再記下請求基準；自有 coroutine 的時序使用同一 test scheduler，不用固定睡眠等待 flow 傳播。

CI 也執行 release guard 與 Profile validator 的離線測試。從 repository root 執行：

```powershell
python -m unittest discover -s .github/scripts -p "test_*.py" -v
actionlint
```

Release guard 測試只在暫存 Git repository 建立 tag，覆蓋 main tag、未合併 dev tag、非法版本、缺少／空白 changelog，以及 versionCode 退版。Profile 測試使用暫存檔案，驗證兩份來源必須各有 app 規則，並拒絕缺檔、空白、僅註解及其他 namespace 的內容；不操作裝置。

Release 使用已提交的 Profile，在解碼 signing key 前驗證來源，建置後檢查 APK 內的 `assets/dexopt/baseline.prof`。獨立的 **Android Baseline Profiles** workflow 僅接受手動觸發，使用 Demo 與 API 35 Gradle 管理的模擬器產生可供人工提交的兩份檔案。更新步驟與首次啟用限制見 [Release 流程](release.md#更新-baseline-與-startup-profile)。

CodeQL 保留 main push、PR 與每週掃描。2026-09-13 run `34747861031` 的註記確認 java-kotlin autobuild 失敗，因此改成安裝 Android SDK，從 `android/` 手動 `:app:assembleProductionDebug`；停用 build/configuration cache 並強制重建，讓 CodeQL 擷取 Kotlin 編譯，不重跑 unit tests／Lint。必須在 GitHub 實際確認 Kotlin 覆蓋與 SARIF 上傳成功後，才考慮將 `Analyze (java-kotlin)` 設為 required check；本機 assemble 成功不能代替掃描驗證。

## JDK 25 的 DataStore 相容設定

DataStore 內嵌的 `androidx.datastore.preferences.protobuf` 仍呼叫 `sun.misc.Unsafe`，因此 JDK 25 執行偏好儲存測試時會發出棄用警告。這份內嵌程式不會隨 App 的 `protobuf-javalite` 版本更新。

`app/build.gradle.kts` 只對 JVM `Test` 任務加入 `--sun-misc-unsafe-memory-access=allow`，暫時停止這類警告；這不是上游 Unsafe 實作的修復，也不是全面關閉 compiler／Lint／Gradle 警告。此選項作用於整個測試 JVM，無法僅限定 DataStore；不會傳給 Gradle daemon 或 Android App。

上游移除 Unsafe 呼叫後，刪除這個設定，強制重跑 `:app:testDemoDebugUnitTest --rerun --warning-mode all`，確認警告不再出現且儲存相關測試仍通過。此設定不保證未來移除 Unsafe 的 JDK 版本仍相容。

參考：[DataStore 發行紀錄](https://developer.android.com/jetpack/androidx/releases/datastore)、[JEP 498](https://openjdk.org/jeps/498)。
