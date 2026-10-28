# 功能維護契約

本文件供修改特定功能前按需閱讀；UI 規範見 [DESIGN.md](../../DESIGN.md)，整體責任見 [資料流](data-flow.md)。以下檔名除測試外，均可在 `android/app/src/main/java/com/clhs/score/` 對應 package 找到。測試位於 `android/app/src/test/java/com/clhs/score/`。

## 登入、Session 與私人資料

入口：`data/SessionStore.kt`、`data/SchoolGradeClient.kt`、`data/GradeCacheStore.kt`、`viewmodel/ScoreViewModel.kt`。另見 [校務系統邊界](school-system.md)。

- `prepareSession(session)` 按完整 session 與鎖載入 Cookie；同學號的新授權也必須更新 Cookie 並清頁面 token。每條請求鏈固定 generation，舊回應及 redirect 不得讀寫新 session Cookie。所有 CookieJar 讀寫維持 synchronized；每個 generation 的衍生 client 共用 dispatcher／connection pool，同 host 與總併發限制皆為 4，批次請求不得另建獨立 client 繞過限制。
- 成績回應的 Result 必須為非空 object；明確且非空的學號不符以一般 SchoolException 拒絕，不寫快取，也不因此宣告目前登入過期。合法空科目清單仍可顯示；這不等於驗證所有 optional 欄位的完整 schema。
- 只有新登入的 `establishSession()` 可建立 authority。儲存與解鎖只能沿用已驗證 authorization ID，不能使 revoked credential 重新有效。
- 一般與提醒 Session 使用 Keystore AES-GCM、不同 AAD 與 Proto DataStore；保留原子更新、generation guard、VALID／UNKNOWN／REVOKED 及 cleanup pending。不得用 `runBlocking` 包裝 suspend store API。
- 登出先清除 runtime session、發布 Guest 並 revoke，再逐項清理。全部成功才清除 pending；啟動及新登入重試未完成清理，新登入等待 cleanup job。
- 一般 cache I/O 可降級為 miss；`clearStudent()`、`clearAll()` 與 Widget 隱私刪除必須傳遞失敗。Widget／reminder 同時檢查 authority metadata，不能因實體檔案刪除失敗而繼續顯示舊私人資料。
- 舊 `score_session` 由 `LegacySessionPreferences` 整份刪除，不讀取、解密或遷移。沒有 authority 的舊 Session 視為 UNKNOWN；清理失敗交由 owner 重試。
- 生物識別使用 PIN 衍生 Session key 與 biometric-bound PIN wrapping；備用 PIN 失敗由持久化 `PinAttemptLimiter` 執行最長五分鐘 backoff。解鎖先 activate 記憶體 Session，再解除鎖定，不恢復一般儲存副本。
- `MainActivity` 保留 biometric prompt single-flight，避免 prompt lifecycle 被誤認為背景返回；隱私鎖啟用時保留 `FLAG_SECURE`。Manifest 與 backup rules 禁止備份／轉移 Session 材料。
- 個人頁進入時才從 `ICampus/Home/Index2` 的 `userInfo` 載入學生資訊；保留 single-flight、學號比對與按學生 cache。restore 只讀 cache，不在登入／還原時預抓或改從成績報告取資料。
- 啟動設定讀取／migration I/O 失敗顯示重試頁；訂閱失敗保留前值並阻止進入內容，成功重新訂閱後才 ready。不得以預設 biometric=false 取代讀取失敗。驗證 `SettingsIoRecoveryTest`。
- 「啟動時開啟課表」與精準鬧鐘偏好寫入 I/O 失敗時，保留原值並提示重試，不得讓例外造成崩潰；精準鬧鐘偏好儲存成功後才重排既有鬧鐘。取消例外仍須傳播。
- 缺少 `has_completed_onboarding_v2` 時原子清空 `app_settings` 並寫入 false；完成或稍後登入才設 true。重新執行首次設定只重設旗標，不清除 Session。

檢查：`ArchitectureBoundaryTest`、`SessionStoreTest`、`SessionCryptoTest`、`PinAttemptLimiterTest`、`GradeCacheStoreTest`、`ScoreViewModelTest`、`SettingsRepositoryTest`。若撤銷寫入全部失敗且 process 死亡，不能宣稱記憶體撤銷能保證跨 process 安全。

## 公開資料與首頁

- 行事曆活動的「加入首頁倒數元件」帶入活動 ID／標題，在既有首頁編輯草稿最前方新增學校活動倒數並切到首頁；保留其他未儲存編輯，只有儲存才提交，取消不寫入。不再啟動系統行事曆新增 Intent。

- 首頁左上角的正式 logo＋Outfit `Pocket` 品牌列屬於 grid header 的捲動內容，沿用原有 header 總佔位高度；正常捲動時移出畫面，之後由日期時間膠囊接替，不新增 nested scroll 或尺寸動畫。編輯模式只顯示日期時間膠囊。logo 與字樣未用動態色彩時讀取 `clhs_pocket_icon_blue`，有效啟用動態色彩時採用 Material `primary`；不能以基礎 theme 的 primary 取代正式 logo 藍色。

