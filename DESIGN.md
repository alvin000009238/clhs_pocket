# CLHS Pocket 設計規範

本文件描述目前 Android 實作，供後續畫面與元件復用。以 [ScoreTheme.kt](android/app/src/main/java/com/clhs/score/ui/theme/ScoreTheme.kt) 和各元件原始碼為準；這不是網站 CSS token 或未實作的設計提案。

## 設計方向與資訊架構

CLHS Pocket 是繁體中文校園工具，讓學生快速掌握課表、成績與校園資訊。介面使用 Material 3 Expressive，以清楚的主次、tonal surface 與短文案組織內容。摘要可以有 Hero，資訊列則依內容分組，避免每筆資料都做成大型卡片。

優先用操作元件、狀態與即時結果表達流程，避免常駐的教學段落及重複通知；必要限制與錯誤提示只在相關操作時呈現。

- App 頂層依序為「首頁、課表、成績、校園」。手機直向使用 icon-only Floating Navigation Toolbar；寬度達 600dp 或可用高度不足 480dp 使用 Navigation Rail，個人入口在頂層頁的右上角。
- 四個目的地保留各自 back stack；頂層導航僅在首頁、課表、成績、校園 root 顯示，重按目前目的地回到捲動頂端。登入、設定與其他子頁使用同一個 Navigation 3 `NavDisplay`，隱藏頂層導航並取消 overlay clearance；手機 IME 顯示時也隱藏 floating toolbar。
- Guest 可以使用公開內容與設定。私人內容以 `AuthGate` 顯示登入入口；登入成功回到原目的地，不切換另一套 App shell。
- Welcome、外觀、權限、帳號是首次設定頁，期間隱藏頂層導覽。登入是 WebView route，不是通用 Modal。

來源：[AppNavigation.kt](android/app/src/main/java/com/clhs/score/ui/navigation/AppNavigation.kt)、[AuthenticatedApp.kt](android/app/src/main/java/com/clhs/score/ui/AuthenticatedApp.kt)、[AuthGate.kt](android/app/src/main/java/com/clhs/score/ui/AuthGate.kt)。

## Theme 與色彩

根主題為 `MaterialExpressiveTheme`，預設 `MotionScheme.expressive()`。支援跟隨系統、淺色、深色；Android 12 以上可選動態色彩，深色可再套用 AMOLED 黑底。元件應讀 `MaterialTheme.colorScheme`，不要把下表色碼複製到畫面。

以下是未啟用動態色彩的實際基礎值：

| Role | Light | Dark |
| --- | --- | --- |
| primary / onPrimary | `#36618E` / `#FFFFFF` | `#A0CAFD` / `#003258` |
| primaryContainer / onPrimaryContainer | `#D1E4FF` / `#194975` | `#194975` / `#D1E4FF` |
| secondary | `#535F70` | `#BBC7DB` |
| tertiary | `#6B5778` | `#D6BEE4` |
| background / surface | `#F8F9FF` | `#111418` |
| surfaceContainerLow | `#F2F3FA` | `#191C20` |
| surfaceContainer | `#ECEEF4` | `#1D2024` |
| surfaceContainerHigh | `#E6E8EE` | `#272A2F` |
| onSurface / onSurfaceVariant | `#191C20` / `#43474E` | `#E1E2E8` / `#C3C7CF` |
| outlineVariant | `#C3C7CF` | `#43474E` |

AMOLED 將 background、surface、surfaceDim、surfaceContainerLowest 改為黑色，Low／Container／High／Highest 分別為 `#050505`／`#0A0A0A`／`#141414`／`#1E1E1E`；動態色彩的深色模式也套用這組表面覆寫。

成績語意使用 `ScoreTheme.semanticColors`，與動態色彩分開：

| 語意 | Light | Dark |
| --- | --- | --- |
| positive | `#126738` | `#81C995` |
| negative | `#BA1A1A` | `#FFB4AB` |
| warning | `#946C00` | `#FDE293` |
| neutral | `#6B7280` | `#9CA3AF` |

