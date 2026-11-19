# iOS 小工具、通知、即時動態與背景更新

本文件記錄 `feat/kmp-ios-widgets-notifications` 的實作範圍、與 Android 的差異，以及哪些行為已在 Simulator 實際執行、哪些僅通過編譯。目前 iOS App 只有示範資料，沒有校務登入；下列功能全部以共用 Kotlin 的示範資料驅動。

## 架構

```
共用 Kotlin  com.clhs.score.shared.platform
  buildWidgetScheduleSnapshot()  ← resolveCourses()（課程邊界的唯一來源）
  planCourseReminders() / planGradeNotification() / planAnnouncementNotification()
        │ JSON、PlannedNotification
        ▼
iosApp/Sources/Platform（App 行程）
  PocketPlatformCoordinator：發布快照、排程通知、啟動即時動態、登記背景更新
        │ App Group 檔案 schedule-snapshot.json
        ▼
iosApp/Widgets/Extension（WidgetKit extension，不連結 Kotlin）
  ScheduleWidget、LessonLiveActivity
```

- 課程邊界、階段（上課中、即將上課、下一堂、今日結束、今日無課）、上課前提醒時間、安靜時段判斷與通知文案都在共用 Kotlin；Swift 只解碼並呈現。
- Extension 不連結 `PocketShared`，也不讀 Keychain、`UserDefaults`、Cookie 或任何網路；它唯一的輸入是 App Group 內的 `schedule-snapshot.json`。

## 小工具快照

`WidgetScheduleSnapshot`（schema 版本 1）只包含：節次、科目、教室、上下課時間、科目色、週次標籤，以及 `resolveCourses` 算出的每個重新載入時間點。不包含學號、姓名、班級代碼、教師、成績、Token 或 Cookie；`PlatformPlanningTest` 與 `PlatformTests` 各有一項測試檢查序列化結果。

- 週課表：快照涵蓋到該週結束（週日 24:00，與 Android 的週過期相同），之後小工具顯示「課表已過期」。
- 學期課表：快照涵蓋 7 天，需要 App 或背景更新重新發布。目前示範資料只走週課表路徑；學期路徑只有共用程式碼，未在 iOS 執行。
- schema 版本不符、JSON 損毀、節次重疊或週次過期時，視為沒有快照，小工具顯示「開啟 App 查看課表」。
- 檔案以 `completeUntilFirstUserAuthentication` 保護；登出時刪除檔案並重新載入時間軸。

## 小工具

| 尺寸 | 內容 |
| --- | --- |
| 小 | 狀態、科目、教室、進度條與倒數 |
| 中 | 同上，加上「接下來」最多三堂 |
| 大 | 當日（或下一個上課日）完整課表，標示目前與下一堂 |
| 鎖定畫面矩形 | 狀態、科目、下課時間（或上課時段）與教室 |
| 鎖定畫面圓形 | 上課中為節次與進度環，其餘為下一堂開始時間 |
| 鎖定畫面單行 | 一行摘要 |

- 時間軸在每個共用邊界重新載入（上課、下課、上課前 5 分鐘、換日、過期），倒數與進度條由系統自行更新，不需要每分鐘一筆。
- 淺色、深色與色調模式：色調與透明模式下改用系統的 primary / secondary 樣式，科目色與進度條標為 `widgetAccentable`。
- Dynamic Type 上限為 `xxLarge`，以免固定尺寸的小工具被裁切。
- 沒有使用 iOS 26 專屬 API；iOS 18 走相同程式碼。

## 即時動態

App 在前景時依快照啟動或更新目前（或今天下一堂）課程的即時動態，包含鎖定畫面與動態島的 compact、minimal、expanded 版面。可在「個人 › 通知與動態」關閉。

限制：沒有推播通道（本專案不建置任何伺服器），所以即時動態只有在 App 執行時才會換到下一堂。倒數與進度條會自行前進；另外利用 `staleDate` 讓系統自行重繪一次：等待中的課程在上課時間變成「上課中」，進行中的課程在下課時間變成「已下課」。之後要等 App 回到前景或背景更新執行才會再更新。放學後或登出時由 App 結束。

## 通知

| 類型 | 觸發 | 內容 |
| --- | --- | --- |
| 上課前提醒 | 上課前 5 分鐘的本機排程，最多 40 則（iOS 上限 64） | 節次、科目、時間、教室 |
| 段考更新 | 檢查到成績異動時立即送出 | 只列出有異動的科目，不含分數與排名（沿用 `GradeReminderText`） |
| 學校公告 | 與已記錄的公告 ID 比對 | 則數與標題 |

