# 功能維護契約

本文件供修改特定功能前按需閱讀；UI 規範見 [DESIGN.md](../../DESIGN.md)，整體責任見 [資料流](data-flow.md)。以下檔名除測試外，均可在 `android/app/src/main/java/com/clhs/score/` 對應 package 找到。測試位於 `android/app/src/test/java/com/clhs/score/`。

## 登入、Session 與私人資料

入口：`data/SessionStore.kt`、`data/SchoolGradeClient.kt`、`data/GradeCacheStore.kt`、`viewmodel/ScoreViewModel.kt`。另見 [校務系統邊界](school-system.md)。

- `prepareSession(session)` 按學號及鎖載入 Cookie，所有 CookieJar 讀寫維持 synchronized；同 host 與總併發限制皆為 4，批次請求不得另建 client 繞過限制。
- 只有新登入的 `establishSession()` 可建立 authority。儲存與解鎖只能沿用已驗證 authorization ID，不能使 revoked credential 重新有效。
- 一般與提醒 Session 使用 Keystore AES-GCM、不同 AAD 與 Proto DataStore；保留原子更新、generation guard、VALID／UNKNOWN／REVOKED 及 cleanup pending。不得用 `runBlocking` 包裝 suspend store API。
- 登出先清除 runtime session、發布 Guest 並 revoke，再逐項清理。全部成功才清除 pending；啟動及新登入重試未完成清理，新登入等待 cleanup job。
- 一般 cache I/O 可降級為 miss；`clearStudent()`、`clearAll()` 與 Widget 隱私刪除必須傳遞失敗。Widget／reminder 同時檢查 authority metadata，不能因實體檔案刪除失敗而繼續顯示舊私人資料。
- 舊 `score_session` 由 `LegacySessionPreferences` 整份刪除，不讀取、解密或遷移。沒有 authority 的舊 Session 視為 UNKNOWN；清理失敗交由 owner 重試。
- 生物識別使用 PIN 衍生 Session key 與 biometric-bound PIN wrapping；備用 PIN 失敗由持久化 `PinAttemptLimiter` 執行最長五分鐘 backoff。解鎖先 activate 記憶體 Session，再解除鎖定，不恢復一般儲存副本。
- `MainActivity` 保留 biometric prompt single-flight，避免 prompt lifecycle 被誤認為背景返回；隱私鎖啟用時保留 `FLAG_SECURE`。Manifest 與 backup rules 禁止備份／轉移 Session 材料。
- 個人頁進入時才從 `ICampus/Home/Index2` 的 `userInfo` 載入學生資訊；保留 single-flight、學號比對與按學生 cache。restore 只讀 cache，不在登入／還原時預抓或改從成績報告取資料。
- 啟動設定讀取／migration I/O 失敗顯示重試頁；訂閱失敗保留前值並阻止進入內容，成功重新訂閱後才 ready。不得以預設 biometric=false 取代讀取失敗。驗證 `SettingsIoRecoveryTest`。
- 缺少 `has_completed_onboarding_v2` 時原子清空 `app_settings` 並寫入 false；完成或稍後登入才設 true。重新執行首次設定只重設旗標，不清除 Session。

檢查：`ArchitectureBoundaryTest`、`SessionStoreTest`、`SessionCryptoTest`、`PinAttemptLimiterTest`、`GradeCacheStoreTest`、`ScoreViewModelTest`、`SettingsRepositoryTest`。若撤銷寫入全部失敗且 process 死亡，不能宣稱記憶體撤銷能保證跨 process 安全。

## 公開資料與總覽

入口：`data/SchoolAnnouncements.kt`、`data/SchoolCalendar.kt`、`data/WeatherRepository.kt`、`data/LatestCacheFile.kt`、`domain/overview/`。