以 primary 表達選取與主要操作；以 surface container 層級區分群組。成績、調課與錯誤必須保留文字或數字語意，不只依賴顏色。課表差異沿用 tertiary container；科目色沿用既有課表配色與自訂偏好。

首頁天氣、成績摘要與校園公告主卡使用 `ui/theme/PocketMeshBackground.kt` 的靜態 `MeshGradientPainter`。沿用各卡片的 Material 主題底色，以低強度 primary／tertiary container 混色；Painter 隨色彩更新並快取，不加入常駐動畫，也不改變元件量測尺寸。密集資料列保留純色表面。

## 字型、尺寸與形狀

- 文字使用 `MaterialTheme.typography` 的 display／headline／title／body／label roles；根主題套用 `PocketTypography`。display、headline、titleLarge（含 emphasized）使用封裝的 Huninn 粉圓，建立親切圓潤的頁名、區塊標題與主要數值；titleMedium／Small、body、label 維持 Material 預設，讓公告、課表與設定容易掃讀。沿用官方字級與行高，中文標題字距為 0sp，不壓縮字體縮放。
- Huninn 使用官方 Google Fonts 的 Regular 字型與完整字符集，OFL 授權隨 App 封裝並列於開放原始碼授權頁。只有一種原生字重，停用合成粗體；用字級、字體與內文 emphasized 字重建立對比，避免假粗體破壞圓潤筆畫。罕見缺字由 Android fallback 處理。
- `CLHS Pocket`／`Pocket` 品牌字樣使用 `OutfitFontFamily`，來自 Outfit Bold 700 subset，只包含品牌名稱需要的字元。不可拿它排任意文案。
- 首頁 Logo 使用由 `docs/public/logo.svg` 原始 path 與 viewBox 轉換的 `clhs_pocket_logo.xml`，直接按顯示尺寸繪製；不要放大帶有安全區留白的 launcher foreground。
- 分數摘要等對齊數字沿用 `fontFeatureSettings = "tnum"`；這是個別樣式，不是全 App 預設。
- Android 間距與形狀使用 dp，字級使用 sp。常見內距為 12、16、20、24dp；依現有元件調整，不建立另一套 px/rem token。

| `MaterialTheme.shapes` | 圓角 |
| --- | --- |
| extraSmall | 8dp |
| small | 12dp |
| medium | 16dp |
| large | 24dp |
| extraLarge | 28dp |
| largeIncreased | 32dp |

一般容器讀取 shape role。圖表、課表格與 Glance Widget 可保留資料幾何需要的小圓角。按鈕和切換元件沿用 Material 3 `shapes()`／`toggleableShapes()`，互動區至少 48dp。首頁的編輯、個人、完成與新增元件入口為例外，使用固定形狀，按下時保留 ripple 與既有觸覺回饋，不做形狀變換。

數字 badge 共用 `ui/UiUtils.kt` 的 `CountBadge`，以內容所需寬高的較大值作為圓形直徑；多位數與放大字體仍維持正圓。使用 error／onError 語意色，沿用各入口的計數與無障礙描述。

圖示使用 [SymbolWrapper.kt](android/app/src/main/java/com/clhs/score/ui/SymbolWrapper.kt) 的 Rounded Material Symbols subset。新增 ligature 時更新並執行 [generate_material_symbol_subset.py](android/scripts/generate_material_symbol_subset.py)，同步產出 outline／filled 字型。

## 畫面與可復用元件

以下來源均位於 `android/app/src/main/java/com/clhs/score/`。