- 通知預設關閉；開啟時才向系統要求權限，被拒絕則設定維持關閉並提示前往「設定」。
- 安靜時段使用共用的 `BackgroundPause`（裝置當地時間，結束時間不含）。時段內的上課前提醒直接略過；公告保留到時段結束後再通知；背景檢查不送出通知。
- 第一次檢查公告只建立基準，不會通知。
- 通知內容只帶一次性的隨機 capability，不帶目的地或任何資料。點擊時由 App 兌換；偽造、重複使用或已撤銷的 capability 只會開啟 App，不會導向任何頁面。
- 登出時移除所有待送與已送達的通知、撤銷所有 capability、清除公告基準、結束即時動態並刪除快照。

示範資料不會自行變動，所以段考更新與公告目前只能從「個人 › 通知與動態 › 傳送示範通知」觸發；它走的是相同的規劃與送出路徑。正式版需以 `GradeReportDiffer` 比對兩次抓取的成績，並實作 `PlatformDataProviding`。

登入狀態由 App 外殼在每次變動與回到前景時告知 `PocketPlatformCoordinator`，並另存一份旗標供背景啟動使用（`AppModel` 的 `session` 預設值可能被啟動參數覆蓋，不能直接讀）。

## Deep Link

`PocketDeepLink` 接受 `clhspocket://home`、`schedule`、`grades`、`campus`、`announcements`、`calendar`。帶有路徑、查詢參數、使用者資訊或未知目的地的 URL 一律拒絕。連結只選擇分頁與子頁，課表與成績仍經過外殼既有的登入檢查；歡迎畫面未完成時不會被跳過。

## 背景更新與 Android 的差異

| | Android | iOS |
| --- | --- | --- |
| 排程 | WorkManager 週期工作，另可用精確鬧鐘 | `BGAppRefreshTask`，由系統決定是否與何時執行 |
| 上課前提醒 | 鬧鐘喚醒後即時計算 | 開啟 App 或背景更新時預先排好整週的本機通知 |
| 目前課程通知 | 常駐通知，可即時更新 | 即時動態，只有 App 執行時才能更新 |
| 小工具更新 | Worker 更新 Glance 狀態 | 時間軸預先算到週末，不需要 App 執行 |

iOS 不保證背景更新會執行；使用者關閉「背景 App 重新整理」、低耗電模式或很少開啟 App 時可能完全不執行。因此設計上不依賴它：上課前提醒與小工具時間軸都在 App 開啟時預先排到週末。背景更新只用來在使用者沒開 App 時提早換到下一週，以及檢查公告。

## 與 Android 尚未對齊的部分

- 沒有生物辨識鎖：iOS App 尚無此功能，通知只經過登入檢查。
- 沒有「暫停一日」等通知動作按鈕。
- 沒有校務連線，段考與公告檢查沒有真實資料來源。
- 設定未納入設定匯入匯出。
- App Group `group.com.clhs.pocket.ios` 與 Live Activity 在實機上需要對應的開發者帳號設定；目前只在 Simulator 以 ad-hoc 簽署驗證。建置時不可加 `CODE_SIGNING_ALLOWED=NO`，否則 entitlement 不會嵌入，App Group 會失效。

## 示範時鐘

`-demoClock 2026-11-18T10:35` 會把課表時間固定在該時刻。為了讓小工具與即時動態的倒數能即時前進，`PlatformClock` 會記住第一次啟動時的時差，之後示範時間隨真實時間流動；快照中的時間點也依此平移。只影響示範資料。

## 驗證

指令見 [iosApp/README.md](../../iosApp/README.md)。

- 共用 Kotlin：`PlatformPlanningTest`（時間軸邊界、過期、隱私、提醒、安靜時段兩側邊界、公告基準）。
- iOS 單元測試：`CLHSPocketTests`（Kotlin 產生的快照可由 Swift 解碼並驅動時間軸、儲存與清除、即時動態狀態、Deep Link、通知排程與權限、capability 一次性、登出清理、設定）。
- Simulator（iPhone 17、iOS 26.5）實際操作：小、中、大小工具加入主畫面（淺色、深色、色調）；鎖定畫面矩形與圓形小工具，並觀察到它們在下課與上課前 5 分鐘的邊界自行切換；通知權限詢問、通知橫幅與鎖定畫面通知；點擊通知導向成績頁；`clhspocket://announcements`；即時動態的鎖定畫面、動態島 compact 與 expanded，以及等待中的課程在上課時間自行變成「上課中」；登出後快照檔案、capability、公告基準與即時動態都被清除，小工具回到「尚無課表」。
- 僅通過編譯、未實際執行：背景更新的處理函式（Simulator 不會排程 `BGAppRefreshTask`）、鎖定畫面單行小工具、動態島 minimal 版面、iOS 18 執行環境、iPad、實機。