- 重要公告元件的 `ListItems.announcementPriority` 是未讀、置頂、收藏的完整排列，預設依此順序逐項比較，再按日期由新到舊排序。候選為首頁既有公告來源中的未讀／置頂公告及本機收藏，收藏可補入首頁來源之外的公告；同 ID 去重並優先採用目前來源資料。沿用閱讀 Room、收藏 DataStore 與各自 ViewModel，不額外抓取所有公告頁。設定只影響首頁元件，不改校園公告列表。
- 近期行程的 `ListItems.maxEventDurationDays` 預設 `null`（不過濾）；設定正整數後，完整 `start → endExclusive` 長度超過該天數的行程，在取顯示筆數之前排除，相同長度仍保留。全天事件沿用 ICS 排他結束時間，因此單日事件長度為一天。行事曆與倒數不受影響。兩項設定沿用元件 JSON version 1 的新增預設欄位，不改 Room schema；確定只更新編輯草稿，完成才儲存，取消不寫入。

- 日期時間沿用 `OverviewState.now`，視覺上是首頁第一張卡片，不在可編輯 grid／新增選單中，不能移動、縮放或移除。`OverviewWidgetGrid` 提供捲動位移，由自製 `OverviewDateTimeHeader` 配合 Header 專用的臨界阻尼 spring（`DampingRatioNoBouncy`），連續變化位置、尺寸、形狀與字體，收合成狀態列下方左上角的單行 pill（短日期＋時間，水平與垂直置中；日期用 onSurfaceVariant、時間用 primary），卡片先隨內容正常向上捲動，底緣到達 pill 底緣時完整收合；展開卡片不提前釘在停靠線，收合動畫只改 overlay，不改 grid 的固定 header 佔位高度、內容座標或捲動範圍；overlay 的捲動行程限制到停靠門檻，快速甩動不得將尚在變形的時鐘拉出畫面。時間使用同一份文字連續移動與縮放，膠囊日期僅淡入，不以兩份時間文字交接；膠囊文字尺寸以最終寬度量測，避免動畫期間重新縮字跳動；寬度需單向收斂至目標，不沿用會越過端點再回彈的 spatial spring，避免進度 clamp 後又拉寬膠囊；回捲低於停靠門檻 16dp 時完整展開，不以捲動比例停留在中間形狀。編輯模式直接固定為膠囊並縮短 header 佔位，不自動捲動；一般模式才依捲動轉換。元件位置動畫僅用於編輯排列，首次量測與一般頁面重入不動畫定位；頂部不預留 App Bar viewport，內容可從浮動 pill 與右側操作入口後方捲至狀態列。首頁最外層共同消耗橫向 `safeDrawing` insets，日期時間 overlay 與 grid 共用相同可用寬度，橫向瀏海／系統安全區不可只套用在 grid。歷史 `date_time` 類型仍可解碼／原樣儲存，但 UI 隱藏；新預設與 legacy 匯入不新增此類 row。

入口：`data/SchoolAnnouncements.kt`、`data/SchoolCalendar.kt`、`data/WeatherRepository.kt`、`data/YouBikeRepository.kt`、`data/LatestCacheFile.kt`、`domain/overview/`。

