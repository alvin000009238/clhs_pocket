# 壢中 Pocket iOS

原生 SwiftUI App，最低支援 iOS 18.0；業務與示範資料來自 Kotlin Multiplatform 的 `PocketShared` framework（`android/shared`）。目前進度與已知缺口見 [iOS 移植狀態](../docs/migration/ios-status.md)。

## 需求

- Xcode 26 以上（Liquid Glass API 需要 iOS 26 SDK；iOS 18–25 走 `#available` 後備樣式）
- JDK 25、Android SDK（`platforms;android-37.0`、`build-tools;37.0.0`）
- [XcodeGen](https://github.com/yonaskolb/XcodeGen) 2.40 以上

`CLHSPocket.xcodeproj` 由 `project.yml` 產生，不納入版本控制。

## 建置與執行

```sh
cd iosApp
xcodegen generate
xcodebuild -project CLHSPocket.xcodeproj -scheme CLHSPocket \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  CODE_SIGN_IDENTITY=- build
```

Xcode 的 build phase 會執行 `./gradlew :shared:embedAndSignAppleFrameworkForXcode`，不需另外先編譯 framework。

## 測試

```sh
# 共用 Kotlin 邏輯（Kotlin/Native）
cd android && ./gradlew :shared:iosSimulatorArm64Test

# UI 流程，並把每個畫面的截圖寫到 SHOT_DIR
cd iosApp && TEST_RUNNER_SHOT_DIR=/tmp/pocket-shots xcodebuild \
  -project CLHSPocket.xcodeproj -scheme CLHSPocket \
  -destination 'platform=iOS Simulator,name=iPhone 17' test
```

加上 `TEST_RUNNER_CONTENT_SIZE=UICTContentSizeCategoryAccessibilityXL` 可用輔助使用字級跑同一組流程。

兩個正式模式的流程（訪客首頁的公開小工具、開啟校務登入頁）會連到學校、天氣與 YouBike 的伺服器，預設略過；加上 `TEST_RUNNER_LIVE_NETWORK=1` 才執行。CI 不執行這兩個流程。

測試不要加 `CODE_SIGNING_ALLOWED=NO`（Keychain 單元測試需要簽署的測試主程式）；只跑 UI 流程可加 `-only-testing:CLHSPocketUITests`。

單元測試與 UI 流程建議分兩次執行（`-only-testing:CLHSPocketTests`、`-only-testing:CLHSPocketUITests`）：同一次執行時，單元測試的宿主 App 偶爾會在連上測試執行器之前停住。CI 也是分兩次跑。

UI 變更必須在 Simulator 檢視淺色與深色截圖；只通過建置不算視覺驗證。

## 小工具、通知與即時動態

範圍、限制與驗證結果見 [iOS 小工具、通知、即時動態與背景更新](../docs/migration/ios-widgets-notifications.md)。

小工具透過 App Group 讀取快照，entitlement 必須嵌入才有效。建置與測試一律使用 ad-hoc 簽署（`CODE_SIGN_IDENTITY=-`），不要加 `CODE_SIGNING_ALLOWED=NO`；否則 App 仍可執行，但小工具只會顯示「開啟 App 查看課表」。

```sh
# 只跑單元測試
xcodebuild -project CLHSPocket.xcodeproj -scheme CLHSPocket \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  CODE_SIGN_IDENTITY=- -only-testing:CLHSPocketTests test
```

## 啟動參數

| 參數 | 用途 |
| --- | --- |
| `-demoClock 2026-11-18T10:35` | 固定示範時間，讓截圖與測試可重現 |
| `-hasCompletedOnboarding NO` | 重新顯示歡迎畫面 |
| `-dataMode demo` | 使用示範資料；不加這個參數就是正式模式（訪客可看公開資料，登入走校務系統） |
| `-session guest` / `demo` | 示範模式下以訪客或示範帳號啟動 |
| `-lockAuth succeed` / `manual` / `relock` | 以固定結果取代 Face ID／密碼驗證；`manual` 會停在鎖定畫面等待點按，`relock` 啟動時解鎖、之後回到前景都維持鎖定 |
| `-appLockEnabled YES` | 啟動時開啟 App 鎖定 |
| `-platform.liveActivity NO` | 不啟動即時動態 |
| `-appearance system` / `light` / `dark` | 外觀 |
| `-resetLocalState YES` | 啟動時清除本機偏好（UI 測試每次都從乾淨狀態開始） |
| `-UIPreferredContentSizeCategoryName <類別>` | 系統參數，指定動態字級，例如 `UICTContentSizeCategoryAccessibilityXL` |

## 結構

| 路徑 | 內容 |
| --- | --- |
| `Sources/App/` | `AppModel`（session、分頁、各分頁獨立導覽堆疊、本機偏好）、`RootView`、`LiveData`（正式模式的資料載入與登入狀態）、`PocketAppServices`（示範模式的登入與通知權限、UI 測試用的固定驗證結果） |
| `Sources/Theme/` | Pocket 色彩、字體、卡片與 Liquid Glass 後備 |
| `Sources/Features/` | 歡迎與登入、首頁與小工具編輯、課表、成績（明細、模擬器）、校園（公告、行事曆、在校紀錄）、個人與設定 |
| `Sources/Platform/` | 小工具快照發布、通知、即時動態、背景更新、Deep Link |
| `Widgets/Extension/` | WidgetKit extension：課表小工具與即時動態 |
| `Widgets/Shared/` | App 與 extension 共用的快照模型 |
| `Widgets/Tests/` | `CLHSPocketTests` 單元測試 |
| `UITests/` | 端到端流程與截圖 |
| `../android/shared/src/commonMain/kotlin/com/clhs/score/shared/app/` | Swift 使用的入口：`PocketDataSource`、`PocketDashboard`、`PocketGrades`、`PocketScheduleStore`、`PocketCampusStore` |

SwiftUI 只呈現 `PocketDataSource` 回傳的顯示用快照；計算（加權平均、排名、學分驗證與推算、課表合併與異動、小工具排版、倒數、公告篩選）都留在共用 Kotlin。
