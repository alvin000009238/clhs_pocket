# CLHS Pocket 設計規範

本文件整理 Android App 的視覺與互動規範，供新增或調整畫面時查閱。實作以 [ScoreTheme.kt](android/app/src/main/java/com/clhs/score/ui/theme/ScoreTheme.kt) 和各元件原始碼為準；本文件描述 App，不是網站 CSS token，也不取代功能規格。

## 設計原則

CLHS Pocket 是繁體中文校園工具，讓學生快速掌握課表、成績與校園資訊。介面使用 Material 3 Expressive，以清楚的主次、tonal surface 與短文案組織內容。摘要可以使用 Hero；密集資訊依內容分組，避免每筆資料都做成大型卡片。

優先用操作元件、狀態與即時結果表達流程。避免常駐教學段落與重複通知；限制和錯誤只在相關操作時呈現。使用者可以透過文字、數字或圖示辨認狀態，不以顏色作為唯一提示。

## 色彩與主題

根主題為 `MaterialExpressiveTheme`，預設使用 `MotionScheme.expressive()`。支援跟隨系統、淺色、深色；Android 12 以上可選動態色彩，深色模式可套用 AMOLED 黑底。元件讀取 `MaterialTheme.colorScheme`，不要在畫面複製色碼。

下表列出未啟用動態色彩時的基礎值：

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

AMOLED 模式將 background、surface、surfaceDim 與 surfaceContainerLowest 設為黑色；surfaceContainerLow、surfaceContainer、surfaceContainerHigh、surfaceContainerHighest 分別使用 `#050505`、`#0A0A0A`、`#141414`、`#1E1E1E`。深色動態色彩也套用這組表面覆寫。

成績的語意色由 `ScoreTheme.semanticColors` 提供，並與動態色彩分開：

| 語意 | Light | Dark |
| --- | --- | --- |
| positive | `#126738` | `#81C995` |
| negative | `#BA1A1A` | `#FFB4AB` |
| warning | `#946C00` | `#FDE293` |
| neutral | `#6B7280` | `#9CA3AF` |

以 primary 表達選取與主要操作，以 surface container 層級區分內容群組。課表差異使用 tertiary container；科目色沿用課表配色與使用者偏好。首頁天氣、成績摘要與校園公告主卡可使用 `ui/theme/PocketMeshBackground.kt` 的靜態 `MeshGradientPainter`；密集資料列使用純色表面。Mesh gradient 使用低強度 primary／tertiary container 混色，隨主題更新並快取，不加常駐動畫，也不改變元件尺寸。

## 字型、間距與圖示

### 字型

- 文字使用 `MaterialTheme.typography` 的 display、headline、title、body、label roles；根主題套用 `PocketTypography`。
- display、headline、titleLarge（含 emphasized）使用封裝的 Huninn 粉圓，適用頁名、區塊標題與主要數值。titleMedium／Small、body、label 維持 Material 預設，方便掃讀公告、課表與設定。
- Huninn 使用 Google Fonts Regular 完整字符集，OFL 授權隨 App 封裝並列於開放原始碼授權頁。此字型只有原生 Regular 字重，停用合成粗體；用字級與字型建立層級，罕見缺字交由 Android fallback 處理。
- `CLHS Pocket`／`Pocket` 品牌字樣使用 Outfit Bold 700 subset；它只包含品牌所需字元，不用於其他文案或數字。
- 對齊用數字（例如成績）使用 `fontFeatureSettings = "tnum"`。這是個別樣式，不是全 App 預設。
- 中文標題字距為 0sp。沿用 Material 字級與行高，尊重系統字體縮放。

### 尺寸與形狀

Android 介面使用 dp 表示間距與形狀、sp 表示字級。常見內距為 12、16、20、24dp，依既有元件調整，不另建 px 或 rem token。

| `MaterialTheme.shapes` | 圓角 |
| --- | --- |
| extraSmall | 8dp |
| small | 12dp |
| medium | 16dp |
| large | 24dp |
| extraLarge | 28dp |
| largeIncreased | 32dp |

一般容器使用對應的 shape role。圖表、課表格與 Glance Widget 可依資料幾何使用較小圓角。按鈕和切換元件沿用 Material 3 `shapes()`／`toggleableShapes()`；互動範圍至少 48dp。首頁編輯、個人、完成與新增元件入口使用固定形狀，按壓時保留 ripple 與既有觸覺回饋，不做形狀變換。