- 公告、行事曆與天氣使用無 Cookie client；首頁先顯示 cache，來源各自更新與降級。首頁可見／STARTED 訊號從關閉轉為開啟時，重新檢查課表、公告與行事曆的有效期限；重複的開啟訊號不重啟查詢。已載入課表後，切回首頁重新讀取／檢查快取不得啟用 `isScheduleRefreshing`，避免「課表更新中」短暫插入造成版面位移；首次載入保留 loading，實際查詢過期課表仍顯示更新狀態。離開首頁暫停天氣觀察時保留已載入快照；行事曆與公告的首次載入旗標在取得快取／結果後關閉，背景刷新不重顯骨架，已知空結果也保留空狀態。成績只讀提醒 change set，不因開啟首頁另查成績 API。
- YouBike 使用官方 `https://apis.youbike.com.tw/json/station-yb2.json` 公開資料，依設定的 `station_no` 選取，舊版學校站點為 `500304002`，站名沿用官方「中壢高中」；新預設配置不含 YouBike，使用者須自行新增並選站；使用無 Cookie／憑證 client，解壓後回應限制 8 MiB，解析陣列與 `available_spaces_detail` 物件。可借、空位各讀原欄位，不能由總車位推算；車種明細缺失或不符總數時只降級明細。`status=2` 呈現暫停營運（1 為營運中）。來源 `updated_at` 以 Asia/Taipei 解析，超過五分鐘或在未來時標示可能過期，不以取得時間掩蓋舊來源時間。
- YouBike 沿用首頁可見／STARTED 訊號；widget 路徑依啟用元件設定去重站點，多站共用一次公開 feed 請求與 JSON 解析，建立站號索引後各自驗證快照，立即載入後每 60 秒更新。非物件資料列略過，重複站號視為該站資料不可用；單站解析失敗不覆蓋其他站點。搜尋比對站名、行政區與地址，沿用台／臺正規化。離開、背景或沒有啟用站點時取消。手動重試不刷新其他來源。只留記憶體快照，失敗保留資料並提示，首次載入不阻擋整頁；取消不轉成失敗。Guest 可用，Demo 與 Production 都連公開即時 API；只有 Preview／隔離單元測試用固定數量假資料。舊單站 observe 仍支援 legacy 顯示偏好。
- 首頁以 `ScoreViewModel.authState` 的登入世代隔離私人資料；非登入狀態不訂閱課表與成績提醒，提醒 state 與 change set 均須符合目前學號。畫面在認證 collector 執行前也要檢查世代；普通刷新沿用聚合內容，帳號變動取消舊工作並拒收舊結果。首頁自動查課表傳入發起時的 `expectedSession`，避免舊查詢參數套用到新帳號；取得請求鎖與快取讀取完成後再次檢查 Session，快取寫入維持授權檢查。
- 天氣設定 Flow 的 `IOException` 在來源內以 1、2、4 秒至最多 30 秒退避重試；legacy 與元件路徑皆保留可用天氣並標示部分失敗，無舊值時呈現 unavailable，不補預設來源。其他一般例外只隔離、不自動重試；協程取消必須傳遞。
- 首頁注入 `Clock`，以 `Instant` 計算快取年齡，以明確的 `Asia/Taipei` 計算課程、日期與倒數；不使用裝置時區解讀學校時間。公告相對日期沿用首頁 state 的日期。
- 首頁配置由 `OverviewLayoutRepository` 的獨立 Room `overview_layout.db` 保存，Room schema 版本 1、排列格式版本 2；格式 1 保留舊版回填排版的讀取與視覺順序轉換，完成才在同一 transaction 寫入新 rank 與格式 2（見 [排列決策](../decisions/001-overview-reading-order.md)）；記錄元件身份、順序、尺寸、開關與版本化設定。首次初始化只有在實際存在 legacy overview keys 時才匯入 `app_settings`，保留原順序、隱藏項目、筆數、天氣來源與倒數事件，否則建立四個預設元件：課表摘要、課程／成績異動、近期行程、重要公告。重設也使用此順序；天氣、YouBike 與倒數由使用者自行新增。v2.0.0 沒有 YouBike 區塊，legacy 匯入必須排除 `orderedSections()` 自動補上的 YouBike，不可因升級而啟用；已提交的 Room 排版不會自動移除元件。新增元件入口及 YouBike 選單項目以紅點提示；看過並離開新增選單（關閉或選取）後，透過 `OverviewViewModel → SettingsRepository` 持久化 `has_seen_youbike_widget`，重開 App 或重設排版不重新提示，儲存失敗則保留提示。不刪除 legacy keys；初始化與 marker 同一交易提交，已初始化的空排版不會重匯入。未知類型或設定保留，已知 singleton 不得複製。`OverviewViewModel` 持有 committed／draft／resetUndo，草稿透過 SavedStateHandle 還原；移動、調整尺寸、重設、復原只改草稿，完成才原子提交，失敗保留草稿。近期行程仍限未來 14 天，重要公告限已載入頁面；倒數沿用事件身份與首頁時鐘，不另查來源。既有 grid packing／resize 算法維持原責任。
- 首頁課程進度由 `OverviewCoordinator` 依 `PERIOD_TIMES` 計算；每秒時鐘只在首頁可見且 Activity 至少為 STARTED 時運作，返回首頁立即更新。公告與成績投影及行事曆排序只在來源更新時計算；時間相依的課程、事件與倒數保留每秒推進，UI 分類與區塊順序依資料及偏好記憶化。只有上課中的 Hero 提供 `courseProgress`，畫面使用 `LinearWavyProgressIndicator`。下一個上課日依日期差顯示「明天／後天／N 天後」，保留課表有效日期限制。課程異動跟隨 Hero 日期篩選；當天尚未到原下課時間的停課異動也須保留，即使 Hero 已指向下一個上課日或不存在，仍須符合課表有效日期。不以今天的星期套用其他週的異動；課表區塊使用獨立的 `isScheduleRefreshing` 顯示更新狀態。倒數的 `displayDate` 使用實際倒數目標日期，全天事件結束扣除排他邊界；全天行程從 start（含）到 endExclusive（不含）顯示「進行中」，開始前才倒數；「已到期／已結束／今天／進行中」不附加剩餘時間前後綴，畫面與無障礙描述沿用同一份文案。
- `LatestCacheFile` 同 canonical path 的 instance 共用 generation／提交鎖。網路開始前取得 generation，每次用唯一 temp，只有最新 generation 可 atomic replace。
- 公告全部單位的 `flock` 為空，限定單位用 `unit_<ID>`；獨立 `unit` 參數無效。列表首筆是分頁 metadata；只有無關鍵字、全部單位的第一頁寫入離線 cache。
- 詳情先由 `show.php` 取 `g_news_unique_id` 再取 content。HTML 移除主動內容與危險 scheme；只有 HTTPS 校網圖片自動載入，其他連結由使用者開啟。詳情限制 2 MiB。
- 公告收藏使用獨立的 `announcement_bookmarks` Preferences DataStore，保存穩定 `newsId` 與清單摘要，不保存 HTML、Session 或個資。收藏不改 Room 閱讀狀態、提醒基準或公告 cache；收藏清單離線顯示摘要，點選後仍以 ID 重新載入來源詳情。外部連結重新驗證後才開啟。收藏不綁校務帳號，登出不清除此公開內容偏好。
- 行事曆只讀固定學校 Google Calendar ICS，使用 biweekly，限制 5 MiB、cache 六小時；失敗可使用舊 cache。目前不展開 RRULE，略過時提示使用者。
- 天氣預設學校座標、不要求定位；widget 可指定來源與地點，驗證座標、時區及 CWA 適用性。相同來源／地點共用一個資源 Flow，不同資源分離 cache；學校預設保留既有 cache 路徑。新鮮 30 分鐘，降級最多六小時，強制重試略過新鮮 cache。Open-Meteo 使用實際 hourly 預報，CWA 使用實際溫度時段並在該時段取得其他元素；不捏造預報時間或數值。預設 CWA 為 `F-D0047-005` 中壢區預報，不稱為校內觀測。
- CWA key 使用 Keystore AES-GCM 加密於 `noBackupFilesDir`，只放 Authorization header；查詢用 `LocationName`／`ElementName`。缺少授權碼時，點擊首頁天氣卡片直接開啟既有授權碼對話框，與元件設定共用 SettingsViewModel 儲存流程。共用 `fetch()` 必須在 `Dispatchers.IO` 讀完整 body，僅非同步 execute 不足以離開 Main。
- 天氣高低溫：Open-Meteo 查詢 `daily.temperature_2m_max/min`，依地點時區匹配取得當日，不假設陣列第一筆；跨日後不顯示舊的當日高低溫。CWA 三日產品沒有官方每日高低溫，使用取得時間起 24 小時內的 `DataTime` 溫度點計算預報範圍，不稱全天極值。介面只以箭頭與溫度顯示範圍，不加「24 小時／今日」標籤；降雨機率以雨滴圖示與百分比顯示，不附加「N 小時內」文字。缺值略過，不以目前溫度補值；保留既有快取相容性。

