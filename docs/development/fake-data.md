# 假資料模式

假資料讓 UI、互動、佈局與本機測試不依賴校務系統帳號。

```powershell
Set-Location android
.\gradlew.bat installDebug -PuseFakeData=true
```

`-PuseFakeData=true` 會設定 `BuildConfig.USE_FAKE_DATA`，使 app 使用 `FakeGradeRepository` 與 `FakeScheduleRepository`，並在記憶體中啟用 `FakeData.session`，不需要連線到校務系統。它不改變正常的首次設定與 route contract；展示資料集中在 `android/app/src/main/java/com/clhs/score/data/FakeData.kt`，Compose Preview 在 `ui/ScorePreviews.kt` 直接以該資料組成 UI state。

新增 preview 情境時，先擴充 `FakeData.kt`，不要在個別 Composable 寫臨時硬編資料。假資料不能包含真實學生、課表、帳號或 cookie。
