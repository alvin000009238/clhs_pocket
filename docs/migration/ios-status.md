# iOS 移植狀態

本文件記錄原生 iOS App（`iosApp/`）的實際完成度。未列為「完成」的項目一律尚未實作。所有畫面目前都使用共用 Kotlin 的示範資料；連線、Keychain、通知與 Widget 由其他工作階段負責。

## 里程碑 1：原生外殼與四個根畫面

SwiftUI App（iOS 18.0 以上、iPhone / iPad）、訪客優先、四個分頁各自獨立的 `NavigationStack`、Pocket 淺色與深色主題、iOS 26 Liquid Glass 與 iOS 18 後備樣式：完成。

## 里程碑 2：功能與介面對齊 Android（示範資料）

| 項目 | 狀態 |
| --- | --- |
| 四步驟歡迎流程（歡迎、外觀、通知、連結校務帳號／先逛逛） | 完成 |
| 首頁小工具：新增、移除、排序、調整尺寸；天氣地點、YouBike 站點、學校行程與自訂倒數、清單則數 | 完成，配置使用共用 `OverviewWidgetInstance` JSON 與 `OverviewLayoutEngine` |
| 成績：切換考試、科目明細（五標、班級分布、走勢）、成績分析、模擬器（共用 `SimulatorCredits` 的預設、驗證、推算）、分享摘要 | 完成；分享為純文字摘要，沒有圖片或檔案匯出 |
| 課表：本週／學期、切換班級與週次、異動標示、自訂科目、課程明細、分享課表圖片 | 完成；圖片經系統分享表單輸出，沒有直接存入相簿的按鈕 |
| 校園：公告分類、搜尋、已讀與收藏、內文；行事曆月曆與列表；出缺勤與獎懲 | 完成；公告內文與附件為示範文字，附件無法開啟 |
| 個人與設定：外觀、通知、安靜時段、天氣來源、App 鎖定、設定匯入匯出、刪除資料、關於、登入與登出 | 完成；正式實作見里程碑 3 |

## 里程碑 3：接上正式資料、登入、鎖定與平台功能

App 預設以正式模式啟動；啟動參數 `-dataMode demo` 才使用示範資料（UI 測試、截圖與單元測試宿主都用它）。

| 範圍 | 正式模式 | 示範模式 |
| --- | --- | --- |
| 資料 | `LiveDataController`（`Sources/App/LiveData.swift`）透過 `PocketBackend` / `PocketApi` 載入，寫進共用的 `PocketLive`；訪客可看公告、行事曆、天氣與 YouBike，登入後才載入課表、成績、出缺勤與獎懲 | 共用的 `DemoSource`，學年與更新日期跟著示範時間 |
| 登入 | `SchoolLoginScreen`（WKWebView）；Cookie 交給 `PocketBackend.login` | 只接受 `DEMO-000` / `demo` |
| App 鎖定 | `AppLockController`、`LockScreenView`、`.pocketPrivacyProtected(lock:)`；通知與 Deep Link 經 `AppLockRouteGate` | 相同，另可用 `-lockAuth succeed` / `manual` 取代系統驗證 |
| 通知、即時動態、安靜時段 | 「個人 › 通知」是唯一的設定頁，直接讀寫 `PlatformSettings` 並請 `PocketPlatformCoordinator` 重新排程 | 相同，另有「傳送示範通知」 |
| 天氣來源 | 切換後立即以所選來源重新取得 | 數值固定 |
| 設定匯入匯出 | `PocketApi.exportSettingsJson` / `inspectSettingsJson` / `restoreSettingsJson`，與 Android 通用 | 相同 |
| 小工具與即時動態 | 快照來自目前的共用課表；登出與刪除資料時清除 | 示範課表 |

尚未載入的私人資料顯示載入中或「尚未載入」與重試，不會退回示範內容。登出與刪除資料先撤銷記憶體中的登入狀態再清除儲存空間，清除失敗會顯示給使用者；刪除資料同時把通知偏好與 App 鎖定回復為預設值。

已知限制：

- 沒有使用真實帳號，正式登入與登入後的資料載入未經實機驗證。
- 課表的自訂科目名稱仍存在本機偏好，尚未寫入共用的 `saveSubjectOverrides`，因此不會隨備份匯出。
- 正式模式只提供本週與學期課表，不能切換其他週次。
- 成績更新通知尚無正式資料來源（`gradeNotification` 回傳空值）。
- 公告內文以純文字段落顯示，附件以系統瀏覽器開啟。

## 共用 Kotlin 介面

Swift 只依賴 `com.clhs.score.shared.app`：

| 入口 | 使用的共用邏輯 |
| --- | --- |
| `PocketDataSource` | `FakeData`、`buildGradeAnalysis`、`FakeScheduleData`、`compareScheduleItems`、`getSubjectColors` |
| `DemoSchedule.hero` | `resolveCourses`、`ScheduleItem.occurrenceOn` |
| `PocketDashboard` | `OverviewWidgetInstance` / `OverviewWidgetConfig`、`OverviewLayoutEngine`、`recentEvents`、`importantAnnouncementsFrom`、倒數計算 |
| `PocketGrades`、`PocketSimulator` | `buildGradeAnalysis`、`scoreDistributions`、`SimulatorCredits`、`weightedAverageFor`、`gradeSummaryDisplay` |
| `PocketScheduleStore` | `ScheduleSubjectOverride` 的正規化與 `applySubjectOverrides` |
| `PocketCampusStore` | `FakeData.announcementPage`、`FakeRecordData`、`shared.time` |

日期與時間一律使用 `com.clhs.score.shared.time`；先前的 `PocketDates` 已移除。

仍是 `shared.app` 內固定示範值的部分：天氣數值、YouBike 車輛數、公告內文與附件、行事曆事件。對應的 repository 與解析共用化後應替換。

## 小工具、通知、即時動態與 Deep Link

範圍與限制見 [iOS 小工具、通知、即時動態與背景更新](ios-widgets-notifications.md)。段考與公告通知尚無真實資料來源；背景更新只通過編譯。

## 尚未開始

成績提醒的正式資料來源；公告圖片預覽；課表圖片直接存入「照片」；在首頁格線上拖曳排序。

## 驗證

指令見 [iosApp/README.md](../../iosApp/README.md)。`PocketShellUITests` 有八個流程（歡迎與訪客外殼、首頁編輯、成績明細與模擬器、課表自訂與換週、校園、設定與刪除資料、App 鎖定、正式模式訪客開啟校務登入頁），並輸出每個畫面的截圖。