檢查：`LatestCacheFileTest`、`SchoolAnnouncementsTest`、`SchoolCalendarTest`、`WeatherRepositoryTest`、`YouBikeRepositoryTest`、`OverviewCoordinatorTest`、`OverviewWidgetCoordinatorTest`、`OverviewReliabilityTest`、`OverviewEditorTest`、`OverviewLayoutRepositoryTest`、`OverviewWidgetLayoutTest`、`ActiveSessionResolverTest`、`SessionStoreTest`。公開 API 契約用本機 fixture；CI 不依賴校網即時回應。

- 公告列表的單位與類別選項共用官網首頁及既有單位 HTML 快取；伺服器篩選以 `flock=unit_<ID>-attr_<ID>` 組合，全部省略該條件。搜尋、重新整理、分頁保留兩個篩選 ID，切換條件取消舊查詢並回到第一頁；清除關鍵字保留篩選。僅無關鍵字、無篩選的第一頁可寫一般公告快取，列表篩選不改公告提醒的單位偏好。檢查 `SchoolAnnouncementsTest`、`SchoolAnnouncementsViewModelTest`。

## 背景提醒與通知

入口：`reminders/`、`notifications/`、`data/AnnouncementReminderRepository.kt`。

- 當前課程通知的「上課前 5 分鐘提醒」沿用同一 DataStore，預設關閉；切換由既有 Coordinator 序列化、立即重新排程且保留今日暫停。`resolveCourses()` 只有通知 owner 傳入此偏好時才提供 upcoming 與提前邊界；上課中的 current 優先，通知使用同一 ID，提前期間倒數至上課，上課後倒數至下課。主開關、權限、快照授權與今日暫停同樣約束提前提醒。

- 當前課程通知僅從課表 overflow 選用，獨立偏好預設關閉；共用授權快照與 `CourseResolution.kt`。目標日期落在有效 CURRENT_WEEK 範圍內時，週課表為權威來源，即使當日或整週沒有可解析課節，也不回退學期快取；範圍結束後才回退同學生／班級的學期快取，按星期與節次循環且沒有截止日，假日或學期結束後仍可能通知。快照提交、偏好、滑除恢復及明確關閉由 `CourseNotificationCoordinator` 序列化，撤銷時同步取消。快照讀取／Receiver timeout 暫時失敗時，僅排一次五分鐘後的 recovery Alarm；timeout 後最多用一秒嘗試完成 recovery 判斷與補排。該次 recovery 再失敗不連鎖重試，成功後恢復真正課程邊界。停用與 session invalidation 清通知與 Alarm；有效週內無課時清通知，但保留週末邊界 Alarm 以在過期後重新解析。通知要求 promoted ongoing Live Update，不支援／未允許時降級為同一標準 ongoing 通知。一般排程預設使用 `setAndAllowWhileIdle(RTC_WAKEUP)`；只有使用者開啟「排程可靠性」中的精確鬧鐘偏好，且 API 31+ `canScheduleExactAlarms()` 仍允許時，真正課程邊界才用 `setExactAndAllowWhileIdle()`。API 29–30 不需特殊存取；授權撤銷或註冊時遭系統拒絕，保留 App 偏好並回退一般排程。Recovery 永遠使用一般非精確 Alarm。精確鬧鐘可能增加耗電且不保證準時。忽略電池最佳化是獨立系統能力，以 `isIgnoringBatteryOptimizations()` 回讀，不能使一般 Alarm 變精確。頁面回到前景及授權狀態廣播後重新評估並 re-arm。授權變更 Receiver 使用八秒 timeout 與 finally finish，逾時沿用 Widget recovery 及最多一秒的通知 recovery；協調器僅在通知及 Widget 都完成 re-arm 後記錄已處理狀態；暫時失敗、例外或取消均保留後續事件重試能力。通知和 Widget 各保留唯一 boundary Alarm owner；不加 FGS 或週期 polling。Android 14+ 收到滑除 deleteIntent 時依當下狀態恢復；展開通知的「暫停一日」以 generation 保存暫停至 Asia/Taipei 次日零時，保留 enabled=true，撤下通知並以同一 boundary Alarm 排定隔日恢復；永久停用須在 App 設定頁關閉開關。一次性 schedule capability 每次發布須替換舊 token，消耗後刷新 ongoing 通知。實作邊界及手動驗證見 [當前課程通知](../development/current-course-notification.md)。檢查 `CourseResolutionTest`、`GradeCacheStoreTest`、`CourseNotificationCoordinatorTest` 及 `ArchitectureBoundaryTest`。
- 「排程可靠性」中的電池豁免只在使用者主動點擊時呼叫 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；若已豁免，提供系統設定入口讓使用者撤回。Android 官方／Google Play 對直接豁免請求有核心功能限制，發布前須按實際上架功能審查。

