# KMP／iOS 遷移狀態

本文件記錄 `feat/kmp-ios-native-migration` 分支的實際進度與驗證結果。
狀態只使用 `PASS`、`FAIL`、`BLOCKED`、`NOT_RUN`；沒有實際執行的項目不得標為 `PASS`。

## 分支與分工

| 分支 | 用途 |
| --- | --- |
| `feat/kmp-ios-native-migration` | 整合分支（PR #250）：Android、`android/shared` 共用邏輯、`iosApp/`、文件 |
| `feat/kmp-ios-native-app` | macOS 上的原生 SwiftUI App 與整合；每個里程碑合併回整合分支 |
| `feat/kmp-ios-data-security` | iOS 連線、登入、Keychain、App 鎖定、備份格式（已合併） |
| `feat/kmp-ios-widgets-notifications` | WidgetKit、Live Activity、通知、背景更新、Deep Link（已合併） |

iOS 端的細節與驗證見 [ios-status.md](ios-status.md)、[ios-data-security.md](ios-data-security.md)、[ios-widgets-notifications.md](ios-widgets-notifications.md)。

Linux 無法執行 Xcode 與 iOS Simulator；iOS 建置、測試與畫面檢查一律在 macOS 進行。
Linux 可以編譯 iOS klib（`:shared:compileKotlinIosX64`、`:shared:compileTestKotlinIosX64`），但不能連結或執行 iOS 測試。

## Android 基準（遷移前）

起點為 `bd735bc`（PR #249 的內容）。在 Linux、JDK 25 執行 AGENTS.md 的最終 gate 加上 `:shared:allTests`：

| 檢查 | 結果 | 說明 |
| --- | --- | --- |
| `:app:testDemoDebugUnitTest` | FAIL | 759 個測試中 1 個失敗：`ArchitectureBoundaryTest.campusPersonalAndSettingsDestinationsOwnFormerDrawerEntrances`。PR #249 把四個根目的地標籤改為引用 `PocketRoot`，原始碼守衛找不到字面值。 |
| 其餘 gate 任務 | NOT_RUN | 測試失敗後 Gradle 停止，未取得結果。 |

修正：`AppNavigation.kt` 還原為 `main` 的字面標籤（`c23e289`），`ArchitectureBoundaryTest` 隨後通過。

## 共用模組的型別策略

Android 的公開 API 大量使用 `java.math.BigDecimal` 與 `java.time`。為了不改變 Android 行為：

- `com.clhs.score.shared.math`：`expect class BigDecimal`／`RoundingMode`。Android 以 `actual typealias` 指向 `java.math`；iOS 指向純 Kotlin 的 `PortableDecimal`（以 `Long` 保存未縮放值，scale 限制在 ±10000，溢位時丟出 `ArithmeticException`）。跨 scale 比較、`formatFixed`（直接以 `Double` 的最短十進位字串進位）、`Long.MIN_VALUE`、負零與帶尾端 0 的除數都不會溢位或誤判。
- `com.clhs.score.shared.time`：`expect class LocalDate`／`LocalTime`／`LocalDateTime`／`DayOfWeek`／`Instant`。Android 指向 `java.time`；iOS 指向 `PortableLocalDate` 等純 Kotlin 實作。`java.time` 的靜態工廠與存取子改為頂層函式（例如 `parseLocalDate`、`LocalDate.shiftDays`）。
- 學校時區固定為 `Asia/Taipei`（UTC+8，無日光節約時間）。
- 兩個日期時間的間隔以 `LocalDateTime.durationUntil`（`kotlin.time.Duration`）計算，對應 Android 原本的 `java.time.Duration.between`；舊的 `nanosUntil`（以 `Long` 奈秒相乘，約 292 年後溢位）已移除。
- `PortableLocalDate` 的年份範圍與 `java.time` 相同（±999,999,999），`plusMonths` 先以 `Long` 檢查年份再縮成 `Int`，`toString` 依 ISO 格式輸出負年份與五位數年份。`SchoolTimeTest`（`commonTest`）在 Android 以 `java.time`、在 iOS 以 `Portable*` 執行同一份比較、間隔、月份位移、`Instant` 與學校時區斷言。
- `Portable*` 實作放在 `commonMain`，由 `androidHostTest` 的 `PortableDecimalParityTest`、`PortableTimeParityTest` 以隨機資料和 `java.math`／`java.time` 逐一比對，因此可以在 Linux 驗證 iOS 會用到的實作。
- 搬到 `commonMain` 的檔案保留原本的 package（`com.clhs.score.data`），Android 呼叫端不需要改 import。跨模組後 `internal` 宣告改為 public，兩處跨模組 smart cast 改為區域變數。

## 已搬到 `android/shared/src/commonMain`