| 情境 | 現行組成與復用入口 |
| --- | --- |
| 頂層與子頁工具列 | `ui/RootFloatingScaffold.kt` 的頂層浮動膠囊、`ui/UiUtils.kt` 的 `AccountIconButton`；傳統 app bar 保留給其他情境，子頁先看 `ui/SubpageLayout.kt` |
| 首頁 | `ui/overview/OverviewDashboard.kt`、`OverviewWidgetGrid.kt`、`OverviewWidgetContent.kt`、`OverviewScreen.kt`：既有多實例 grid 加各尺寸內容，消費 `OverviewState` 與 editor state |
| 首頁寬版 | 沿用既有 grid packing 與 widget 尺寸，課表摘要整區點擊導向課表，不補冗餘 CTA 箭頭 |
| 成績 | `ui/GradesScreen.kt`：摘要／科目／分析的浮動 PrimaryTabRow 位於底部導覽上方，學期／考試 split button 由右上角「選擇成績」按鈕展開；保留 pager 滑動、各頁捲動位置及下拉更新 |
| 成績寬版 | 摘要、科目、分析皆維持單欄上下排列，內容置中且上限為 1200dp；成績模擬器與折線圖子頁保留既有寬版配置 |
| 成績子內容與圖表 | `ui/GradesSubjectsTab.kt`、`ui/GradesAnalysisTab.kt`、`ui/Charts.kt`、`ui/SubjectTrendLineChart.kt`；沿用資料、圖例、語意色與標籤 |
| 個人與設定 | `ui/PersonalScreen.kt`：同一個 LazyColumn，最大寬度 720dp；Profile 加 SettingsSection／SettingsGroup／SettingsRow／SettingsSwitchRow。整列觸控回饋與右側值保留；更新狀態放在箭頭左側 |
| 校園 | `ui/HubScreens.kt`：公告、行事曆、欣河智慧校園平台入口；不恢復舊漢堡選單或重複的設定／關於入口 |
| 校園搜尋 | `ui/CampusSearchScreen.kt`：緊湊工具列搭配 M3 填色搜尋列；空白狀態提供快速搜尋，分類 Chips 在同頁切換全部／行事曆／學校公告。行事曆使用日期方塊，公告以日期＋單位為次要資訊；相符文字加粗，列表重排沿用 PocketMotion。各來源獨立載入與重試，公告沿用自動分頁與未讀／置頂排序 |
| 公告與行事曆 | `ui/announcements/SchoolAnnouncementsScreen.kt`、`ui/calendar/SchoolCalendarScreen.kt`：下拉更新；公告列表接近尾端自動載入下一頁，不提供「載入更多」按鈕；不加來源摘要卡、獨立更新按鈕或更新時間 |
| 課表與自訂 | `ui/schedule/ScheduleScreen.kt`、`ScheduleCustomizationsScreen.kt`：沿用 grid、科目色、調課語意及個人偏好 |
| 當前課程通知 | `ui/schedule/CourseNotificationSettingsScreen.kt`：從課表更多選單進入，在頁面啟用並查看功能說明、通知權限與排程可靠性入口 |
| 排程暫停時段 | `ui/BackgroundPauseSettingsScreen.kt`：個人頁「通知與提醒」入口，Guest 可用；共用每日開關與 Material 時間輸入對話框，支援跨午夜。開關切換或確認時間後立即儲存；時間列使用低強調色。起訖相同不能確認；儲存失敗保留選擇供重試 |
| 備份與還原 | `ui/SettingsTransferScreen.kt`：從開發者選項進入，主要畫面提供儲存備份／從檔案還原；JSON 編輯與複製收合於進階列。檔案讀入後直接顯示取代確認，科目是否套用至目前帳號仍可選擇 |
| 排程可靠性 | `ui/ScheduleReliabilitySettingsScreen.kt`：正常時只顯示簡短狀態與設定群組，不保留完成進度；待授權時才突出系統設定列；舊版 Android 省略授權列。電池最佳化收合於選用進階項目，不影響完成狀態；開啟設定失敗時顯示手動路徑，額外說明放入 Bottom Sheet |

公告依未讀、置頂、一般排序，列表及首頁個別公告顯示紅色「未讀」標籤；成功載入詳情，或外部連結 Intent 成功啟動後，才寫入已讀。閱讀狀態由 Room 保存，與公告提醒開關及通知基準獨立；校園入口及導覽 Badge 共用 Room 未讀集合。公告工具列的「全部已讀」只更新點擊時已知未讀的 key 快照，處理期間新發現公告不受影響；無未讀、閱讀狀態不可用或處理中時停用。儲存失敗保留狀態並提示，讀取失敗不視為全部已讀。更新可用時，帳號入口顯示低干擾 Badge；自動檢查不彈窗、不跳頁。