- 個人頁「排程暫停時段」沿用 `app_settings` 保存共用每日區間，預設關閉、23:00–07:00，依裝置當地時間且開始包含／結束不包含，禁止相同起訖時間。成績與公告 Worker 完成必要清理、期限／權限檢查後，於連網前讀取設定；時段內正常結束，不更新檢查時間、失敗次數、快照、比對基準或未讀。讀取失敗重試；時段結束後由下一次原定工作恢復，不補排喚醒。已開始的查詢允許完成，成績提醒 48 小時期限不延長。前景載入、手動更新、課程通知、Widget 及 FCM 不受影響；課表目前沒有定期連網 Worker。檢查 `BackgroundPauseTest`、`SettingsRepositoryTest`、`SettingsIoRecoveryTest` 與 `ArchitectureBoundaryTest`。

- 段考更新提醒 `grade_reminder_poll` 為 WorkManager 15 分鐘週期，開始前需通知權限與忽略電池最佳化許可；仍不保證即時。只能讀提醒專用 Session，最長 48 小時，不 fallback 到一般 Session。
- 成績及公告提醒在連網前與更新比對基準前檢查 App、所屬 notification channel 與 group 能力。channel 關閉時略過查詢、保留 enabled 與基準；成績全域通知關閉亦暫停，48 小時期限與必要清理仍生效。公告全域權限撤銷沿用原有停用／取消政策。系統重新允許後由下一次原排程恢復，無法送達期間不補排工作。設定 UI 以文字揭露暫停並提供 channel 設定入口，保留停止操作。
- 登入失效停止；逾時、408／429／5xx 重試，其他錯誤才累計連續失敗。停止／過期／登出／切換學生時取消工作並清除 Session、snapshot、change set。
- `GradeReportDiffer` 比對可見資訊及區塊新增／移除，不比 rawResult、HTTP 格式或學生資料時間戳。啟動可補排未過期工作，不因每次 state 更新重排。
- 公告更新提醒權威讀取必須傳遞 I/O 失敗給 Worker retry；ViewModel 保留前值／排程並標示 unavailable，每五秒可取消地重新訂閱。驗證 `AnnouncementReminderRepositoryTest` 與 `SettingsIoRecoveryTest`。
- 公告更新提醒 `school_announcement_poll` 與 FCM、段考更新提醒獨立；不要求忽略電池最佳化。多單位依序查詢並按 ID 去重。
- 首次啟用／更換單位讀最新三頁建立不通知的基準；已有 known ID 時查到已知項目或最多 20 頁。啟用基準提交時須在同一 DataStore 交易內比對查詢時的接收單位；單位已變更則拒絕舊結果、保留目前設定並回報失敗，讓使用者重新啟用。使用 v2 基準及未讀 keys，不沿用舊篩選結果。
- 一筆新公告直達詳情，多筆到列表；以一次性 capability 發出通知 action。停用或權限撤銷只清通知比對基準，保留單位／頻率偏好及 Room 閱讀紀錄。更換提醒單位重建通知基準，不清閱讀紀錄。
- 公告閱讀狀態由 `AnnouncementReadRepository.kt` 的單表 Room 管理，僅 `announcementKey`（API `newsId`）與 `isRead`。無閱讀紀錄且發布日期早於固定 cutoff 時視為已讀、不插入；等於／晚於 cutoff 或無法解析日期時首次發現插入未讀。cutoff 為首次導入時的 Asia/Taipei 日期，放在原提醒 DataStore；列表日期及詳情時間一律比較日期，不以當天時分秒推測新舊。先以 insert-ignore 匯入 `unread_ids_v2`，Room 成功後才保存遷移完成並移除舊 unread key；失敗保留 cutoff／舊未讀供重試，提醒狀態修改須先完成遷移。通知 `known_ids_v2` 不由閱讀操作修改。
- 列表快取、搜尋、分頁、首頁第一頁與 Worker 查詢沿用既有資料取得流程，只登記已取得公告；不抓全庫、不另建公告 cache。列表／首頁／入口 Badge 共用 Room 未讀集合。詳情成功載入或合法外部連結 Intent 成功啟動後才寫入已讀；失敗保留未讀並提示。全部已讀僅更新操作開始時的 key 快照，分批更新置於同一 Room transaction，之後發現的新公告維持未讀。Worker 閱讀登記失敗須 retry，不能先推進通知 baseline。檢查 `AnnouncementReadRepositoryTest`（JVM policy／遷移協作，使用記憶體 DAO，不代表真實裝置 SQLite 驗收）、`AnnouncementReminderRepositoryTest` 與 `SchoolAnnouncementsTest`。
- `MainActivity` 不信任裸 Intent extras；保留 `NotificationActionCapabilities` 與鎖。系統通知權限在 App 根層同步，僅使用者操作時 request；永久拒絕走系統設定。
- FCM 推播使用 `general`／`app_updates`，跟隨開關訂閱；不承載私人 Session 或成績。段考 channel 保留 high importance，既有安裝仍受系統 channel 設定限制。
- 執行資訊頁列出 App 所有 WorkManager 工作，只讀、不提供取消。Debug reminder receiver／Widget capture activity 保留 `android.permission.DUMP`；不得搬至 release。