數字 badge 共用 `ui/UiUtils.kt` 的 `CountBadge`，直徑取內容所需寬高的較大值；多位數與放大字體仍維持正圓。使用 error／onError 語意色，保留各入口計數與無障礙描述。

圖示使用 [SymbolWrapper.kt](android/app/src/main/java/com/clhs/score/ui/SymbolWrapper.kt) 的 Rounded Material Symbols subset。新增 ligature 時更新並執行 [generate_material_symbol_subset.py](android/scripts/generate_material_symbol_subset.py)，同步產生 outline／filled 字型。首頁 Logo 使用由 `docs/public/logo.svg` 原始 path 與 viewBox 轉換的 `clhs_pocket_logo.xml`，按實際尺寸繪製；不要放大帶有安全區留白的 launcher foreground。

## 頁面骨架與版面

### 導覽與工具列

App 頂層依序為「首頁、課表、成績、校園」。手機直向使用 icon-only Floating Navigation Toolbar；寬度達 600dp 或可用高度不足 480dp 時使用 Navigation Rail。個人入口位於頂層頁右上角。

四個目的地各自保留 back stack。頂層導覽只在四個 root 顯示；重按目前目的地回到捲動頂端。登入、設定及其他子頁共用 Navigation 3 `NavDisplay`，隱藏頂層導覽並取消 overlay clearance；手機 IME 顯示時也隱藏 floating toolbar。

首頁以 Logo 與 Pocket 品牌列、大型日期時間卡片起始；捲動後由左上角日期時間膠囊接替。課表、成績、校園不顯示頁名，以品牌列起始並隨內容捲動離開。頂層工具膠囊使用 `onSurfaceVariant` 圖示色，浮動控制項使用 `PocketFloatingShadow`。膠囊內的圖示按鈕按壓時維持圓形。狀態列以背景漸層維持可讀性。

所有子頁共用無文字標題的浮動返回列。返回按鈕左距 20dp，位於狀態列下 8dp，觸控範圍為 48dp。返回鍵下方內容與首頁工具膠囊至時間卡片的起始間距一致，為 16dp。`PocketBackButton` 與個人頁設定按鈕共用 `PocketCircleIconButton`：48dp 圓形、24dp 圖示、`surfaceContainerHigh` 底色、`onSurfaceVariant` 圖示色。保留各頁既有位置、回呼與無障礙描述。

浮出式選單與工具膠囊保留 10dp 間距；這是展開選單與錨點的距離，不增加膠囊內按鈕間距。個人頁的單一設定圓鈕不加外框內距。公告詳情載入骨架與完成內容使用相同的頂部避讓。浮動導覽與子頁浮動控制項使用單層靜態陰影，模糊半徑 8dp、垂直偏移 2dp、scrim alpha 16%；其他卡片不全面加陰影。

### 寬度與系統 inset

首頁、成績、課表與共用 `SubpageLayout` 子頁最大寬度為 1200dp；個人、校園卡片與 Widget 設定為 720dp；首次設定為 520dp。頂部 App Bar 維持全寬，手機浮動導覽有獨立寬度上限。

使用 `widthIn` 時先於 `fillMaxWidth`。已填滿父層的容器要放寬水平限制時，先使用 `wrapContentWidth`。首頁、成績、課表與共用子頁的寬版內容置中；摘要和科目仍單欄上下排列，成績模擬器與折線圖保留各自配置。

內容需正確消化 safeDrawing 與 IME inset。`FloatingSubpageLayout` 的頂部避讓放在捲動內容內，讓 viewport 延伸至狀態列；底部避讓只包含導覽列。搜尋頁消費底部避讓後自行處理 IME，避免重複扣除鍵盤高度。個人與在校紀錄頁不顯示標題，返回與右上操作浮在可捲動內容上方。

## 共用元件與頁面

以下路徑均相對於 `android/app/src/main/java/com/clhs/score/`。

### 首頁

首頁由 `ui/overview/OverviewDashboard.kt`、`OverviewWidgetGrid.kt`、`OverviewWidgetContent.kt` 與 `OverviewScreen.kt` 組成，使用既有多實例 grid 與 editor state，資料取自 `OverviewState`。寬版沿用既有 grid packing 與 Widget 尺寸；課表摘要整區點擊導向課表，不加重複 CTA 箭頭。

