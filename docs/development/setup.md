# 開發環境設定

## 必要工具

- Android Studio（建議使用其內建 JDK 25）。
- Android SDK Platform 37 與 Build Tools 37.0.0。
- Git；Windows 使用 PowerShell 與 `gradlew.bat`。

第一次以 Android Studio 開啟 `android/`，讓它建立本機 `local.properties` 並下載 Gradle 依賴。`local.properties` 是本機 SDK 路徑設定，不能提交。

## 命令列建置

```powershell
Set-Location android
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

若系統找不到 JDK 25，使用實際 Android Studio JBR 並把 Gradle / Android user home 留在 workspace：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
$env:ANDROID_USER_HOME = "$PWD\.android-user-home"
.\gradlew.bat test
```

先用 `Test-Path $env:JAVA_HOME` 驗證 JBR 路徑；不同機器的 Android Studio 資料夾名稱可能不同。

## Build mode 與 Firebase

目前沒有 demo product flavor。`debug` 是日常開發 build，`release` 只由 signed release workflow 使用；`-PuseFakeData=true` 是可重複的本機假資料模式，不是另一種 variant。

`android/app/google-services.json` 是已追蹤的 Firebase Android client 設定，正常 Debug build 會使用它。開發者不需要 service-account JSON、FCM server key 或 release signing keystore；這些檔案和任何密碼都不能放進 repository。假資料 UI 開發仍不需要校務系統帳號。

## 首次閱讀路徑

1. [架構總覽](../architecture/overview.md)
2. [假資料模式](fake-data.md)
3. [建置](building.md) 與 [測試](testing.md)
4. [除錯](debugging.md)