檢查：`GradeReportDifferTest`、`GradeReminderWorkerTest`、`AnnouncementReminderRepositoryTest`、`NotificationPermissionArchitectureTest`、`ArchitectureBoundaryTest`。

## 課表、科目偏好與 Widget

入口：`viewmodel/ScheduleViewModel.kt`、`data/` 的 Schedule models／repository、`widget/`。

- Grid 保留 1–8 節，API 額外正節次用稀疏清單追加，不建立巨大連續範圍。
- 更新失敗仍保留既有課表；首頁由 `OverviewState.scheduleFailed` 顯示失敗說明與重試入口，登入世代變更清除此狀態。課表頁以獨立 `refreshFailed` 保留警告，不隨 Snackbar 消失；強制重新載入按鈕沿用過期狀態的 errorContainer 底色與實心 refresh 圖示，重試中停用並顯示 LoadingIndicator。
- 週次依本地日期落入 StartDateDisplay／EndDateDisplay 唯一範圍決定，不依 Selected。過期判定以整週實際最後一節下課為準，無法辨識節次以 16:55 備援；後續目標為 weekStartDate 加一週，不用 weekEndDate 判斷。讀取過期快取時自動重載；首次查詢若收到已結束的週課表，立即追加一次下一週查詢，追加失敗保留該目標供重試。頁面停留跨過期時間只更新顯示狀態，不直接呼叫重載；過期時更新入口使用醒目底色、較大實心圖示及過期說明，沿用原有手動 refresh；重載中顯示 LoadingIndicator 並停用重複點擊。
- 週次不唯一或查詢失敗回學期課表並提示。週課表成功後取得學期課表比對科目／教師／教室；兩份結果分別存 current-week／semester cache，不能互覆，也不保存未使用 rawData。
- 可辨識且合法的空週課表仍保存 CURRENT_WEEK 權威性；未知 envelope、無效 slot 或非 JSON 不當成無課週。課表請求、行事曆篩選與畫面日期使用 Asia/Taipei，測試保留可注入日期來源。
- 科目自訂依學生及原始科目名稱保存，僅在顯示前共用套用；cache 的 items／changes 維持原始資料。登入／登出／失效清理用 `preserveSubjectOverrides = true`；明確清學生或全部資料才完整刪除，儲存失敗必須傳遞。
- Widget 只讀專用快照及 authority metadata；課表成功與舊 cache 載入都同步快照。目標日期落在有效本週範圍內時，快照即使沒有課也不得被學期覆蓋；範圍結束後可由 App 提供學期 fallback。顯示日期不得早於快照 weekStartDate，不自行查 API 或比對調課。
- 更新預設沿用 `setAndAllowWhileIdle(RTC_WAKEUP)`，選用精確鬧鐘時才對 `Asia/Taipei` 課程開始／結束與午夜日期 boundary 使用 `setExactAndAllowWhileIdle()`；Widget 同步或 Alarm 註冊失敗須傳遞至呼叫端；Receiver 初次失敗或逾時時，以同一 PendingIntent 排一次五分鐘後的非精確 recovery Alarm；recovery 再失敗不連鎖重試。同步 finally 在取消後仍以最多一秒嘗試補排正常邊界；正常邊界重設 recovery 標記，持續儲存／AlarmManager 故障時由下一次前景或系統事件恢復。上課時間含開始、不含結束；邊界更新延遲五秒。全體同步入口負責每次同步至多排一次下一個 Alarm；`provideGlance()` 只讀已準備狀態並 render。Widget receiver 在有實例時處理開機、套件更新、系統時間及時區變更後重新同步及排程；沒有實例就取消。午夜 Alarm 已處理日期 rollover，不另註冊 `DATE_CHANGED`，避免午夜重複同步。移除最後一個 Widget 必須取消 Alarm。系統 exact access 與電池豁免狀態不可只信任本機偏好；App resume／system event 重新讀取並 re-arm。
- 精確鬧鐘的 App 偏好與 Android 特殊存取分開保存；偏好開啟但尚未授權時仍使用一般鬧鐘，授權或撤銷後依實際狀態重新排程，保留使用者的偏好選擇。
- 個別 GlanceId 保存教師／地點／時間／下課切日偏好；配置先寫入該 Glance state，再由全體 sync 讀取並刷新。全體 sync 只同步快照與 theme，不能覆蓋個別偏好。舊全域偏好只補缺值。

檢查：`ScheduleViewModelTest`、`ScheduleGridTest`、`ScheduleSubjectOverrideTest`、`ScheduleWidgetDaySwitchTest`、`ArchitectureBoundaryTest`。尺寸、預覽與 RemoteViews 規則見 DESIGN.md。

## 成績模擬器學分

入口：`ui/GradesScreen.kt` 右上角更多選單的「學分設定」、`ui/SimulatorCreditPanel.kt` 的 Bottom Sheet、`viewmodel/SimulatorCreditStateOwner.kt`、`data/SimulatorCredits.kt`；`ui/AdvancedComponents.kt` 的模擬器只讀取已套用學分，不提供學分編輯。