首頁視覺焦點為課程 Hero。天氣、YouBike 與倒數依數值層級區分用途；短版顯示主要資訊，較高版本才增加細節。`PocketVisual` 只共用 Hero 與數值容器兩種形狀。主數字使用 emphasized typography；Outfit subset 不含數字，不用於數值內容。

近期行程與重要公告的標題放在容器外。內容使用官方 `SegmentedListItem` 和 `ListItemDefaults.segmentedShapes()`，列間距 4dp。日期膠囊固定在左欄並依 intrinsic width 保留空間，最小尺寸 52×48dp，水平內距 6dp、垂直 4dp。日期、內容與箭頭相對卡片垂直置中。字體放大時保留分欄，文字自然換行，不放大所有 spacing。公告日期放在左欄，小字只顯示單位，不重複日期或時間；未讀標記與點擊路徑沿用既有 state。

倒數以剩餘時間與事件名稱為主，不加外部區塊標題；較高版本補充日期。近期行程日期欄顯示起訖日期；全天事件的結束日期扣除 ICS DTEND 排他邊界。課表摘要以同一段文字呈現節次、教室與教師，不顯示鐘點區間；上課中另呈現可取得的下一節課。

YouBike 由使用者新增並選站。2×1 顯示總量與一般／電輔數量；2×2 以兩列呈現總量與車種。零值保留數字，未知數量不補零；停用顯示暫停營運，失敗保留上次數量，來源超過五分鐘標示可能過期。天氣與 YouBike 只在使用者點擊手動更新時，於右上角顯示官方 `LoadingIndicator`；進入頁面、背景及週期更新保持靜默。成功、失敗或離開頁面都結束手動指示器。較高元件使用可用高度分布資訊，避免底部集中留白；觸控範圍至少 48dp。

各首頁資料來源可分別載入、空白或失敗。天氣失敗不覆蓋整頁，也不為填滿版面捏造資料。只有尚無可用資料時顯示局部 Shimmer 骨架，包含天氣、YouBike、初次讀取的課表摘要、等待學校行程的倒數，以及空列表中的行程／公告列。保留已知站名與區塊標題，骨架配合元件尺寸與列表設定。已有資料時的手動或背景更新保留內容；手動更新仍使用右上角 `LoadingIndicator`。骨架使用 `LoadingShimmer` 的時間模型、系統動畫倍率與主題色，只在骨架顯示時運作；無障礙只讀一個載入描述。錯誤使用固定且易懂的文案，不顯示 exception 原文；權限只在使用者主動操作時請求。

首頁 Widget 編輯以 `dashboard_customize` 進入；新增元件位於編輯模式的 `HorizontalFloatingToolbar`。使用既有 grid 調整排列、尺寸與各實例設定。按完成才提交 Room；取消有變更時提供儲存、放棄或繼續編輯。重設與復原只改草稿，儲存失敗保留草稿。多個天氣、YouBike、倒數元件各自保留身份與設定。

拖曳與右下角縮放由 grid viewport 持有手勢，避免 lazy item 換位或改尺寸後重建。一次按住可跨多個位置或來回調整尺寸。拖曳預覽固定使用抓取時寬高；座標只在 placement 階段讀取，避免手指每次移動都重組整個首頁。Grid 列高只保留排列空間，各元件使用自然高度或所選固定高度；小元件不可被同列長內容拉高。

元件依閱讀順序往後排列，後面的元件不回填前方空洞。縮放只推移其後元件，不將原本相鄰元件移到其他區段。舊版回填排版按目前可見順序轉換，完成後才儲存新版順序標記；未儲存草稿和已提交配置分別轉換，取消仍還原已提交配置。拖曳依手勢開始時的落點幾何判定換位，保留穩定區間，避免重排反過來觸發換位或重複震動。小元件拖到列間自動保留一列，沒有「獨立一列」選項；後續元件不可回填到該列之前。第一指抓住元件時，第二指可捲動 viewport；浮動元件仍跟隨第一指，第二指操作期間停用邊緣自動捲動。

拉伸外框以連續像素尺寸跟隨手指；跨越合法尺寸門檻時立即更新草稿、內容與其他元件位置，不等放開才變更。按完成才儲存排列。新增或重新啟用元件後，等 grid 量測新位置再平順捲到元件；取消新增與修改既有設定不改變捲動位置。

