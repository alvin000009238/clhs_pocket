# CLHS Pocket 設計規範

本文件描述目前 Android 實作，供後續畫面與元件復用。以 [ScoreTheme.kt](android/app/src/main/java/com/clhs/score/ui/theme/ScoreTheme.kt) 和各元件原始碼為準；這不是網站 CSS token 或未實作的設計提案。

## 設計方向與資訊架構

CLHS Pocket 是繁體中文校園工具，讓學生快速掌握課表、成績與校園資訊。介面使用 Material 3 Expressive，以清楚的主次、tonal surface 與短文案組織內容。摘要可以有 Hero，資訊列則依內容分組，避免每筆資料都做成大型卡片。

- App 頂層依序為「總覽、課表、成績、校園」。手機使用 Navigation Bar，寬度達 600dp 使用 Navigation Rail；個人入口在頂層頁的右上角。
- 四個目的地保留各自 back stack；重按目前目的地回到該 stack 首頁。登入與子頁使用同一個 Navigation 3 `NavDisplay`。
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

## 字型、尺寸與形狀

- 文字使用 `MaterialTheme.typography` 的 display／headline／title／body／label roles；根主題沒有另建整套 Typography。一般字型與中文字形由平台 fallback 提供，未封裝 Noto Sans TC。
- `CLHS Pocket` 品牌字樣使用 `OutfitFontFamily`，來自 Outfit Bold 700 subset，只包含品牌名稱需要的字元。不可拿它排任意文案。
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

一般容器讀取 shape role。圖表、課表格與 Glance Widget 可保留資料幾何需要的小圓角。按鈕和切換元件沿用 Material 3 `shapes()`／`toggleableShapes()`，互動區至少 48dp。

圖示使用 [SymbolWrapper.kt](android/app/src/main/java/com/clhs/score/ui/SymbolWrapper.kt) 的 Rounded Material Symbols subset。新增 ligature 時更新並執行 [generate_material_symbol_subset.py](android/scripts/generate_material_symbol_subset.py)，同步產出 outline／filled 字型。

## 畫面與可復用元件

以下來源均位於 `android/app/src/main/java/com/clhs/score/`。

| 情境 | 現行組成與復用入口 |
| --- | --- |
| 頂層與子頁工具列 | `ui/UiUtils.kt` 的 `RootTopAppBar`、`AccountIconButton`；子頁先看 `ui/SubpageLayout.kt` |
| 總覽 | `ui/overview/OverviewScreen.kt`：課表 Hero／登入提示、精簡天氣列、提醒群組、近期行程與重要公告；只接收 `OverviewState` |
| 總覽寬版 | 內容可用寬度達 600dp 後，行程與公告並排；課表摘要整區保留點擊導向課表，不補冗餘 CTA 箭頭 |
| 成績 | `ui/GradesScreen.kt`：總覽／科目／分析的 PrimaryTabRow 與 pager；保留滑動、各頁捲動位置及下拉更新 |
| 成績寬版 | 摘要、科目、分析皆維持單欄上下排列，內容置中且上限為 1200dp；成績模擬器與折線圖子頁保留既有寬版配置 |
| 成績子內容與圖表 | `ui/GradesSubjectsTab.kt`、`ui/GradesAnalysisTab.kt`、`ui/Charts.kt`、`ui/SubjectTrendLineChart.kt`；沿用資料、圖例、語意色與標籤 |
| 個人與設定 | `ui/PersonalScreen.kt`：同一個 LazyColumn，最大寬度 720dp；Profile 加 SettingsSection／SettingsGroup／SettingsRow／SettingsSwitchRow。整列觸控回饋與右側值保留；更新狀態放在箭頭左側 |
| 校園 | `ui/HubScreens.kt`：公告、行事曆、欣河智慧校園平台入口；不恢復舊漢堡選單或重複的設定／關於入口 |
| 公告與行事曆 | `ui/announcements/SchoolAnnouncementsScreen.kt`、`ui/calendar/SchoolCalendarScreen.kt`：下拉更新；不加來源摘要卡、獨立更新按鈕或更新時間 |
| 課表與自訂 | `ui/schedule/ScheduleScreen.kt`、`ScheduleCustomizationsScreen.kt`：沿用 grid、科目色、調課語意及個人偏好 |

公告依未讀、置頂、一般排序，個別卡片顯示紅色「未讀」標籤；成功載入詳情後才標為已讀。更新可用時，帳號入口顯示低干擾 Badge；自動檢查不彈窗、不跳頁。

## 狀態、互動與動效

- 大螢幕內容置中：總覽、成績、課表與共用 `SubpageLayout` 子頁上限為 1200dp；個人、校園卡片與 Widget 設定上限為 720dp；首次設定維持 520dp。工具列與 App 導覽維持全寬。使用 `widthIn` 時須先於 `fillMaxWidth`，已填滿父層的容器先用 `wrapContentWidth` 放寬水平限制。

- `AuthGate` 在還原時先呈現中性 placeholder，約 300ms 後才出現 loading；公開資訊維持可用。沒有 session 才顯示登入提示。
- 總覽右上角 `dashboard_customize` 開啟自訂對話框：可拖曳調整課表、天氣、提醒、倒數、行程與公告的順序並切換顯示，分別調整行程／公告筆數，並搜尋勾選多個行事曆事件倒數。按儲存才套用，取消不改設定；可逐筆移除倒數或恢復預設；恢復預設後以 Snackbar 提供「復原」，還原重設前的完整草稿，仍須儲存才套用。倒數以不超過 220dp 的小卡自動換行，依開始時間排列，已結束事件置後；使用 bodySmall／titleMedium 與 12dp 水平、10dp 垂直內距，只顯示事件名稱與主要剩餘時間單位，移除日期與「查看全部」。點擊卡片開啟行事曆並定位、標示對應事件。行程與公告皆顯示且順序相鄰時，寬版依指定順序並排；其餘區塊使用全寬。
- 總覽來源可各自 loading、空白或失敗，天氣失敗不覆蓋整頁；不要為了填滿版面捏造資料。
- 錯誤使用固定、可理解的文案，不顯示 exception 原文。權限只在使用者主動操作時請求。
- [AppNavigationMotion.kt](android/app/src/main/java/com/clhs/score/ui/navigation/AppNavigationMotion.kt) 集中管理導覽轉場：頂層 Fade Through 搭配 0.98 起始縮放；子頁進入位移為寬度 1/12、父頁退出為 1/24，加上 fade。effects／spatial spec 取自 motion scheme。
- Predictive Back 由 NavDisplay 管理，沿進入方向反向退出。不要在畫面再加一套返回攔截或固定毫秒動畫。
- 高頻設定與工具互動沿用局部 `MotionScheme.standard()`。狀態變化不應讓資料區持續跳動。
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