- 官方總分為加權總分。模擬使用 `Σ(科目成績 × 合計學分)`，平均除以採計學分總和；使用者設定及推算候選不回寫 `GradeReport`。同一份考試成績對應多門課時，只保存合計學分，不推測個別課程配置。
- 成績頁摘要與模擬器入口共用 `data/GradeSummaryDisplay.kt`。官方總分、平均各自保留有效的 API 原文，包含真正的 0；空白、`--` 或非數字視為尚未公布，只有缺少的欄位才用目前範圍的 `appliedCredits` 試算並附 `*`。試算總分僅移除尾端小數 0，平均以 HALF_UP 顯示兩位小數，並顯示「* 非官方試算」。不改寫官方報表、分析、歷史、匯出、首頁或 Widget。官方平均不可用時隱藏 Hero 與近期變化的本次平均比較。
- 滾動公布期間只以有效、無缺考／作弊註記的已公布分數及其學分計算分母，不能把缺漏或無效分數當成 0。整份已套用學分必須完整對應清理後的完整科目名稱，且全部為 1～4；沒有有效分數、缺學分或初次載入失敗時顯示 `--`，不標星號。同範圍重載失敗可保留既有有效 map 試算；換帳號、學期、考試或科目簽章不可沿用。
- 成績更多選單與「學分設定」項目顯示紅點，無障礙狀態為「學分待確認」。只有已套用狀態可靠載入或成功儲存，且同時符合已公布的官方總分與平均，才能清除紅點。開啟選單／設定、編輯、選候選、恢復預設及僅按儲存不會獨立清除；缺少官方摘要或數值不符時持續提醒。草稿不影響摘要或紅點，可靠性與供編輯用的 `isLoaded` 分開記錄。
- 未設定時先用國文、數學、英文各 4 學分，其餘各 2 學分；符合官方總分與平均時沿用，不符才推算調整。這是可調整的預設，不是固定限制。手動設定及推算支援每科 1～4 的整數，排除科目由採計選擇處理，最多 64 科。依清理後的完整科目名稱對應，不能縮寫合併；空白或重複名稱拒絕推算及保存。歷史分數提示亦僅比對完整名稱。
- `SimulatorCredits` 以十進位計算。官方顯示值依其小數位視為正數 HALF_UP 的半單位區間，同時比對加權總分與平均；這是顯示精度的容差，不保證涵蓋校方其他截斷規則。原始科目分數須有效且無缺考／作弊註記；不能透過 0 學分略過無效分數。
- 推算在背景執行，最多搜尋 50,000 節點、保留 32 組候選，達上限必須標示搜尋未完成，不能宣稱唯一或無解。完整搜尋僅一組時可先填入設定草稿，但仍標示未確認；多解由使用者選取或固定已知學分縮小範圍。候選吻合官方數值不代表校方確認了各科學分，也不將國文或數學無條件固定為 4。
- 已找到的候選先排全科皆為偶數學分的方案，再排含奇數學分的方案；各組內依國、英、數為 4 學分的科數由多到少排序，同順位保留搜尋順序。排序不排除有效解；搜尋未完成時不保證已找到全域最優先的方案。
- 合計學分統一用於模擬總分、平均、自訂採計組合與目標反推。反推以十進位運算並向上取至小數第 2 位，使可達目標不因四捨五入而落空；超過 100 或沒有未鎖定的採計學分時提示原因。試算摘要顯示平均至小數第 2 位，與官方原文顯示分開。
- `ScoreViewModel` 持有學分 state owner；設定透過 `GradeRepository → GradeCacheStore` 的獨立 key 保存，僅明確確認才寫入。帳號、學期、考試隔離，保存完整科目簽章；科目改變不套用舊設定，同考試分數更新則保留確認的學分並重新比對官方值。不自動跨考試沿用，亦不納入設定 JSON 備份。
- owner 分開保存草稿輸入、已提交快照與 `appliedCredits`。手動編輯、選候選及「恢復預設」只改草稿；按「儲存」且持久化成功後才更新模擬器並關閉 sheet。關閉未儲存 sheet 會取消推算並還原已保存學分，尚未保存則還原預設；不刪除既有設定。儲存失敗保留草稿與原有效學分供重試，儲存中禁止使用者關閉。讀取失敗禁止保存覆蓋未知設定，已有的有效 map 保留，不能把讀取失敗當成沒有設定。
- 已確認的解碼／驗證錯誤可按「恢復預設」建立恢復草稿，仍須明確儲存才能取代損壞設定；取消回到恢復前的錯誤與有效 map。I/O 讀取失敗只允許重新載入，不能以恢復預設繞過保存保護。
- sheet 開啟狀態及草稿由 owner 持有，配置重建保留；離開成績頁會放棄草稿，帳號、學期、考試、科目或成績內容變更會關閉並重新準備目前範圍。不要用舊 route 的 dispose callback 清掉新範圍狀態。
- 讀取 I/O／解碼失敗須與沒有設定區分；儲存與刪除錯誤必須回報。Session generation 檢查涵蓋交易提交前；登出取消並等待 owner 工作，再沿用私人快取清理刪除學分設定，不能因課表自訂科目可保留而保留學分表。

