# 壢中 Pocket KMP 遷移決策與驗收契約（進行中）

## 目標與非目標

目標是將可共用的資料、業務規則與狀態邏輯逐步移入 Kotlin Multiplatform，讓 Android 現有 Jetpack Compose/Material 3 Expressive 介面、功能、設定、包名、簽章與導航行為保持不變；iOS 使用 SwiftUI/UIKit 原生 UI 與平台能力。

**不是** 將 Android UI 改為 Compose Multiplatform；**不是** 把 Android DataStore、Room、WorkManager、Glance、WebView 或 Keystore 直接搬到 `commonMain`；也不可在沒有 macOS/Xcode 實測的情況下聲稱 iOS 可交付。

## 工作區結構

- `android/app`：原生 Android shell、Compose UI、通知、Glance、WorkManager、Android 儲存與安全 adapter。
- `android/shared`：KMP common domain contracts；隨功能遷移放入共用 repository、use case、model 與可測試邏輯。Android 與 iOS 都依賴這個模組。
- `iosApp`：原生 SwiftUI shell；建立獨立導航 stack、視覺語意、登入 UI、通知、Widgets 等 iOS 實作。
- 所有跨平台資料讀寫都應明確分離介面與平台實作；不得讓 common 層直接持有 Android Context、Activity、Java-only 時間／數學 API 或 UIKit。

## 目前工具鏈相容性阻礙

2026-11-19 檢查到 Android 使用 Kotlin 2.4.21、AGP 9.4.1、Gradle 9.8.2、JDK 25、compileSdk 37。官方 KMP 相容表對 Kotlin 2.4.21 列出的 Gradle 上限是 9.7.0、AGP 上限是 9.3.1。現在的版本超出已公布的完整支援矩陣；**不能視為已相容**。必須先以 CI / macOS 實測決定是升級 KMP、調整 AGP/Gradle，或使用暫時隔離的 build，並證明原 Android 版本仍可建置。

KMP Android library 應使用 `com.android.kotlin.multiplatform.library`，而非新建 legacy `com.android.library` + `androidTarget()`。不要破壞 Android 既有 demo/production variants，因為 Android-KMP library 只有單一 variant。

## 遷移順序

1. **相容性與護欄**：建立 KMP module、可編譯的 Kotlin/Native framework、原生 SwiftUI 導航外殼；執行 Demo/Production Android CI、KMP common tests、iOS Simulator build。所有未通過項目在 PR 裡標成 blocker。
2. **共用純模型與規則**：從最小、無 Android/Java-only 依賴的功能開始；原始 Kotlin 程式碼只保留一份，Android 使用同一共用實作；以同資料 fixture 驗證兩平台輸出相等。
3. **公開校園功能**：公告、行事曆、天氣、YouBike；建立無憑證 HTTP adapter、快取、失敗降級及取消語意。不要把公開資料請求接到校務 Cookie Jar。
4. **校務登入與私人資料**：iOS WKWebView 僅載入學校受允許 HTTPS host；建立相應 Apple Keychain / LocalAuthentication adapter。確保 Session 單一權威、generation 隔離、舊請求取消與無條件清除保護；不得在測試、log、analytics 記錄個資。
5. **課表、成績、個人紀錄**：逐一遷移 parser、repository、快取、ViewModel/State owner；iOS 實作 SwiftUI；涵蓋缺考、排名、官方平均、學分推算及課表異動等既有極端情況。
6. **平台特殊功能**：Android Glance/AlarmManager/WorkManager 原封保留；iOS 使用 WidgetKit、UserNotifications、BackgroundTasks 等符合平台限制的等價能力，區別「效果等價」與「系統 API 完全相同」。
7. **品質與發佈**：逐功能對照功能矩陣、登入/登出/資料刪除、離線、通知、無障礙、iPhone/iPad、Light/Dark、旋轉、背景與前景、錯誤/空值/延遲、性能與記憶體；必要時跑實機。沒有證據不得宣告 complete。

## 完成標準

- Android Demo/Production unit tests、Lint、Debug/Release build、ArchitectureBoundaryTest 全部通過；UI 變更需比較原有 Compose Preview、實機導航與 Widget，不只是編譯。
- iOS 對應功能需有 SwiftUI 實作、Kotlin/Native 及 Swift build、iOS Simulator 行為測試、有效裝置/系統版本的驗證記錄。Liquid Glass 優先使用原生 TabView、NavigationStack、Toolbar、Sheet 等系統提供效果；客製視圖才使用 iOS 26 以上 glassEffect，並保留舊版 iOS fallback。
- 對每一功能記錄：原 Android contract、shared API、Android regression tests、iOS adapter、iOS tests、隱私／安全驗證和剩餘限制。禁止用 placeholder、空畫面、mock API 或跳過測試宣告功能完工。
- 僅在 Android 既有功能保持等價、iOS 核心功能通過驗收且獨立核對簽章、隱私與資料清除後，才建立發布候選版。

## 官方參考

- [KMP 既有 Android App 整合](https://kotlinlang.org/docs/multiplatform/multiplatform-integrate-in-existing-app.html)
- [KMP 官方相容矩陣](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
- [Android-KMP library plugin](https://developer.android.com/kotlin/multiplatform/plugin)
- [Apple Liquid Glass](https://developer.apple.com/documentation/TechnologyOverviews/adopting-liquid-glass)