天氣來源與授權碼只在首頁天氣元件設定管理，個人頁不提供天氣設定。「中央氣象署」選項的右側提供授權碼入口，直接開啟輸入對話框。沿用 `SettingsViewModel` 驗證與安全儲存；輸入值只保留在對話框記憶體，不寫入元件草稿。驗證與儲存訊息直接顯示在對話框。

### 成績

`ui/GradesScreen.kt` 顯示摘要、科目與分析，底部浮動 `PrimaryTabRow` 位於導覽列上方。學期與考試 split button 由右上角「選擇成績」按鈕展開。保留 pager 滑動、各頁捲動位置與下拉更新。成績子內容與圖表見 `ui/GradesSubjectsTab.kt`、`ui/GradesAnalysisTab.kt`、`ui/Charts.kt`、`ui/SubjectTrendLineChart.kt`；沿用資料、圖例、語意色與標籤。

成績模擬器摘要固定在底部，以數值、箭頭與總分呈現調整結果；原始與調整後標籤保留於無障礙語意。列表依摘要實際高度避讓，最後一科可完整捲出。寬版內容與摘要限制寬度並置中。右上角進階模式開啟獨立可捲動面板，包含採計、反推、學分設定與恢復全部；面板不受科目列表位置影響。學分設定與進階面板分開開啟。

### 個人、設定與在校紀錄

`ui/PersonalScreen.kt` 使用最大寬度 720dp 的 LazyColumn，保留 ProfileHero 與旋轉頭像。缺曠與獎懲入口附內容線索，不預先查詢資料。保留帳號安全；登出是低強調文字操作並經確認。右上角齒輪進入一般設定。

`ui/GeneralSettingsScreen.kt` 對 Guest 開放，使用 `SettingsSection`、`SettingsGroup`、`SettingsRow` 與 `SettingsSwitchRow`，集中外觀、啟動、通知與排程暫停、使用統計、關於。執行資訊與排程可靠性收合在「進階背景設定」。共用元件是否使用緊湊模式由呼叫端決定。整列保留觸控回饋及右側值；更新狀態放在箭頭左側。開發者選項與桌面課表 Widget 設定沿用同一選項列樣式，保留原有控制與選取語意。

設定與功能入口的選項列共用 `PocketOptionIcon`：44dp medium 圓角主題色容器內放置 24dp 圖示，圖示與文字間距 16dp。主文字使用 `titleMediumEmphasized`，說明使用 `bodySmall` 並保留 4dp 行距；列高隨換行增加，不截斷說明。設定列及開關列的水平內距皆為 16dp，避免不同 Material 元件預設縮排造成錯位。開關、勾選與箭頭保留各自語意。資料紀錄、搜尋結果、篩選籤與導覽列使用自己的資訊結構。

個人與在校紀錄子頁使用 `FloatingSubpageLayout`，不顯示頁面標題；右上操作與返回按鈕浮在內容上方。在校紀錄學期膠囊與內容右緣共用 20dp 內距；下拉更新使用 Expressive `LoadingIndicator`，並提供無障礙自訂動作。

`ui/StudentRecordsScreen.kt` 是登入保護的缺曠與獎懲子頁，最大寬度 720dp。學期控制使用緊湊 tonal 按鈕，概覽使用單一 primaryContainer。缺曠最多呈現三個已提供的官方分類數值；「全部分類」Bottom Sheet 保留所有分類與零值，不以總計替代分類。明細按原始日期分組；可解析的民國或西元完整日期依新至舊排列，無法解析的日期保留原文與所有紀錄。

獎懲頁以「全部／獎勵／懲處」依校方 group 篩選；未知分類仍顯示於「全部」。主列保留事由、類別、數量與發生日期；核定、銷過、學期欄位可按列展開。歷年統計獨立載入並預設收合；展開後顯示可水平捲動的六學期表格，缺值顯示「—」。更新失敗且保留舊資料時明示未更新。重試、工具列更新與下拉更新皆可用；非即時提示放在頁尾。不自行換算功過、總數或出席率。

### 校園、公告與行事曆