| 範圍 | 檔案 |
| --- | --- |
| 成績模型與分析 | `GradeModels.kt`、`GradeAnalysis.kt`、`GradeSummaryDisplay.kt`、`GradeCacheModels.kt` |
| 模擬器學分 | `SimulatorCredits.kt` |
| 成績提醒比對 | `GradeReminderModels.kt`、`GradeReminderText.kt`、`GradeReportDiffer.kt` |
| 出缺勤與獎懲解析 | `StudentRecords.kt` |
| 學校 JSON 對應與例外 | `JsonMappers.kt`、`SchoolExceptions.kt` |
| 範例成績 | `MockGradeSystem.kt`（自 `FakeData.kt` 拆出 `StudentScenario`、`MockGradeSystem`） |
| 課表模型、解析、更新時機 | `ScheduleModels.kt` |
| 天氣與 YouBike 模型 | `WeatherLocation.kt`、`WeatherForecastPeriod.kt`、`YouBikeModels.kt`（解析與網路仍在 `app`） |
| 課程時段解析 | `CourseResolution.kt`（以 `Instant` 為輸入；Android 的 `Clock` 版本留在 `app` 的 `SchoolCourseClock.kt`） |
| 範例資料 | `FakeData.kt`、`FakeScheduleData.kt`、`FakeRecordData.kt`（`FakeGradeRepository` 留在 `app` 的 `FakeGradeRepository.kt`） |
| 設定模型 | `AppSettings.kt`（含 `OverviewPreferences`、`BackgroundPause`；以裝置時區判斷的 `BackgroundPause.contains` 留在 `app`） |
| 首頁模型與小工具 | `domain/overview/OverviewModels.kt`（`OverviewState`、倒數計算）、`OverviewWidgets.kt`（小工具設定、版面配置）、`OverviewListSelection.kt`（重要公告與近期行程篩選）。`OverviewCoordinator`（資料彙整與更新排程）仍在 `app` |
| 公告與行事曆模型 | `SchoolAnnouncementModels.kt`、`SchoolCalendarModels.kt`（解析與網路仍在 `app`） |

`GradeAnalysisTest`、`SimulatorCreditsTest`、`GradeReportDifferTest`、`GradeSummaryDisplayTest` 已改用 `kotlin.test` 並搬到 `commonTest`，Android host 與 iOS 目標共用同一份測試。

## 檔名限制

`app` 與 `shared` 使用同一個 package，含頂層宣告的檔案不可同名：兩邊都會產生 `<檔名>Kt` 類別，執行時只會載入其中一個（曾造成 `NoSuchMethodError`）。拆檔時 `app` 留下的部分要改檔名。

## 已知的行為差異

- `YouBikeSnapshot.isStale` 原本以 `Duration`（奈秒）比較 5 分鐘門檻，現在以毫秒比較。只有當下時間落在門檻後不到 1 毫秒時結果不同；既有的 `YouBikeRepositoryTest`（300 秒、301 秒、早 1 秒）通過，沒有另外為亞毫秒邊界新增測試。

- 小工具設定的時區檢查在 Android 仍使用 `ZoneId.of`；iOS 使用 `NSTimeZone.timeZoneWithName`，兩者接受的識別字不完全相同（例如 `UTC+8` 這類偏移寫法），iOS 端尚未測試。
- 公告收藏的網址檢查（`safeAnnouncementWebUrl`）依賴 OkHttp，仍在 `app`；iOS 需要自己的等效實作。

## 未解決的問題

- **lint 與 KSP 的競態**：在本分支修改原始碼後第一次跑完整 gate，`lintAnalyzeDemoDebugUnitTest`／`lintAnalyzeDemoDebugAndroidTest` 會在 `kspProductionDebugKotlin` 同時重新產生檔案時讀取 `app/build/generated/ksp/productionDebug/.../AnnouncementReadDao_Impl.kt`，丟出 `FileNotFoundException`；不改任何東西重跑就通過。在 `main`（`a3085ea`）以同一指令跑兩次（全新建置、修改一個原始檔後再跑）都通過，所以這個問題是本分支（含 PR #249 加入的 `:shared` 模組）才出現的。原因尚未查明，也尚未在 CI 的全新環境驗證。

## 尚未搬移

- Android 的公告、行事曆、天氣、YouBike 解析仍在 `app`（與 OkHttp、Jsoup、biweekly 寫在同一個檔案）。iOS 使用 `com.clhs.score.shared.net`／`shared.repo` 內另一份可攜實作；兩邊尚未合併成單一實作，修改解析規則時需要同時檢查兩處。
- `domain/overview/OverviewCoordinator.kt` 的彙整與排程、Android 的 `SettingsTransfer.kt` 仍是 Android 專用；iOS 以共用的備份格式讀寫相容檔案。

## PR #250 審查修正：共用數值、時間與 CI（Linux）