公告搜尋框最右側的篩選按鈕開啟 Bottom Sheet，可各選一個單位與類別；按「套用」才更新列表，關閉不改條件。「重設」將草稿恢復為全部，仍須套用。有篩選時按鈕顯示 Badge，搜尋框下方列出條件；清除關鍵字保留篩選。選項沿用官網首頁及既有單位快取，更新失敗顯示重試入口。

## 狀態、互動與動效

- 首頁以 logo＋Pocket 品牌列與大型日期時間卡片起始，捲動後由左上角日期時間膠囊接替。課表、成績、校園不顯示頁名，以相同的 PocketBrand 品牌列起始，隨頁面捲動離開；右側個人及頁面操作共用有陰影的浮動容器。起始安全間距放在捲動內容內，viewport 可到達狀態列；狀態列以背景漸層維持可讀性。App 週課表不顯示週次；週次保留在桌面課表 Widget。右側膠囊中的按鈕不使用按壓形狀動畫。

- 觸覺回饋使用 `ui/InteractionHaptics.kt` 與 Compose `LocalHapticFeedback`，沿用系統觸覺設定。主要導覽、分頁／課表範圍選擇、個人與設定入口、手動更新使用輕量 tick；開關使用開／關回饋；首頁排序在開始拖曳與跨越位置時回饋。只在使用者操作 callback 觸發，不對重新組合、背景更新或一般捲動震動；保留既有長按回饋，避免重複觸發。

- 大螢幕內容置中：首頁、成績、課表與共用 `SubpageLayout` 子頁上限為 1200dp；個人、校園卡片與 Widget 設定上限為 720dp；首次設定維持 520dp。Top App Bar 維持全寬，手機浮動導航有獨立寬度上限。使用 `widthIn` 時須先於 `fillMaxWidth`，已填滿父層的容器先用 `wrapContentWidth` 放寬水平限制。