`ui/HubScreens.kt` 提供公告、行事曆與欣河智慧校園平台入口，不加入舊漢堡選單或重複的設定／關於入口。`ui/CampusSearchScreen.kt` 使用緊湊工具列與 M3 填色搜尋列；空白狀態提供快速搜尋，分類 Chips 在同頁切換全部、行事曆與學校公告。行事曆使用日期方塊；公告以日期和單位為次要資訊。相符文字加粗，列表重排沿用 `PocketMotion`。各來源分別載入與重試；公告沿用自動分頁及未讀／置頂排序。

公告與行事曆的右上搜尋按鈕原地向左延展為 48dp 高、最大寬度 560dp 的搜尋框，寬螢幕維持靠右，返回鍵位置不變。收合、鍵盤搜尋與返回會保留關鍵字，不另顯示搜尋條件橫條。X 清除關鍵字；右箭頭收合並保留關鍵字，鍵盤搜尋鍵也只收合。公告收合工具列只保留搜尋與更多；更多選單集中全部已讀、收藏、篩選及提醒。篩選 Bottom Sheet 可各選一個單位與類別；按「套用」才更新列表，關閉不改條件。「重設」只將草稿恢復為全部，仍須套用；清除關鍵字會保留篩選。選項沿用官網首頁與既有單位快取，更新失敗時提供重試入口。

公告與行事曆使用下拉更新。公告列表接近尾端時自動載入下一頁，不提供「載入更多」按鈕，也不加入來源摘要卡、獨立更新按鈕或更新時間。公告依未讀、置頂、一般排序，列表與首頁個別公告顯示紅色「未讀」標籤。成功載入詳情，或成功啟動外部連結 Intent 後，才寫入已讀。閱讀狀態由 Room 保存，與公告提醒開關及通知基準獨立；校園入口及導覽 Badge 共用 Room 未讀集合。

「全部已讀」只更新點擊時已知未讀 key 的快照，處理期間新發現的公告不受影響。沒有未讀、閱讀狀態不可用或處理中時停用。儲存失敗保留狀態並提示；讀取失敗不視為全部已讀。更新可用時，帳號入口顯示低干擾 Badge；自動檢查不彈窗、不跳頁。全部已讀完成後回到列表頂端，避免未讀排序改變時跳至下方。從首頁定位行程時，連同該日日期標題一起定位。

### 課表與背景設定

`ui/schedule/ScheduleScreen.kt`、`ScheduleCustomizationsScreen.kt` 沿用 grid、科目色、調課語意與個人偏好。App 週課表不顯示週次；週次保留在桌面課表 Widget。

`ui/schedule/CourseNotificationSettingsScreen.kt` 從課表更多選單進入，使用者可在頁面啟用當前課程通知、查看說明、通知權限與排程可靠性入口。排程暫停設定由一般設定的「通知與提醒」進入，Guest 可用；共用每日開關與 Material 時間輸入對話框，支援跨午夜。切換開關或確認時間後立即儲存；時間列使用低強調色。起訖相同時不能確認，儲存失敗時保留選擇供重試。

`ui/ScheduleReliabilitySettingsScreen.kt` 正常時只顯示簡短狀態與設定群組，不保留完成進度。待授權時才突出系統設定列；舊版 Android 省略授權列。電池最佳化收合於選用進階項目，不影響完成狀態。開啟設定失敗時顯示手動路徑，其他說明放入 Bottom Sheet。

`ui/SettingsTransferScreen.kt` 從開發者選項進入，主要畫面提供儲存備份與從檔案還原；JSON 編輯與複製收合於進階列。讀入檔案後直接顯示取代確認，使用者仍可選擇是否將科目套用至目前帳號。

## 互動、狀態與動效

### 登入與載入狀態

Guest 可使用公開內容與設定。私人內容由 `AuthGate` 顯示登入入口；登入成功後回到原目的地，不切換另一套 App shell。Welcome、外觀、權限與帳號是首次設定頁，期間隱藏頂層導覽。登入使用 WebView route，不用通用 Modal。

`AuthGate` 還原時先顯示中性 placeholder，約 300ms 後才顯示 loading；公開資訊維持可用。沒有 session 時才顯示登入提示。首頁各資料來源可分別載入、空白或失敗，避免單一來源錯誤覆蓋整頁。已有資料時背景更新保留內容；錯誤不顯示 exception 原文。權限只在使用者主動操作時請求。

### 動作回饋與轉場

