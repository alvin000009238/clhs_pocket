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

- 沒有使用真實帳號，正式登入與登入後的資料載入未經實機驗證：校務系統只接受臺灣的 IP，開發期間沒有可用的 Mac 與臺灣網路。
- 公告附件以系統瀏覽器開啟。

## 里程碑 4：審查修正與補齊

- **鎖定與隱私遮罩**：改放在獨立的最上層視窗（`PrivacyShield`），蓋住所有 sheet、全螢幕畫面與登入網頁；鎖定時其他視窗對 VoiceOver 與觸控都不可用。
- **成績考試選擇**：以「學期 + 考試」為鍵（`PocketExamKey`），不同學期使用相同考試代碼時不會取到舊學期的成績。
- **平台更新**：登入後課表載入或變更時會重新發布小工具快照、上課提醒與即時動態。
- **公告**：第一頁的頁碼是 0，與 Android 相同。
- **刪除資料**：不會關閉 App 鎖定。Android 沒有「刪除全部資料」，它的本機資料清除也不清設定，鎖定設定一律保留；iOS 比照，要關閉必須到「App 鎖定」通過驗證。
- **成績更新通知**：前景或背景更新時重新取得最新一次考試，與 App 已有的成績單以共用的 `GradeReportDiffer` 比對，有差異才通知；遵守通知開關與安靜時段，內容不含分數與排名。比對基準是既有的成績快取，不另外儲存。
- **自訂科目名稱**：登入後存在帳號的受保護儲存空間，會包含在設定備份中（與 Android 相同）；訪客與示範模式仍是本機偏好。
- **課表圖片**：可直接儲存到「照片」，只要求「加入照片」權限。
- **首頁**：長按拖曳小工具到另一個小工具上即可調整順序；編輯頁保留按鈕式的上移、下移。
- **輔助使用**：UI 測試對四個根畫面執行 Xcode 的 accessibility audit（元素描述與點按範圍）。

## 里程碑 5：審查修正

- **自訂科目名稱依帳號分開**：每個學號一個檔案（Android 以學號為鍵），換帳號登入不會覆蓋別人的設定；舊版的單一檔案仍可由原本的帳號讀取。
- **示範與真實帳號隔離**：已有真實登入狀態時從歡迎畫面進入示範，示範不讀寫真實帳號的儲存空間（自訂科目、備份、成績檢查），也不替該帳號連線；離開示範後才恢復。
- **成績更新檢查會重新取得考試清單**：Android 的 `GradeReminderWorker` 只檢查使用者指定的那一次考試，不更新清單；iOS 自動檢查最新一次考試，所以每次檢查先更新清單，新出現的考試若已有成績也會通知。

- **CI 不依賴外部伺服器**：兩個正式模式的 UI 流程（訪客首頁的公開小工具、開啟校務登入頁）改為選擇性執行，設定 `TEST_RUNNER_LIVE_NETWORK=1` 才會跑；CI 會略過。
- **取不到資料的小工具**：天氣或 YouBike 暫時沒有資料時顯示「暫時無法取得」的卡片，不再留下空白。

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

## 里程碑 6：補齊已知缺口

