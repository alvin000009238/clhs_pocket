# iOS 資料存取、驗證與安全儲存

本文件說明 `feat/kmp-ios-data-security` 分支提供的 iOS 資料層，以及哪些路徑已經測試、哪些只通過編譯、哪些需要真實學校帳號才能驗證。

## 架構

| 層 | 位置 | 責任 |
| --- | --- | --- |
| 共用網路 | `android/shared/src/commonMain/kotlin/com/clhs/score/shared/net/` | 傳輸介面、學校用戶端（Cookie、世代、四個並行上限、重新導向）、公告、行事曆、天氣、YouBike 解析 |
| 共用資料 | `android/shared/src/commonMain/kotlin/com/clhs/score/shared/repo/` | `SessionVault`、帳號隔離快取、各 Repository、設定與 Android 相容備份、`AccountService`、Swift 入口 `PocketApi` |
| iOS 傳輸與儲存 | `iosApp/Sources/Data/` | `URLSessionTransport`、`ProtectedFileStore`、`PocketBackend`、`PocketSettingsFile` |
| iOS 安全 | `iosApp/Sources/Security/` | `KeychainStore`、`AppLockController`、`LockScreenView`、`PrivacyCoverView`、學校 WebView |

Swift 不重複業務邏輯：解析、快取策略、世代檢查與清除順序都在共用 Kotlin，Swift 只提供平台能力。

## 安全界線

- `URLSessionTransport` 使用 ephemeral 設定，不存 Cookie、不快取、不自動跟隨重新導向。Cookie 只由共用層的 `SchoolSessionState` 依世代管理，因此公開用戶端不可能帶到學校 Cookie。
- 學校工作階段與 CWA 金鑰只存在 Keychain：`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`、不同步 iCloud。CWA 金鑰只放在 `Authorization` 標頭。
- 檔案放在 Application Support 的 `PocketData`，排除備份。`private`（學校資料）使用 `.complete`，`public` 與 `settings` 使用 `.completeUntilFirstUserAuthentication`。
- 測試用啟動參數與腳本化驗證器只編譯進 Debug 建置。
- 登出、切換帳號、刪除全部資料都先撤銷記憶體中的授權，再清除 Keychain、Cookie、快取與 WebKit 資料。任何一步失敗都會以 `PocketError.kind == .cleanupIncomplete` 回報，並留下標記；標記存在時不會還原舊工作階段，也不能建立新的。
- App 鎖使用 `LAPolicy.deviceOwnerAuthentication`（Face ID／Touch ID，退回裝置密碼）。啟用時 App 啟動即鎖定，進入背景立即鎖定；開關鎖定都要先通過驗證。開關存在 Keychain，清除偏好設定或啟動參數無法關閉它。回到前景時只自動要求驗證一次，取消後需由使用者按下解鎖。鎖定期間不載入私人資料、不發布小工具與通知、不處理 Deep Link。App 只有單一場景；未支援多視窗。
- `pocketPrivacyProtected(lock:)` 在 App 非作用中（含 `willResignActive`）時同步蓋上不透明畫面並收起鍵盤，App 切換器不會顯示成績或課表。
- 學校 WebView 使用非持久的 `WKWebsiteDataStore`，主頁面只允許 HTTPS 的 `shcloud2.k12ea.gov.tw`（預設連接埠），子框架只允許同一主機或 `about:blank`，並以內容規則封鎖 HTTP 資源與第三方 Cookie；拒絕新視窗與下載。登入掛鉤只回報帳號欄位，不讀取密碼。這些規則未在真實校務網站上驗證（需要臺灣的 IP）。

## 接入 App

```swift
let backend = PocketBackend.live()
let lock = AppLockController(isEnabled: settings.appLockEnabled)

RootView()
    .pocketPrivacyProtected(lock: lock)

// 登入
SchoolLoginScreen(isVerifying: verifying, errorMessage: message,
                  onLogin: { studentNo, cookies in
                      Task { session = try await backend.login(studentNo: studentNo, cookies: cookies) }
                  },
                  onClose: close, onDismissError: { message = nil })

// 資料
let structure = try await backend.call { try await $0.loadStructure(session: session, forceRefresh: false, token: $1) }
```

`PocketError.kind` 區分 `.authenticationExpired`、`.transient`、`.school`、`.publicData`、`.storage`、`.cleanupIncomplete`、`.cancelled`、`.invalidInput`。取消 Swift `Task` 會取消進行中的請求。

設定匯出匯入使用 `PocketApi.exportSettingsJson`、`inspectSettingsJson`、`restoreSettingsJson`，檔案格式與 Android `SettingsTransfer.kt` 相同（`app = "clhs-pocket"`、`version = 1`、上限 1 MiB）。Android 專用欄位會原樣保留。

## 驗證

```sh
cd android && ./gradlew :shared:iosSimulatorArm64Test

cd iosApp && xcodegen generate
xcodebuild test -project CLHSPocket.xcodeproj -scheme CLHSPocket \
  -destination 'platform=iOS Simulator,name=iPhone 17'
```

單元測試不要加 `CODE_SIGNING_ALLOWED=NO`：未簽署的測試主程式沒有 Keychain 權限，Keychain 測試會以 `-34018` 失敗。加上 `TEST_RUNNER_SHOT_DIR=<目錄>` 會輸出鎖定畫面、隱私遮罩與登入失敗狀態的淺色、深色截圖。

所有自動化測試都以 `URLProtocol` 或假傳輸在本機回應，不會連到學校系統。

### 已測試

| 範圍 | 測試 |
| --- | --- |
| 解析、學校用戶端、世代與 Cookie 隔離、並行上限、Repository、設定備份 | `android/shared/src/commonTest/`（Kotlin/Native 模擬器） |
| URLSession 傳輸：標頭、Cookie 不儲存、重新導向不跟隨、大小上限、錯誤分類、取消 | `iosApp/Tests/URLSessionTransportTests.swift` |
| Keychain 存取屬性與錯誤傳遞、檔案保護等級、路徑限制、備份排除 | `iosApp/Tests/StorageTests.swift` |
| App 鎖狀態、WebView 主機與 Cookie 規則 | `iosApp/Tests/SecurityTests.swift` |
| 登入後 Cookie 只送往學校主機、重啟還原、登出與刪除全部資料、清除失敗、401、取消、設定檔往返 | `iosApp/Tests/PocketBackendTests.swift` |

### 僅通過編譯

- `SystemDeviceAuthenticator`（實際的 Face ID／Touch ID／密碼提示）。
- `SchoolLoginWebView`、`SchoolSiteWebView` 與 `SchoolWebCoordinator` 的實際頁面載入、JavaScript 掛鉤與對話框。
- `SchoolWebDataCleaner` 對 WebKit 預設資料庫的清除。
- App 切換器快照的實際遮蓋效果（狀態邏輯有測試，系統快照沒有）。
- 實體裝置上鎖定時的檔案保護行為；模擬器不強制資料保護。

### 需要真實帳號

- 學校登入頁（含驗證碼）與 `DoCloudLoginCheck` 回應格式。
- 以真實 Cookie 取得成績、學生資料、出缺勤、獎懲、課表。
- 工作階段過期後被導回登入頁的偵測。

測試環境的網路也無法連到 `shcloud2.k12ea.gov.tw`，因此登入頁本身沒有載入過。