觸覺回饋使用 `ui/InteractionHaptics.kt` 與 Compose `LocalHapticFeedback`，沿用系統設定。主要導覽、分頁／課表範圍選擇、個人與設定入口、手動更新使用輕量 tick；開關使用開／關回饋；首頁排序在開始拖曳與跨越位置時回饋。只在使用者操作 callback 觸發，不對重組、背景更新或一般捲動震動；保留既有長按回饋，避免重複觸發。

`AppNavigationMotion.kt` 集中管理導覽轉場。頂層 entry 不做整頁淡入或縮放，品牌列與操作膠囊保持穩定；內容從第一個繪製幀完整可見，透過同一個 NavDisplay scope 輕微向上進場，不做透明度轉場。各頂層頁面保留自己的捲動位置。子頁進入位移為寬度的 1/12，父頁退出位移為 1/24，並加上 fade。語意 motion 由 `ui/theme/PocketMotion.kt` 取自 root `MotionScheme.expressive()`。Predictive Back 由 NavDisplay 管理；不要在畫面另外加入返回攔截或固定毫秒動畫。

自訂 UI 使用 `PocketMotion` 語意 token：effects 用於不改變 geometry 的透明度或顏色，spatial 用於位置、尺寸與展開。一般 UI 不在 feature code 自訂 tween／spring；既有課程詳情展開 timing 集中保留。倒數、進度與 shimmer 等時間語意動畫維持 domain-specific。自訂元件使用 App root 的 Expressive motion；Material 元件沿用同一 theme，不重複包裝動畫。動畫尊重 Android 系統 animation scale，不另設 App-level Reduce Motion 偏好。

個人卡片的帳號形狀約 20 秒旋轉一圈。點擊形狀依序切換 Material 形狀並反轉方向，中央圖示保持正向。形狀由 `SettingsRepository` 寫入 `app_settings`，重開 App 後保留；儲存失敗時沿用原形狀並提示重試。索引 0–3 固定對應 Cookie9Sided、Clover4Leaf、Sunny、SoftBurst；旋轉方向依索引奇偶還原。連續旋轉使用等速動畫，遵守系統 animation scale，頁面不在前景時暫停。

保留圖示無障礙描述、toggle 語意與數字上下文。獨立 Activity 使用 edge-to-edge；內容正確消化 safeDrawing／IME inset。WebView loading 遮罩不得蓋住可操作工具列。

## Glance Widget

[ScheduleWidget.kt](android/app/src/main/java/com/clhs/score/widget/ScheduleWidget.kt) 使用 Glance 官方 Scaffold，無法直接套用 Compose MaterialExpressiveTheme；維持相同色彩與資訊層級。

- 高度小於 160dp 時只顯示上課中或下一節；寬度小於 220dp 時隱藏教師、地點與週次；高度至少 260dp 才顯示已下課區塊。
- 預設大小為 4×3，可縮至約 3×2。設定預覽沿用該 Widget 的尺寸、快照與偏好。
- RemoteViews 每個條件分支都明確設定 background 與 cornerRadius，避免重用時殘留樣式。
- `schedule_widget_preview.png` 只能由真實 Glance RemoteViews 產生。使用者明確要求裝置操作後，執行 `python android/scripts/generate_schedule_widget_preview.py`；不要手繪或以生成圖片取代。

## 開發與驗證

新增畫面前先找本文件中的同類頁面，再沿用現有 theme、state、元件與 [FakeData.kt](android/app/src/main/java/com/clhs/score/data/FakeData.kt)。不要新增平行 theme、導覽或硬編展示資料。Compose 預覽入口見 [ScorePreviews.kt](android/app/src/main/java/com/clhs/score/ui/ScorePreviews.kt)。依 [測試策略](docs/development/testing.md) 選擇本機檢查；預覽無法驗證互動、生命週期或平台行為時，再做裝置驗證。本文只描述實作規範，不代表已完成裝置視覺驗收。

單欄子頁由 FloatingSubpageLayout 提供 720dp 閱讀欄的水平留白，捲動容器維持全寬；一般 Column 將留白加在 verticalScroll 之後，LazyColumn 使用 readingContentPadding，讓左右空白也能拖動，背景與浮動工具列維持全寬；科目趨勢圖透過 contentMaxWidth 保留 1200dp。新增子頁預設沿用此限制，需要更寬的資料視覺化才明確覆寫。
