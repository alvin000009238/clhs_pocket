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
| 個人與設定：外觀、通知、背景暫停時段、天氣來源、App 鎖定、設定匯入匯出、刪除資料、關於、登入與登出 | 介面完成；見下方「以示範服務代替的部分」 |

### 以示範服務代替的部分

`iosApp/Sources/App/PocketAppServices.swift` 定義四個協定，目前都是示範實作，等待接上正式實作：

| 協定 | 示範行為 | 正式實作負責範圍 |
| --- | --- | --- |
| `SchoolAccountService` | 只接受 `DEMO-000` / `demo`，不連線 | 校務登入、Keychain |
| `NotificationPermissionService` | 一律回報已允許 | 系統通知權限與排程 |
| `AppLockService` | 一律驗證成功 | Face ID / Touch ID / 裝置密碼 |
| `DataTransferService` | 以自訂 JSON 匯出入設定，刪除只清本機偏好 | 共用的設定轉移格式、快取與憑證清除 |

通知項目、背景暫停時段、天氣來源與 App 鎖定目前只儲存偏好值，不會改變任何行為。所有偏好存在 `UserDefaults`。

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

以示範資料實作，範圍與限制見 [iOS 小工具、通知、即時動態與背景更新](ios-widgets-notifications.md)。段考與公告通知尚無真實資料來源；背景更新只通過編譯。

## 尚未開始

把正式資料、WKWebView 登入、Keychain、App 鎖定、通知、小工具與 Deep Link 接進畫面（各自的實作已在 `Sources/Data`、`Sources/Security`、`Sources/Platform`、`Widgets/`，見 [資料與安全](ios-data-security.md) 與 [小工具與通知](ios-widgets-notifications.md)）；成績提醒；公告附件與圖片預覽。

## 驗證

指令見 [iosApp/README.md](../../iosApp/README.md)。`PocketShellUITests` 有六個流程（歡迎與訪客外殼、首頁編輯、成績明細與模擬器、課表自訂與換週、校園、設定與刪除資料），並輸出每個畫面的截圖。