- `AuthGate` 在還原時先呈現中性 placeholder，約 300ms 後才出現 loading；公開資訊維持可用。沒有 session 才顯示登入提示。
- 首頁右上角 `dashboard_customize` 進入編輯模式；新增元件位於編輯模式的 `HorizontalFloatingToolbar`。使用既有 widget grid 調整排列、尺寸與各實例設定；按完成才提交 Room，取消有變更時提供儲存／放棄／繼續編輯。重設與復原只改草稿，儲存失敗保留草稿。多個天氣、YouBike、倒數元件維持獨立身份與設定。
- 持續拖曳與右下角縮放的手勢由 grid viewport 持有，不能放在換位／改尺寸後可能重建的 lazy item 上；一次按住可跨多個位置或來回調整尺寸。保留尺寸選單與無障礙 action。首頁編輯期間保留主導覽及其 clearance，手機與平板維持相同可用寬度。
- Grid 的列高只保留排列空間，各元件維持自己的自然高度或所選固定高度；小元件不可被同列長內容拉高。拖曳預覽固定為抓取時的寬高，座標只在 placement 階段讀取，不因每次手指移動重新組合整個首頁。
- 元件按閱讀順序往後排列，後面的元件不回填前面的空洞。縮放只推移其後的元件，不把原本相鄰的元件移到其他區段之後。舊版回填排版先按當前可見順序轉換，完成才儲存新版順序標記；未儲存草稿與已提交配置分別轉換，取消仍還原已提交配置。
- 拖曳以本次手勢開始時的落點幾何判定換位，保留穩定區間，避免重新排列反過來觸發換位與重複震動。小元件拖到列間自動保留一列，沒有「獨立一列」選項；後續元件不得回填到該列之前。第一指抓住元件時，第二指可捲動 viewport，浮動元件仍跟隨第一指；第二指操作期間停用邊緣自動捲動。
- 拉伸外框以連續像素尺寸跟隨手指；跨越合法尺寸門檻時立即更新草稿、內容與其他元件的位置，不等放開才變更。按完成才儲存排列。
- 新增或重新啟用元件後，等待 grid 量測新位置，再平順捲到元件；取消新增與修改既有設定不改變捲動位置。
- 天氣來源與授權碼只在首頁天氣元件設定中管理，個人頁不提供天氣設定。「中央氣象署」選項右側提供授權碼入口，直接開啟輸入對話框。沿用 SettingsViewModel 的驗證與安全儲存；輸入值只保留在對話框記憶體，不寫入元件草稿。驗證與儲存訊息直接在對話框顯示。
- 首頁視覺焦點是課程 Hero；天氣、YouBike、倒數以數值層級區分用途，短版優先主資訊，較高版本才展開明細或預報。`PocketVisual` 只共用 Hero 與數值容器兩個形狀，避免全頁同一種 Card。主數字使用 emphasized typography；品牌 Outfit subset 不包含數字，不用於數值內容。
- Grid 在實際寬度不足 320dp、或字體縮放後不足 280dp 時降為兩欄；一般 1.3 倍字體的寬版手機仍能並排半寬元件。每列的 40dp 固定空間不隨字體放大，72dp 內容預算才跟隨倍率；避免 padding 與文字一起放大造成多餘留白。
- 近期行程與重要公告的標題在容器外。內容使用官方 `SegmentedListItem` 與 `ListItemDefaults.segmentedShapes()`，列間留 4dp，群組首尾圓角較大、中間較小。日期膠囊固定在左欄，依文字 intrinsic width 保留空間，最小尺寸 52×48dp、內距水平 6dp／垂直 4dp；文字在膠囊內置中，日期、內容與箭頭相對卡片垂直置中。字體放大時保留分欄，文字自然換行，不放大全部 spacing。未讀標記與點擊路徑沿用既有 state。
- 倒數以剩餘時間與事件名稱為主，不加外部區塊標題；較高版本補充日期。近期行程的日期欄顯示起訖日期；全天事件的結束日期扣除 ICS DTEND 的排他邊界。公告日期放在左欄，小字僅顯示單位，不重複日期或時間。課表摘要以同一段文字呈現節次、教室與教師，不顯示鐘點區間；上課中另外呈現可取得的下一節課。
- YouBike 由使用者自行新增並選站；2×1 保留總量與一般／電輔數量，2×2 使用兩列數值呈現總量與車種。零值保持數字，未知數量不補零，停用顯示暫停營運；失敗保留上次數量，來源超過五分鐘標示可能過期。天氣與 YouBike 僅在點擊手動更新時於右上角顯示官方 `LoadingIndicator`，兩者使用相同點擊震動；進入頁面、背景與週期更新保持靜默。成功、失敗或離開頁面均結束手動指示器。較高元件利用可用高度分布資訊，避免底部集中留白。觸控範圍至少 48dp。
- 首頁來源可各自 loading、空白或失敗，天氣失敗不覆蓋整頁；不要為了填滿版面捏造資料。
- 首頁僅在尚無可用資料時顯示局部 Shimmer 骨架：天氣的溫度／狀態／預報、YouBike 的數值、初次讀取的課表摘要、等待學校行程的倒數，以及空列表載入中的行程／公告列。保留已知站名與區塊標題，骨架配合元件尺寸與列表設定；錯誤、無資料與需要設定時不用骨架。已有資料的手動或背景更新保留內容，不切回 Shimmer；手動更新仍使用右上角 LoadingIndicator。骨架沿用 LoadingShimmer 的時間模型、系統動畫倍率及主題色，只在骨架顯示期間運作，無障礙只讀一個載入描述。
- 錯誤使用固定、可理解的文案，不顯示 exception 原文。權限只在使用者主動操作時請求。
- [AppNavigationMotion.kt](android/app/src/main/java/com/clhs/score/ui/navigation/AppNavigationMotion.kt) 集中管理導覽轉場：頂層 entry 不做整頁淡入或縮放，品牌列與操作膠囊保持穩定；內容從第一個繪製幀完整可見，透過同一個 NavDisplay scope 輕微向上進場，不做透明度轉場，三頁保留各自的捲動位置；子頁進入位移為寬度 1/12、父頁退出為 1/24，加上 fade。語意 motion 由 `ui/theme/PocketMotion.kt` 取自 root `MotionScheme.expressive()`。
- Predictive Back 由 NavDisplay 管理，沿進入方向反向退出。不要在畫面再加一套返回攔截或固定毫秒動畫。
- 自訂 UI 使用 `PocketMotion` 語意 token：effects 處理不改變 geometry 的 alpha／顏色；spatial 處理位置、尺寸與展開。一般 UI 不在 feature code 自訂 tween／spring；既有課程詳情展開 timing 集中保留，倒數、進度、shimmer 等時間語意動畫維持 domain-specific。
- 自訂元件使用 App root 的 Expressive motion；Material 元件沿用同一 theme，不重複包裝動畫。動畫尊重 Android 系統 animation scale，不另設 App-level Reduce Motion 偏好。Loading shimmer 僅在 skeleton 顯示期間運作。
- 個人卡片的帳號形狀約 20 秒旋轉一圈，點擊形狀依序切換 Material 形狀並反轉方向；中央圖示保持正向。形狀由 SettingsRepository 寫入 `app_settings`，重開 App 仍保留，儲存失敗沿用原形狀並提示重試。索引 0–3 固定對應 Cookie9Sided、Clover4Leaf、Sunny、SoftBurst，旋轉方向依索引奇偶還原。此連續旋轉使用等速動畫，遵守系統 animation scale，頁面不在前景時暫停。
- 保留 icon 的無障礙描述、toggle 語意和數字上下文。獨立 Activity 使用 edge-to-edge；內容正確消化 safeDrawing／IME inset。WebView loading 遮罩不得蓋住可操作的工具列。

