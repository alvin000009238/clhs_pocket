# 壢中 Pocket Android App

這是壢中 Pocket 的 Android 原生版本，使用 Kotlin、Jetpack Compose 與 Material 3 Expressive 實作。手機端直接連線學校系統。

貢獻與維護入口請先看 root [README](../README.md)：[環境設定](../docs/development/setup.md)、[假資料模式](../docs/development/fake-data.md)、[測試策略](../docs/development/testing.md) 與 [架構總覽](../docs/architecture/overview.md)。

## 技術棧

- Kotlin + Jetpack Compose + Material 3 Expressive
- OkHttp 直連 `https://shcloud2.k12ea.gov.tw/CLHSTYC`
- Jsoup 解析登入頁 hidden token 與 captcha 資訊
- kotlinx.serialization-json 解析學校端 JSON
- Proto DataStore、Android Keystore 與 AES-256-GCM 保存短期 session 密文
- MockWebServer、JUnit、Compose UI Test 做測試

## 專案設定

- `applicationId`: `com.clhs.score`
- namespace: `com.clhs.score`
- minSdk: 29
- targetSdk: 37
- compileSdk: 37
- Android Gradle Plugin: 9.4.0
- Gradle wrapper: 9.7.1
- Compose BOM: 2026.09.00


## 功能

- 登入頁：內嵌學校系統 WebView 登入，具備浮動控制按鈕並支援密碼自動填入
- 成績查詢：年學期與考試選擇，登入後直連學校 API 取得成績
- 總覽：摘要卡、重點解讀、本地推估洞察、快速入口、強弱科摘要
- 科目：精簡科目卡，點擊後展開五標落點、分佈摘要與上一考比較
- 進階：雷達分析、成績比較、五標分析、分數分布
- 歷次趨勢：同學期目前考試往前抓兩考，背景載入，不阻塞本次成績顯示
- 登出：清除 session 密文、cookie 與 token

## 資料與安全策略

- App 不保存密碼。
- 登入成功後，App 先把 cookies、studentNo 與 apiToken 序列化，再以 Android Keystore 管理的 AES-256-GCM 金鑰加密，最後只把版本、IV 與密文寫入 Proto DataStore。一般與段考更新提醒 session 使用不同 AAD，提醒 session 最長保留 48 小時。
- 生物識別 session 維持雙層加密：PIN 衍生金鑰加密 session，生物識別綁定的 Keystore 金鑰再加密 PIN。私有 preferences 只保存這些密文、IV 與 salt，不保存明文 session。
- 舊版升級後重設 App 設定並從 Welcome 重新設定、登入，不遷移舊 session。舊 `score_session` preferences 直接透過 Android API 整份刪除，不再依賴 `security-crypto`；刪除失敗交由既有隱私清理流程回報並重試。
- 登出、登入失效或段考更新提醒到期時，清除對應的本機 session。
- Manifest 設為 `allowBackup=false`，備份與裝置轉移規則也明確排除新舊 session 檔案。
- Manifest 依功能使用 `INTERNET`、通知、開機後恢復小工具、忽略電池最佳化與 APK 安裝權限；通知、背景提醒與更新安裝皆由使用者主動啟用。
- 禁止 cleartext traffic，只使用 HTTPS。

Android Keystore 金鑰不可由 App 匯出，但不保證每台裝置都有硬體或 StrongBox 保護；AES-GCM 也不保護已解鎖 process memory。Root 或已遭入侵的執行環境仍超出此儲存層能完整防護的範圍。


## 架構

```text
Compose UI
  -> ScoreViewModel
  -> GradeRepository
  -> SchoolGradeClient
  -> school system
```

主要模組：

- `data/SchoolGradeClient.kt`: 集中處理 Cookie 擷取、登入與成績 API 流程
- `data/GradeRepository.kt`: session restore、login、logout、structure loading、grade fetching
- `data/SessionStore.kt`: Proto DataStore 原子讀寫、session lifecycle 與 legacy migration 協調
- `data/SessionCrypto.kt`: Android Keystore key lifecycle 與 AES-256-GCM authenticated encryption
- `data/LegacySessionMigration.kt`: 舊 EncryptedSharedPreferences reader 與 biometric 密文儲存
- `data/GradeAnalysis.kt`: 成績分析、上一考比較、近三次趨勢、本地洞察與排名粗估
- `viewmodel/ScoreViewModel.kt`: UI state、背景比較與趨勢載入
- `ui/`: Login、結果頁、圖表與 Material 3 Expressive theme

## 建置與測試

在 Windows PowerShell：

```powershell
cd android
.\gradlew.bat test
.\gradlew.bat compileDebugAndroidTestKotlin
.\gradlew.bat assembleDebug
```

debug APK 產物：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

有 emulator 或實機連線時可再跑：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

## 安裝到實機

確認手機開啟 USB debugging 並已授權：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
```

安裝 debug APK：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r .\app\build\outputs\apk\debug\app-debug.apk
```