檢查：`GradeSummaryDisplayTest`、`SimulatorCreditsTest`、`SimulatorCreditStorageTest`、`SimulatorCreditStateOwnerTest`、`GradeAnalysisTest`、`ScoreSimulatorAdaptiveLayoutTest`；這些 JVM 檢查不代表裝置 UI 驗收。

## 設定匯入／匯出

入口：個人頁「開發者選項」→「備份與還原」。主要操作是儲存目前配置與從檔案還原；備份範圍另以說明視窗呈現，JSON 編輯／複製收合於進階區域。檔案讀入後直接呈現還原確認，尚未確認不寫入。資料由 SettingsTransferRepository 經既有 SettingsRepository、OverviewLayoutRepository、AnnouncementReminderRepository、CourseNotificationPreferences 及 ScheduleRepository 讀寫；Composable 只呈現 SettingsTransferViewModel 狀態。

- JSON 備份格式 1 必須包含 app 與 version；保留首頁元件身份、原排列版本、停用項目、不透明的新元件設定與各實例內容。空首頁是有效配置。設定白名單包括外觀、啟動、頭像形狀、推播偏好、精確鬧鐘偏好、背景暫停、天氣來源及開發者偏好；公告備份包括開關、間隔與單位，另含課前提醒偏好。
- 不匯出登入 Session、學號、PIN／生物識別、API key、onboarding、成績／課表快取、閱讀狀態、提醒 baseline／generation／當日暫停、目前課程／成績提醒啟用狀態或各 GlanceId 配置。自訂科目文字可能包含個人資訊，檔案與剪貼簿由使用者自行保管。
- subjects 為 null 表示未包含自訂科目；空陣列表示取代為無自訂科目。匯入確認提供是否套用科目的選項，僅寫入目前解鎖帳號；以發起時的完整 Session 及 DataStore 提交前的授權守衛阻止跨帳號寫入，備份內沒有學號可指定其他帳號。
- 檔案透過 Android Storage Access Framework 選取；讀取、JSON 與文字輸入限制 1 MiB，解析前限制巢狀深度，驗證版本、未知欄位、元件數／身份／尺寸／設定及科目長度。讀入檔案、貼上或編輯只修改草稿；從檔案還原或按「套用 JSON」並確認才取代設定。首頁編輯草稿尚未結束時不開啟備份頁。
- 各儲存來源保留自己的 transaction，整體匯入不是跨 Room／DataStore 的 ACID transaction。一般失敗或協程取消按相反順序補償；補償失敗明確提示部分設定可能已變更，程序被終止／裝置斷電則可能留下部分結果。匯入後重新讀取首頁並清除舊視覺順序標記，既有通知／排程 owner 同步設定；通知授權與 CWA key 仍由裝置現況決定。
- 公告單位或啟用狀態改變時重建 baseline，不覆蓋獨立 Room 閱讀狀態；未變更的範圍保留 baseline。課程通知的 generation 使用本機遞增，備份不還原舊通知 capability。

檢查：SettingsTransferTest 的純 JVM 格式、邊界、帳號隔離及失敗補償測試；不代表系統檔案挑選器、剪貼簿或裝置 UI 驗收。

## 發布、套件與整合

- `demo` flavor 固定假資料、`.demo` applicationId suffix，不初始化 Firebase、訂閱 FCM 或開啟校務登入 WebView；`production` 固定真實資料與既有 Firebase client。`debug`／`release` 只決定建置型態，正式發布僅使用 `productionRelease`，`demoRelease` 不得使用正式 release key。
- 更新檢查由 MainActivity 對共用 SettingsViewModel 觸發一次；自動檢查間隔 12 小時，手動不受限。保存 stable release 資訊，已安裝相同或更新版本清掉 cache。
- APK 下載保留 HTTPS、大小、SHA-256、取消與 atomic replacement 驗證，再交 FileProvider／系統安裝器；不靜默安裝。檢查 `UpdateApkDownloaderTest` 與 `SettingsViewModelUpdateCheckTest`。
- 啟動 SettingsViewModel 時，在還原更新資訊前檢查 `cache/updates/clhs-score-update.apk`：只有可解析、相同 package 且 versionCode 不高於已安裝版才刪除。新下載等待這次檢查完成；較新版、其他套件、解析或刪除失敗都保留供重試，不清除使用者資料。
- Release 保留 R8 與 resource shrinking，不新增 broad keep。protobuf-lite message／欄位名與 biweekly 資源相對路徑、反射 constructor／欄位需保留；修改規則需檢查 release DEX／mapping，biweekly 用完整公開 ICS 驗證，不能只靠 debug 或簡化 ICS。
- 保留 default／zh-TW resources、停用 App Bundle 語言分割、不封裝 `.proto`、維持未壓縮 DEX。Runtime 開源依賴異動同步授權清單與 `OpenSourceLicensesTest`。
- Analytics 只經 `AnalyticsLogger`、常數與 sanitizer 白名單；不設 user ID，不送學生資料、成績內容、URL、憑證或錯誤原文。參數僅 enum、boolean、count、bucket；更新 `AnalyticsParameterSanitizerTest`。
- Remote Config 的 `feedback_form_url` 只接受 `https://forms.gle` 或 `https://docs.google.com/forms/`，不得放秘密。
- 成績 CSV 由 `ScoreViewModel.exportGrades()`／`GradeExporter` 產生 BOM + UTF-8 並經 MediaStore 存 Downloads；保留跨學期選擇、未快取考試查詢與既有併發限制。