- 公告、行事曆與天氣使用無 Cookie client；總覽先顯示 cache，來源各自更新與降級，成績只讀提醒 change set，不因開啟總覽另查成績 API。
- 總覽自訂沿用 `app_settings`，保存區塊開關、六個區塊的排列順序、行程／公告各自 1–20 筆的上限與公開行事曆事件 ID 集合（相容原單一事件設定，寫入時移除舊 key）；由 `OverviewViewModel` 合併偏好與總覽狀態，不以跨來源總數截斷資料。近期行程仍限未來 14 天，置頂公告限已載入頁面；倒數可多選任意尚未結束事件，不受 14 天限制，沿用每分鐘時鐘，全天事件按日期差計算，結束時間不包含在活動期間。事件移除時保留選擇並提示重選，儲存失敗保留草稿供重試。區塊順序略過未知或重複值，缺少的區塊依預設順序補齊；拖曳與無障礙上移／下移只改草稿，恢復預設與復原也涵蓋順序。
- 總覽課程進度由 `OverviewCoordinator` 依 `PERIOD_TIMES` 計算，隨既有每分鐘時鐘更新；只有上課中的 Hero 提供 `courseProgress`，畫面使用 `LinearWavyProgressIndicator`。下一個上課日依日期差顯示「明天／後天／N 天後」，保留課表有效日期限制。
- `LatestCacheFile` 同 canonical path 的 instance 共用 generation／提交鎖。網路開始前取得 generation，每次用唯一 temp，只有最新 generation 可 atomic replace。
- 公告全部單位的 `flock` 為空，限定單位用 `unit_<ID>`；獨立 `unit` 參數無效。列表首筆是分頁 metadata；只有無關鍵字、全部單位的第一頁寫入離線 cache。
- 詳情先由 `show.php` 取 `g_news_unique_id` 再取 content。HTML 移除主動內容與危險 scheme；只有 HTTPS 校網圖片自動載入，其他連結由使用者開啟。詳情限制 2 MiB。
- 行事曆只讀固定學校 Google Calendar ICS，使用 biweekly，限制 5 MiB、cache 六小時；失敗可使用舊 cache。目前不展開 RRULE，略過時提示使用者。
- 天氣固定學校座標、不要求定位；預設 Open-Meteo，CWA 使用 `F-D0047-005` 中壢區預報，不稱為校內觀測。來源各自 cache，新鮮 30 分鐘，降級最多六小時。
- CWA key 使用 Keystore AES-GCM 加密於 `noBackupFilesDir`，只放 Authorization header；查詢用 `LocationName`／`ElementName`。共用 `fetch()` 必須在 `Dispatchers.IO` 讀完整 body，僅非同步 execute 不足以離開 Main。

檢查：`LatestCacheFileTest`、`SchoolAnnouncementsTest`、`SchoolCalendarTest`、`WeatherRepositoryTest`、`OverviewCoordinatorTest`。公開 API 契約用本機 fixture；CI 不依賴校網即時回應。

## 背景提醒與通知

入口：`reminders/`、`notifications/`、`data/AnnouncementReminderRepository.kt`。

- 段考更新提醒 `grade_reminder_poll` 為 WorkManager 15 分鐘週期，開始前需通知權限與忽略電池最佳化許可；仍不保證即時。只能讀提醒專用 Session，最長 48 小時，不 fallback 到一般 Session。
- 登入失效停止；逾時、408／429／5xx 重試，其他錯誤才累計連續失敗。停止／過期／登出／切換學生時取消工作並清除 Session、snapshot、change set。
- `GradeReportDiffer` 比對可見資訊及區塊新增／移除，不比 rawResult、HTTP 格式或學生資料時間戳。啟動可補排未過期工作，不因每次 state 更新重排。
- 公告更新提醒權威讀取必須傳遞 I/O 失敗給 Worker retry；ViewModel 保留前值／排程並標示 unavailable，每五秒可取消地重新訂閱。驗證 `AnnouncementReminderRepositoryTest` 與 `SettingsIoRecoveryTest`。
- 公告更新提醒 `school_announcement_poll` 與 FCM、段考更新提醒獨立；不要求忽略電池最佳化。多單位依序查詢並按 ID 去重。
- 首次啟用／更換單位讀最新三頁建立不通知的基準；已有 known ID 時查到已知項目或最多 20 頁。使用 v2 基準及未讀 keys，不沿用舊篩選結果。
- 一筆新公告直達詳情，多筆到列表；以一次性 capability 發出通知 action。詳情成功顯示才清未讀；停用或權限撤銷清除基準／未讀，保留單位／頻率偏好。
- `MainActivity` 不信任裸 Intent extras；保留 `NotificationActionCapabilities` 與鎖。系統通知權限在 App 根層同步，僅使用者操作時 request；永久拒絕走系統設定。
- FCM 推播使用 `general`／`app_updates`，跟隨開關訂閱；不承載私人 Session 或成績。段考 channel 保留 high importance，既有安裝仍受系統 channel 設定限制。
- 執行資訊頁列出 App 所有 WorkManager 工作，只讀、不提供取消。Debug reminder receiver／Widget capture activity 保留 `android.permission.DUMP`；不得搬至 release。

