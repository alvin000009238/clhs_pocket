# 壢中 Pocket iOS（遷移驗證用）

這是以 **SwiftUI 原生元件** 建立的 iOS 工程基礎，**不是** 已完成的 iOS App。
目前四個 Tab 僅能展示導航，尚未接上登入、成績、課表或校園 API；不要對外發佈。

## 建置（需要 macOS）

- 安裝 Xcode（需具備所用 Kotlin/Native 版本支援的 Xcode SDK）、JDK 25、XcodeGen、Android SDK Platform 37。
- 執行 `cd iosApp && xcodegen generate` 產生 Xcode 專案。
- 在 Xcode 開啟 `iosApp/CLHSPocket.xcodeproj`，選擇 iOS Simulator 執行。
- Xcode 的 Compile Kotlin framework Run Script 會從 `android/` 執行 `:shared:embedAndSignAppleFrameworkForXcode`。
- 也可在 macOS 先執行 `cd android && ./gradlew :shared:iosSimulatorArm64Test` 檢查 Kotlin/Native 測試。

此工程使用系統 `TabView`、`NavigationStack`、`Form`、`Sheet`，在 iOS 26 以上由系統提供 Liquid Glass 外觀。iOS 17–25 使用各自原生設計，不模仿玻璃效果。

**驗證狀態：** 尚未在 macOS/Xcode 編譯與模擬器實測。工具鏈相容性、編譯及簽章必須先通過，才可開始遷移功能。