- **成績更新檢查的基準**：上一次檢查確認「沒有任何考試」也算基準，之後第一次出現的考試（連同成績）會通知；登入後的第一次檢查只記錄現況，不通知。
- **正式模式的課表可切換週次**：與 Android 相同，以日期向校務系統查詢該週課表，載入中與失敗時顯示載入／重試；瀏覽其他週不會取代小工具與提醒所用的本週課表。未以真實帳號驗證。
- **公告內文保留格式**：粗體、底線、清單與連結依原文顯示（連結僅限 http／https）；內文先由共用程式碼去除指令碼、樣式與內嵌資源，轉換時不會連線。學校網站的圖片直接顯示，點按開啟原圖；站外圖片與 Android 相同，不自動載入，提供「開啟圖片」。圖片顯示只有正式資料才會出現，未經畫面驗證。
- **iPad 首頁版面**：寬版格線（6 或 8 欄）每一列都填滿；清單型小工具（課程／成績異動、近期行程、重要公告）兩個並排，落單時佔滿整列。iPhone 維持 Android 的排列，儲存的大小不變。
- **iPad 首頁沒有空格（里程碑 7）**：橫向 8 欄時，較矮的小工具會疊在同寬的鄰居下方或延伸到該列高度，不留空洞；訪客看到的「課程／成績異動」只有一行登入提示，改為單列橫跨整列，不再與較高的清單並排拉高。直向與橫向、淺色與深色、示範登入與訪客皆已在畫面上檢視。
- **App Group 名稱**：以 AltStore 等工具用個人開發者帳號重新簽署時，App Group 會被改名；App 與小工具會改用 Info.plist `ALTAppGroups` 所列、且實際可開啟的群組。未在實機驗證。
- **真實登入**：BLOCKED。校務系統只接受臺灣的 IP，開發期間沒有可用的 Mac 與臺灣網路；改由使用者以未簽署的 `.ipa` 在自己的 iPad 上驗證。

## 尚未開始

目前沒有已知但未開始的項目；未驗證的部分見下表。

## 驗證

指令見 [iosApp/README.md](../../iosApp/README.md)。`PocketShellUITests` 有 13 個流程：歡迎與訪客外殼、首頁編輯、首頁拖曳排序、成績明細與模擬器、課表自訂與換週、課表圖片存入照片、校園、設定與刪除資料、App 鎖定、鎖定蓋住已開啟的 sheet、四個根畫面的 accessibility audit、正式模式訪客首頁（公開小工具）、正式模式訪客開啟校務登入頁。每個流程都會輸出畫面截圖。最後兩個流程需要連線到學校、天氣與 YouBike 的伺服器，預設略過。

最後驗證（Xcode 26.6、iOS SDK 26.5、macOS 26.6 arm64）。里程碑 7 的最後一個提交重新執行了 Kotlin 測試、單元測試，以及 iPhone 17 淺色、iPad Air 11 吋淺色與深色的 UI 流程；iPhone 17 深色與 iPhone 16／iOS 18.6 的結果來自里程碑 6 的最後一個提交（不含需要外部網路的兩個流程）；輔助使用字級、減少動態效果與公開端點的結果來自先前的里程碑。

| 檢查 | 結果 |
| --- | --- |
| `:shared:iosSimulatorArm64Test` | PASS |
| 單元測試（App 與 Widget Extension 一併建置；54 個，其中 3 個截圖測試未設定 `SHOT_DIR` 而略過），iPhone 17／iOS 26.5 | PASS |
| UI 流程 13 個（2 個需要外部網路的流程略過），iPhone 17／iOS 26.5，淺色 | PASS |
| UI 流程，iPhone 17，深色 | PASS |
| UI 流程，iPad Air 11 吋 (M4)，淺色與深色 | PASS |
| UI 流程，iPhone 17，輔助使用字級 Accessibility XL | PASS（里程碑 5） |
| UI 流程，iPhone 16／iOS 18.6（非 Liquid Glass） | PASS |
| UI 流程，iPhone 17，模擬器開啟「減少動態效果」 | PASS（設定以模擬器偏好寫入，未另外驗證 App 讀到的值） |
| Accessibility audit（四個根畫面；元素描述與點按範圍） | PASS |
| 公開端點（訪客、模擬器實際連線）：Open-Meteo 天氣、學校公告、學校行事曆、YouBike | PASS |
| 單元測試與 UI 流程放在同一次 `xcodebuild test` | FAIL 一次：單元測試宿主在連上測試執行器前停住；分兩次執行皆通過，CI 已改為分兩次 |
| 真實帳號登入與登入後資料（含成績更新通知、自訂科目同步） | BLOCKED：校務系統只接受臺灣的 IP，沒有可用的 Mac 與臺灣網路，也沒有使用帳號 |
| `kmp-ios.yml` 在 GitHub 上執行 | NOT_RUN |
| VoiceOver 實際朗讀操作 | NOT_RUN |
