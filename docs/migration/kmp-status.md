# KMP／iOS 遷移狀態

本文件記錄 `feat/kmp-ios-native-migration` 分支的實際進度與驗證結果。
狀態只使用 `PASS`、`FAIL`、`BLOCKED`、`NOT_RUN`；沒有實際執行的項目不得標為 `PASS`。

## 分支與分工

| 分支 | 用途 |
| --- | --- |
| `feat/kmp-ios-native-migration` | 整合分支：Android、`android/shared` 共用邏輯、文件 |
| `feat/kmp-ios-native-app` | macOS 上的原生 SwiftUI App（`iosApp/**`、`android/shared/src/iosMain/**`），完成後合併回整合分支 |

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

- `com.clhs.score.shared.math`：`expect class BigDecimal`／`RoundingMode`。Android 以 `actual typealias` 指向 `java.math`；iOS 指向純 Kotlin 的 `PortableDecimal`（以 `Long` 保存，溢位時丟出 `ArithmeticException`）。
- `com.clhs.score.shared.time`：`expect class LocalDate`／`LocalTime`／`LocalDateTime`／`DayOfWeek`／`Instant`。Android 指向 `java.time`；iOS 指向 `PortableLocalDate` 等純 Kotlin 實作。`java.time` 的靜態工廠與存取子改為頂層函式（例如 `parseLocalDate`、`LocalDate.shiftDays`）。
- 學校時區固定為 `Asia/Taipei`（UTC+8，無日光節約時間）。
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
| 公告與行事曆模型 | `SchoolAnnouncementModels.kt`、`SchoolCalendarModels.kt`（解析與網路仍在 `app`） |

`GradeAnalysisTest`、`SimulatorCreditsTest`、`GradeReportDifferTest`、`GradeSummaryDisplayTest` 已改用 `kotlin.test` 並搬到 `commonTest`，Android host 與 iOS 目標共用同一份測試。

## 檔名限制

`app` 與 `shared` 使用同一個 package，含頂層宣告的檔案不可同名：兩邊都會產生 `<檔名>Kt` 類別，執行時只會載入其中一個（曾造成 `NoSuchMethodError`）。拆檔時 `app` 留下的部分要改檔名。

## 已知的行為差異

- `YouBikeSnapshot.isStale` 原本以 `Duration`（奈秒）比較 5 分鐘門檻，現在以毫秒比較。只有當下時間落在門檻後不到 1 毫秒時結果不同；既有的 `YouBikeRepositoryTest`（300 秒、301 秒、早 1 秒）通過，沒有另外為亞毫秒邊界新增測試。

## 尚未搬移

- 公告、行事曆、天氣、YouBike 的解析（目前與 OkHttp、Jsoup、biweekly 寫在同一個檔案，且使用 `java.time.Instant`／`Duration`）。
- `domain/overview` 的模型與彙整邏輯、`SettingsTransfer.kt`。
- 給 iOS 使用的 transport 介面與 repository。

## 目前驗證結果（Linux）

| 檢查 | 結果 |
| --- | --- |
| `:shared:allTests`（Android host） | PASS |
| `:shared:compileKotlinIosX64`、`:shared:compileTestKotlinIosX64`、`:shared:compileKotlinIosSimulatorArm64` | PASS |
| `:app:testDemoDebugUnitTest` | PASS |
| `:shared:iosSimulatorArm64Test`、`:shared:iosX64Test` | BLOCKED（Linux 上被 Gradle 停用，需在 macOS 執行） |
| 完整 Android gate（`e6fb6b7`：demo 測試、production `FlavorConfigurationTest`、`lintDemoDebug`、兩個 flavor 的 assemble） | PASS（第二次執行）。第一次執行 FAIL：`lintAnalyzeDemoDebugUnitTest` 在 `kspProductionDebugKotlin` 重新產生檔案時讀取 `build/generated/ksp/productionDebug`，出現 `FileNotFoundException`；KSP 輸出完成後重跑即通過，尚未確認這個競態在 `main` 是否也會發生。 |
| Android 模擬器啟動檢查（API 35 x86_64 模擬器、`demoDebug`、`6d613fe`） | PASS：導覽頁 4 步、範例帳號登入、首頁／課表／成績／校園／個人頁，淺色與深色皆正常顯示範例資料，沒有當機。 |
| Android 搬移前後畫面逐一比對 | NOT_RUN（搬移前沒有先截基準圖，只能確認搬移後畫面正常） |
| iOS 建置、Simulator、畫面檢查 | NOT_RUN（由 macOS session 進行） |