- `android-ci.yml`、`android-release.yml` 的 gate 加入 `:shared:testAndroidHostTest`，`PortableDecimalParityTest`／`PortableTimeParityTest` 會在 CI 執行。
- `kmp-ios.yml` 的 actions 固定到 commit SHA，加入 `concurrency`（取消同一 ref 的舊執行），`paths` 加入 `android/gradle/wrapper/**`、`android/gradle/libs.versions.toml`、`android/gradle.properties`、`android/gradlew`。
- `android/shared/build.gradle.kts` 改用版本目錄（新增 `kotlinx-coroutines-core`）並設定 `jvmToolchain(25)`。
- `productionRelease`（本機臨時簽章）：APK 12,505,534 bytes，`origin/dev`（`clhs-dev` 工作目錄）12,489,150 bytes，多 16,384 bytes。R8 的 `mapping.txt` 沒有保留任何 `com.clhs.score.shared.net`／`shared.repo`／`shared.app` 類別，`usage.txt` 列出 315 筆被移除的項目。

| 檢查（`devin/review250-shared-core`） | 結果 |
| --- | --- |
| `:shared:allTests`、`:shared:testAndroidHostTest` | PASS |
| `:shared:compileKotlinIosX64` | PASS |
| `:shared:iosSimulatorArm64Test`、`:shared:iosX64Test` | BLOCKED（Linux 上被 Gradle 停用；由 `kmp-ios.yml` 在 macOS 執行） |
| 完整 Android gate（`--warning-mode all`） | PASS（888 個 JVM 測試，0 失敗；lint 只有既有的相依版本提示） |
| `:app:assembleProductionRelease --warning-mode all` | PASS（臨時簽章；正式簽章 NOT_RUN） |

## 目前驗證結果（Linux）

| 檢查 | 結果 |
| --- | --- |
| `:shared:allTests`（Android host） | PASS |
| `:shared:compileKotlinIosX64`、`:shared:compileTestKotlinIosX64`、`:shared:compileKotlinIosSimulatorArm64` | PASS |
| `:app:testDemoDebugUnitTest` | PASS |
| `:shared:iosSimulatorArm64Test` | 本機 BLOCKED（Linux 上被 Gradle 停用）。CI：`kmp-ios.yml` 在 macOS runner 執行這個工作，PR #250 在 `a20300a` 的該檢查通過；尚未讀取工作紀錄確認實際執行的測試數量。 |
| `:shared:iosX64Test` | NOT_RUN |
| PR #250 CI（`a20300a`） | PASS：Android 測試／lint／建置（全新環境，未出現 lint／KSP 競態）、iOS framework 與 SwiftUI Simulator 建置（建置的是 #249 的骨架）、CodeQL。 |
| 完整 Android gate（`801c1a4`：`:shared:allTests`、demo 測試、production `FlavorConfigurationTest`、`lintDemoDebug`、兩個 flavor 的 assemble、`--warning-mode all`） | 原始碼變動後的第一次執行 FAIL（3 次皆同），緊接著重跑 PASS。詳見下方「未解決的問題」。重跑時編譯工作為 UP-TO-DATE，所以這次 PASS 沒有重新檢查 Kotlin 編譯警告。 |
| Android 模擬器啟動檢查（API 35 x86_64 模擬器、`demoDebug`、`6d613fe`） | PASS：導覽頁 4 步、範例帳號登入、首頁／課表／成績／校園／個人頁，淺色與深色皆正常顯示範例資料，沒有當機。 |
| Android 搬移前後畫面逐一比對 | NOT_RUN（搬移前沒有先截基準圖，只能確認搬移後畫面正常） |
| 完整 Android gate（`4dbec4f`，iOS 里程碑 4 合併後） | 第一次執行 FAIL：`OverviewWidgetCoordinatorTest.weatherRetryBypassesFreshCacheWithoutRefreshingOtherPublicSources`（預期 0、實際 2）；該測試單獨重跑 3 次與完整 gate 重跑皆 PASS。里程碑 4 沒有修改該測試涉及的程式；是否在 `main` 也會偶發，NOT_RUN。`7a44153`、`3a8cf15`、`5221a1f` 合併後的完整 gate 皆第一次執行即 PASS。 |
| Android demo 版實機操作回歸（API 35 模擬器、範例資料，`6e44022` 之前的共用邏輯搬移） | 結果與錄影見 PR #250 留言：功能項目 PASS；浮動導覽列在部分捲動位置覆蓋底部內容（未與 `main` 比對，來源未判定）；全新安裝的訪客狀態 BLOCKED（demo 版自動登入範例學生，`main` 亦同）。 |
| iOS 建置、測試、Simulator 畫面 | 見 [ios-status.md](ios-status.md) 的驗證表（macOS session 執行）。 |
