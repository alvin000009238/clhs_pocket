# 開發環境設定

## 必要工具

- Android Studio（建議使用其內建 JDK 25）。
- Android SDK Platform 37 與 Build Tools 37.0.0。
- Git；Windows 使用 PowerShell 與 `gradlew.bat`。
- Android CLI；開發、Preview、APK 安裝與 SDK／文件查詢優先使用 CLI，命令與能力限制見 [Android CLI 工作流](android-cli.md)。

第一次以 Android Studio 開啟 `android/`，讓它建立本機 `local.properties` 並下載 Gradle 依賴。`local.properties` 是本機 SDK 路徑設定，不能提交。

## 命令列建置

```powershell
Set-Location android
.\gradlew.bat :app:assembleDemoDebug
.\gradlew.bat :app:installDemoDebug
```

若系統找不到 JDK 25，使用實際 Android Studio JBR 並把 Gradle / Android user home 留在 workspace：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
$env:ANDROID_USER_HOME = "$PWD\.android-user-home"
.\gradlew.bat :app:testDemoDebugUnitTest
```

先用 `Test-Path $env:JAVA_HOME` 驗證 JBR 路徑；不同機器的 Android Studio 資料夾名稱可能不同。

## Build mode 與 Firebase

`demoDebug` 是日常假資料建置，可與正式版同時安裝；`productionDebug` 連接正式環境，僅在需要驗證真實整合時使用。`productionRelease` 是唯一正式發布 variant。Android Studio 的 Build Variants 應選 `demoDebug` 進行日常開發。

`android/app/google-services.json` 是已追蹤的 Firebase Android client 設定，供 `production` 使用；`demo` 不初始化 Firebase，也不訂閱正式 FCM topic 或傳送 Analytics。開發者不需要 service-account JSON、FCM server key 或 release signing keystore；這些檔案和任何密碼都不能放進 repository。假資料 UI 開發不需要校務帳號。

## 首次閱讀路徑

1. [架構總覽](../architecture/overview.md)
2. [假資料模式](fake-data.md)
3. [建置](building.md) 與 [測試](testing.md)
4. [除錯](debugging.md)
