# 當前課程通知

- 設定頁可選「上課前 5 分鐘提醒」，獨立偏好預設關閉且立即儲存、重新排程。開啟後沿用同一通知 ID 與 boundary Alarm，在上課前五分鐘顯示「即將上課」與上課倒數，開始上課後切換當前課程及下課倒數；不另加聲音、震動或通知 Channel。主開關關閉、今日暫停、無有效課表或通知權限不足時皆不顯示提前提醒。系統 Alarm 仍受既有精確鬧鐘偏好及省電限制影響，可能延遲；遲到時依實際時間解析課程。

入口只有課表右上角三點選單。獨立 DataStore `current_course_notification` 的 `enabled` 預設 false，不繼承公告、FCM、提醒或 Widget 開關。沒有登入、沒有可用課表或只有無法解析的節次時，提示先取得有效週課表或學期課表，不代為查詢校務系統。登出後課表的登入提示仍保留三點選單，可關閉原先啟用的偏好。

## 資料與生命週期

- `data/CourseResolution.kt` 將正式週課表日期與 `PERIOD_TIMES` 結合，固定以 Asia/Taipei 轉成 Instant。`occurrenceOn()`／`CourseOccurrence.contains()` 也供首頁和 Widget 判斷目前課程，區間為 `[start, end)`。同名不同節以日期時間和節次識別；重疊資料不通知，未知／異常時間不推測，連堂依正式節次分開。
- 通知讀取 `GradeCacheStore.courseNotificationScheduleReportFlow()` 的授權快照，包含科目自訂；不讀 Session 憑證或發出背景校務請求。目標日期在有效 CURRENT_WEEK 範圍內時一律採週課表，即使查無課節也不回退學期快取；範圍結束後才回退同學生／班級的學期快取，按星期與節次循環。學期資料沒有截止日，假日或學期結束後仍可能通知；週課表上的補課、假日、考試與調課只採用已取得的報告。
- `CourseNotificationCoordinator` 以 Mutex 序列化偏好、滑除恢復、明確關閉、快照提交與重新解析。`GradeCacheStore` 的提交鉤子先撤下通知，再完成本機快照寫入，最後重算；私人資料刪除失敗仍傳遞給原有清理 owner。通知故障不阻止快照儲存或隱私清理。
- `SessionStore.revoke()` 同步使已讀取的 generation 失效並取消通知／鬧鐘；最終發布仍在授權 generation 檢查內執行。背景只做本機工作。前景、開機、套件更新、時間／時區變更均呼叫同一協調器。

## 通知、排程及關閉

- Channel `current_course_v1` 首次建立為 LOW、無聲、無震動、無 badge；不重建已有 channel。通知 ID／requestCode 為 `42031`，與 Widget 完全分開。一般排程使用 `setAndAllowWhileIdle(RTC_WAKEUP)`；使用者開啟精確鬧鐘偏好且系統授權仍有效時，課程邊界才使用 `setExactAndAllowWhileIdle()`。Recovery 一律使用一般鬧鐘；電池豁免不會改變鬧鐘精度。不加 FGS、輪詢或 WorkManager。
- Manifest 宣告非執行階段權限 `POST_PROMOTED_NOTIFICATIONS`，通知以 `setRequestPromotedOngoing(true)` 要求 Android 將符合資格的當前課程通知升級為 Live Update。課程由使用者在 overflow 明確啟用，且只在已開始、有明確結束時間時發布；不把尚未開始的下一堂課或一般環境資訊宣傳為 Live Update。不支援或未允許宣傳通知的系統仍顯示相同的標準 ongoing 通知。
- 通知使用標準樣式、ongoing、NO_CLEAR、PRIVATE、silent、onlyAlertOnce、chronometer countdown 及當節剩餘時長 timeout；不含 RemoteViews、群組摘要或 colorized。Live Update 狀態方塊以 `setShortCriticalText` 顯示目前科目，完整通知仍以 `setWhen` 與倒數 chronometer 顯示下課倒數，不寫死「剩餘幾分鐘」。系統可能依 96dp 上限縮短過長科目名稱。通知小圖示保留額外透明安全留白，避免 Samsung Now Bar 的圓形容器裁到馬頭圖案，並以 App 圖示共用的 `clhs_pocket_icon_blue` 作為系統 accent color。未開啟提前提醒時，休息期間不顯示通知；無有效資料時皆不顯示。
- 點擊透過既有一次性 capability，交給 MainActivity 的 AppLaunchTarget.Schedule，保留登入、生物辨識與 Navigation 3 頂層導覽。每次發布會撤銷尚未使用的舊 token；點擊後立即刷新 ongoing 通知的 token，讓後續點擊仍可使用且不累積失效 token。
- Android 14+ 使用者滑除通知時，專用 deleteIntent 只重新讀取偏好與本機課表；若仍在上課且 enabled=true，使用同一通知 ID 恢復顯示。通知展開後的「暫停一日」保存暫停至 Asia/Taipei 次日零時，保留 enabled=true，撤下通知並以原 boundary Alarm 排定隔日恢復。暫停跨 process／重新開機保存，滑除、前景與快照更新不會提前恢復；App 設定頁關閉開關才會永久停用，再開啟會清除今日暫停。關閉動作帶有 generation，晚到的舊 PendingIntent 不會暫停之後重新啟用的狀態。
- 權限／Channel 封鎖保留 enabled 意圖，撤下通知與排程；選單顯示權限不足並提供使用者點擊的系統設定入口。權限重新有效後由前景同步恢復。Receiver 使用 goAsync、8 秒 timeout 與 finally finish；逾時後最多用一秒嘗試補排 recovery Alarm，並記錄固定診斷碼；下一次前景／有效事件也會重試，不啟動背景服務。
