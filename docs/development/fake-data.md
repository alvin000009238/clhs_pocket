# 假資料模式

假資料讓 UI、互動、佈局與本機測試不依賴校務系統帳號。

```powershell
Set-Location android
.\gradlew.bat :app:installDemoDebug
```

`demo` flavor 固定 `BuildConfig.USE_FAKE_DATA=true`，使用 `FakeGradeRepository`、`FakeScheduleRepository` 與 `FakeData.session` 假帳號，不需要校務帳號。`production` 固定真實資料；舊 `-PuseFakeData=true` 已移除。展示資料集中在 `android/app/src/main/java/com/clhs/score/data/FakeData.kt`，Compose Preview 在 `ui/ScorePreviews.kt` 直接以該資料組成 UI state。

YouBike 在 Demo 與 Production 都使用官方公開即時資料，不受 `USE_FAKE_DATA` 控制；Preview 與隔離單元測試仍使用假資料。

新增 preview 情境時，先擴充 `FakeData.kt`，不要在個別 Composable 寫臨時硬編資料。假資料不能包含真實學生、課表、帳號或 cookie。

`MockGradeSystem.generateReport` 以顯示至小數第 2 位的科目分數及模擬器預設學分產生加權總分與平均，國文、英文、數學為 4 學分，其餘為 2 學分；摘要以 HALF_UP 顯示兩位小數。一般 Demo 考試可用預設學分核對，測試仍可透過 `customTotalScore`、`customAverageScore` 明確覆寫官方摘要。SPECIAL 情境保留缺考與作弊，摘要將這些科目以 0 分計入學分，但模擬器仍拒絕用這些成績核對或推算；`-999` 科目則完全省略。

Demo 登出後可從登入入口重新啟用假帳號，不開啟校務 WebView。假帳號沿用 SessionStore 授權與撤銷流程；登入會建立 Widget 課表快照，課表查詢與快取載入也會同步快照，登出則撤銷授權並清理快照。所有內容仍為假資料。

`FakeData.session` 必須包含固定的假 cookie（`fake=cookie`），以符合 SessionSerializer 的非空欄位驗證。不可移除 cookie 或放寬正式 Session 驗證，否則 Demo 的持久化登入會失敗。

Demo 課表依 Asia/Taipei 日期產生當前學期選項與查詢週次，保留學期課表、週課表及最後查詢快取。Widget 與當前課程通知讀取同一授權快照；週課表過期後沿用正式的學期快取 fallback。Demo 可自行啟用當前課程通知，通知權限、排程可靠性、預設關閉及登出撤銷規則與 Production 相同；課程內容仍是範例資料，週末或未上課時不會強制顯示通知。
