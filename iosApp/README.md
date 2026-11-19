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
  CODE_SIGNING_ALLOWED=NO build
```

Xcode 的 build phase 會執行 `./gradlew :shared:embedAndSignAppleFrameworkForXcode`，不需另外先編譯 framework。

## 測試

```sh
# 共用 Kotlin 邏輯（Kotlin/Native）
cd android && ./gradlew :shared:iosSimulatorArm64Test

# UI 流程，並把每個畫面的截圖寫到 SHOT_DIR
cd iosApp && TEST_RUNNER_SHOT_DIR=/tmp/pocket-shots xcodebuild \
  -project CLHSPocket.xcodeproj -scheme CLHSPocket \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  CODE_SIGNING_ALLOWED=NO test
```

UI 變更必須在 Simulator 檢視淺色與深色截圖；只通過建置不算視覺驗證。

## 啟動參數

| 參數 | 用途 |
| --- | --- |
| `-demoClock 2026-11-18T10:35` | 固定示範時間，讓截圖與測試可重現 |
| `-hasCompletedOnboarding NO` | 重新顯示歡迎畫面 |
| `-session guest` / `demo` | 以訪客或示範帳號啟動 |
| `-appearance system` / `light` / `dark` | 外觀 |

## 結構

| 路徑 | 內容 |
| --- | --- |
| `Sources/App/` | `AppModel`（session、分頁、各分頁獨立導覽堆疊）、`RootView` |
| `Sources/Theme/` | Pocket 色彩、字體、卡片與 Liquid Glass 後備 |
| `Sources/Features/` | 歡迎、首頁、課表、成績、校園、個人 |
| `UITests/` | 端到端流程與截圖 |
| `../android/shared/src/commonMain/kotlin/com/clhs/score/shared/app/` | Swift 使用的 `PocketDataSource` 與示範實作 |

SwiftUI 只呈現 `PocketDataSource` 回傳的顯示用快照；計算（加權平均、排名、課表合併與異動、倒數）都留在共用 Kotlin。