檢查：`GradeReportDifferTest`、`GradeReminderWorkerTest`、`AnnouncementReminderRepositoryTest`、`NotificationPermissionArchitectureTest`、`ArchitectureBoundaryTest`。

## 課表、科目偏好與 Widget

入口：`viewmodel/ScheduleViewModel.kt`、`data/` 的 Schedule models／repository、`widget/`。

- Grid 保留 1–8 節，API 額外正節次用稀疏清單追加，不建立巨大連續範圍。
- 週次依本地日期落入 StartDateDisplay／EndDateDisplay 唯一範圍決定，不依 Selected。整週實際最後一節下課後才自動刷新，無法辨識節次以 16:55 備援；後續目標為 weekStartDate 加一週，不用 weekEndDate 判斷。
- 週次不唯一或查詢失敗回學期課表並提示。週課表成功後取得學期課表比對科目／教師／教室；兩份結果分別存 current-week／semester cache，不能互覆，也不保存未使用 rawData。
- 科目自訂依學生及原始科目名稱保存，僅在顯示前共用套用；cache 的 items／changes 維持原始資料。登入／登出／失效清理用 `preserveSubjectOverrides = true`；明確清學生或全部資料才完整刪除，儲存失敗必須傳遞。
- Widget 只讀專用快照及 authority metadata；課表成功與舊 cache 載入都同步快照。有效本週快照不得被學期覆蓋；過期可由 App 提供學期 fallback。顯示日期不得早於快照 weekStartDate，不自行查 API 或比對調課。
- 更新沿用 `setAndAllowWhileIdle(RTC_WAKEUP)`，依課程開始／結束與午夜排程，不要求 exact alarm。上課時間含開始、不含結束；下課更新延遲五秒。開機由已存在 Widget 的 receiver 重排，不另設 BOOT_COMPLETED。
- 個別 GlanceId 保存教師／地點／時間／下課切日偏好；配置儲存後把本次選項直接傳給 targeted sync。全體 sync 只同步快照與 theme，不能覆蓋個別偏好。舊全域偏好只補缺值。

檢查：`ScheduleViewModelTest`、`ScheduleGridTest`、`ScheduleSubjectOverrideTest`、`ScheduleWidgetDaySwitchTest`、`ArchitectureBoundaryTest`。尺寸、預覽與 RemoteViews 規則見 DESIGN.md。

## 發布、套件與整合

- 更新檢查由 MainActivity 對共用 SettingsViewModel 觸發一次；自動檢查間隔 12 小時，手動不受限。保存 stable release 資訊，已安裝相同或更新版本清掉 cache。
- APK 下載保留 HTTPS、大小、SHA-256、取消與 atomic replacement 驗證，再交 FileProvider／系統安裝器；不靜默安裝。檢查 `UpdateApkDownloaderTest` 與 `SettingsViewModelUpdateCheckTest`。
- Release 保留 R8 與 resource shrinking，不新增 broad keep。protobuf-lite message／欄位名與 biweekly 資源相對路徑、反射 constructor／欄位需保留；修改規則需檢查 release DEX／mapping，biweekly 用完整公開 ICS 驗證，不能只靠 debug 或簡化 ICS。
- 保留 default／zh-TW resources、停用 App Bundle 語言分割、不封裝 `.proto`、維持未壓縮 DEX。Runtime 開源依賴異動同步授權清單與 `OpenSourceLicensesTest`。
- Analytics 只經 `AnalyticsLogger`、常數與 sanitizer 白名單；不設 user ID，不送學生資料、成績內容、URL、憑證或錯誤原文。參數僅 enum、boolean、count、bucket；更新 `AnalyticsParameterSanitizerTest`。
- Remote Config 的 `feedback_form_url` 只接受 `https://forms.gle` 或 `https://docs.google.com/forms/`，不得放秘密。
- 成績 CSV 由 `ScoreViewModel.exportGrades()`／`GradeExporter` 產生 BOM + UTF-8 並經 MediaStore 存 Downloads；保留跨學期選擇、未快取考試查詢與既有併發限制。
