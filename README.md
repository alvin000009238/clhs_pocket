<p align="center">
  <img src="docs/public/logo.svg" alt="壢中 Pocket logo" width="112">
</p>

<h1 align="center">壢中 Pocket</h1>

<p align="center">
  中大壢中校園口袋工具
</p>

<p align="center">
  <a href="https://github.com/alvin000009238/clhs_pocket/releases/latest"><img src="https://img.shields.io/github/v/release/alvin000009238/clhs_pocket?label=Latest%20Release" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white" alt="Android 10+">
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/alvin000009238/clhs_pocket" alt="MIT license"></a>
  <a href="https://deepwiki.com/alvin000009238/clhs_pocket"><img src="https://deepwiki.com/badge.svg" alt="Ask DeepWiki"></a>
</p>

> [!IMPORTANT]
> 壢中 Pocket 是非官方第三方服務，由學生獨立開發，與中大壢中及欣河智慧校園平台沒有直接關係。

壢中 Pocket 是以 Kotlin、Jetpack Compose 與 Material 3 Expressive 製作的 Android App，讓壢中學生在同一個入口查看成績、課表、公開校園資訊。

## 功能

- 🧭 **總覽**：集中顯示現在或下一節課、天氣、近期行程與重要公告。
- 📊 **成績**：查看加權平均、排名、科目統計、歷次趨勢，並用成績模擬器試算目標。
- 🗓️ **課表**：查詢學期／週課表、查看調課異動、自訂科目資訊並保存課表圖片。
- 🏫 **校園資訊**：整合學校公告、公開行事曆與校務系統入口。
- 📱 **Widget 與提醒**：在桌面查看課表，選擇接收公告更新與段考資訊變更提醒。
- 🔒 **本機優先**：離線快取、加密 Session、生物識別鎖、深色模式與動態色彩。

## 畫面預覽

<table>
  <tr>
    <td align="center"><img src="docs/public/images/features/overview.webp" alt="總覽畫面" width="220"></td>
    <td align="center"><img src="docs/public/images/features/schedule.webp" alt="課表畫面" width="220"></td>
    <td align="center"><img src="docs/public/images/features/grades-overview.webp" alt="成績摘要畫面" width="220"></td>
    <td align="center"><img src="docs/public/images/features/campus.webp" alt="校園資訊畫面" width="220"></td>
  </tr>
</table>

## 隱私與安全

- 校務登入在學校既有的登入頁完成；成績與課表直接在使用者裝置與校務系統之間處理。
- 可重用的登入 Session 以裝置端加密保存。
- 成績、課表與學生資料可能留在 App 私有快取；登出與清除資料會依目前的資料清除規則處理。

詳見 [安全性政策](SECURITY.md)、[校務系統整合](docs/architecture/school-system.md) 與 [資料流說明](docs/architecture/data-flow.md)。

## 下載

[下載最新 APK](https://github.com/alvin000009238/clhs_pocket/releases/latest)

支援 Android 10 以上。尚未登入時，也可以使用總覽、天氣、近期行程、學校公告與公開行事曆；課表、成績與校務系統會在需要時引導登入。

## 開發

### 必要工具

- Android Studio（含 Android SDK Platform 37 與 Build Tools 37.0.0）
- JDK 25
- Git；Windows 使用 PowerShell 與 `gradlew.bat`

### 快速開始

```shell
git clone https://github.com/alvin000009238/clhs_pocket.git
cd clhs_pocket/android

# 建置不連線校務系統的假資料 Debug APK
.\gradlew.bat assembleDebug -PuseFakeData=true

# 有連線的 Android 裝置或模擬器時，可直接安裝
.\gradlew.bat installDebug -PuseFakeData=true
```

假資料模式不需要校務系統帳號、額外環境變數、Firebase service-account 或 release keystore。執行 JVM 測試：

```shell
.\gradlew.bat test
```

完整設定請看 [開發環境設定](docs/development/setup.md)、[假資料模式](docs/development/fake-data.md)、[建置與執行](docs/development/building.md) 與 [測試指南](docs/development/testing.md)。

## 專案結構

| 路徑 | 用途 |
| --- | --- |
| [`android/`](android/) | Kotlin／Jetpack Compose Android App 與 `:benchmark` 模組 |
| [`docs/`](docs/) | 架構、開發與貢獻者文件 |
| [`.github/workflows/`](.github/workflows/) | CI、CodeQL 與 signed release workflow |

## 參與貢獻

請先閱讀 [貢獻指南](CONTRIBUTING.md)、[Code of Conduct](CODE_OF_CONDUCT.md) 與 [安全性回報政策](SECURITY.md)。Issue、log、PR 與截圖都不得包含學號、姓名、Cookie、token、密碼或真實成績資料。

## License

[MIT](LICENSE) © 2026 alvin000009238