## Glance Widget

[ScheduleWidget.kt](android/app/src/main/java/com/clhs/score/widget/ScheduleWidget.kt) 使用 Glance 官方 Scaffold，無法直接套用 Compose MaterialExpressiveTheme；同步色彩與資訊層級即可。

- 高度小於 160dp：只顯示上課中或下一節；寬度小於 220dp：隱藏教師、地點與週次；高度至少 260dp 才加入已下課區塊。
- 新增預設 4×3，可縮至約 3×2。設定預覽沿用該 Widget 的尺寸、快照與偏好。
- RemoteViews 的每個條件分支都明確設定 background／cornerRadius，防止重用時殘留樣式。
- `schedule_widget_preview.png` 只能由真實 Glance RemoteViews 產生；需使用者明確要求裝置操作後，執行 `python android/scripts/generate_schedule_widget_preview.py`，不可手繪或用生成圖片替代。

## 復用與驗證

先找上表同類畫面，再使用現有 theme、state、元件與 [FakeData.kt](android/app/src/main/java/com/clhs/score/data/FakeData.kt)；預覽入口為 [ScorePreviews.kt](android/app/src/main/java/com/clhs/score/ui/ScorePreviews.kt)。不要新增平行 theme、導覽或硬編展示資料。

UI 修改依 [測試策略](docs/development/testing.md) 執行本機檢查；裝置截圖與互動驗證需明確授權。此文件是原始碼對照結果，不代表已做實機視覺驗收。

本次 Expressive 設計方向、官方研究及 Device Hub 驗證紀錄見 [視覺重構紀錄](docs/design/expressive-review.md)。

浮動主導覽使用單層靜態 `dropShadow`（8dp 半徑、2dp 垂直偏移、16% scrim），取代 Toolbar 的 elevation 陰影；形狀沿用 `FloatingToolbarDefaults.ContainerShape`，不改變量測與內容避讓。其他卡片不全面套用陰影；模糊與內陰影需有明確閱讀或互動用途才採用。
