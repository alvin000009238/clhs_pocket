# iOS 移植狀態

本文件記錄原生 iOS App（`iosApp/`）的實際完成度。未列為「完成」的項目一律尚未實作。

## 里程碑 1：原生外殼與四個根畫面（示範資料）

| 項目 | 狀態 |
| --- | --- |
| SwiftUI App、iOS 18.0 最低版本、iPhone / iPad | 完成 |
| 使用 `PocketShared`，示範資料來自共用 Kotlin | 完成 |
| 訪客優先歡迎畫面、課表與成績的登入提示 | 完成（單頁歡迎畫面；Android 的四步驟流程尚未移植） |
| 首頁、課表、成績、校園四個分頁，各自獨立的 `NavigationStack` | 完成 |
| 個人入口（外觀切換、登出示範帳號） | 完成 |
| Pocket 淺色與深色主題、Huninn / Outfit 字體 | 完成 |
| iOS 26 Liquid Glass（`#available`）與 iOS 18 後備樣式 | iOS 26.5 已驗證；iOS 18 後備樣式可編譯但未在實機或模擬器執行 |

## 共用 Kotlin 介面

Swift 只依賴 `com.clhs.score.shared.app.PocketDataSource`：

- 成績：`FakeData` 的報表經 `buildGradeAnalysis` 產生平均、排名百分比與強弱科。
- 課表：`FakeScheduleData` 的學期與本週課表、`compareScheduleItems` 的異動、`getSubjectColors` 的科目色。
- 個人資料：`FakeData` 的示範學生。
- 首頁的天氣、YouBike、倒數，以及校園公告與行事曆：目前是 `shared.app` 內的固定示範資料，因為對應的彙整與解析仍在 `android/app`。

待共用化後應替換的部分：

| iOS 暫時實作 | 應改用 |
| --- | --- |
| `DemoSchedule.hero`（目前課程與下一堂） | `resolveCourses` |
| `DemoCampus`（公告、行事曆、倒數） | 公告與行事曆解析、`FakeData.announcementPage` |
| `DemoPocketDataSource` 的天氣與 YouBike | Overview 彙整、天氣與 YouBike repository |
| `PocketDates` 的日期計算 | `com.clhs.score.shared.time` |

## 尚未開始

校務登入與正式連線、WKWebView、Keychain 與生物辨識、通知與背景更新、WidgetKit 與 Live Activities、設定匯入匯出、Deep Link、首頁自訂、成績模擬器與科目趨勢、課表匯出與科目覆寫、出缺勤與獎懲、公告內文、完整行事曆。

## 驗證

指令見 [iosApp/README.md](../../iosApp/README.md)。UI 測試 `PocketShellUITests` 會走過歡迎畫面、訪客提示、四個分頁、分頁堆疊保留、個人頁，並輸出截圖。
