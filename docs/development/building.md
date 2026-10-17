# 建置與執行

所有 Gradle 指令都在 `android/` 執行。

| 目的 | 指令 |
| --- | --- |
| 日常假資料 Debug APK | `.\gradlew.bat :app:assembleDemoDebug` |
| 安裝 Demo Debug APK | `.\gradlew.bat :app:installDemoDebug` |
| 正式環境 Debug APK | `.\gradlew.bat :app:assembleProductionDebug` |
| JVM unit tests | `.\gradlew.bat :app:testDemoDebugUnitTest` |
| Android lint | `.\gradlew.bat :app:lintDemoDebug` |
| 正式 Release 縮減版 | `.\gradlew.bat :app:assembleProductionRelease` |
| Baseline Profile | `.\gradlew.bat :app:generateDemoReleaseBaselineProfile`（僅在明確要求裝置工作時） |
| Macrobenchmark（實體裝置） | `.\gradlew.bat :benchmark:connectedBenchmarkReleaseAndroidTest` |
| 裝置測試 | `.\gradlew.bat :app:connectedDemoDebugAndroidTest` |
| signed release（CI） | `dev` push 分發 Firebase 測試版；main 已包含的 `v*` tag 發布 GitHub 正式版 |

`demoDebug` 固定假資料且不初始化 Firebase；`productionDebug` 連接正式環境，使用時需謹慎。`productionRelease` 是唯一正式發布 variant。Debug build 不需要 release keystore；`demoRelease` 不使用正式 release key。不要在本機建立、提交或貼出任何正式 keystore 與密碼。

兩個管道共用同一 release workflow 與 versionCode 序列，先檢查 GitHub 與 Firebase 歷史，再執行完整 CI。通過後先驗證已提交的 Baseline 與 Startup Profile，再解碼簽章金鑰並建置 arm64-v8a APK。一般 Release 建置不啟動模擬器；Profile 由獨立的手動 workflow 產生，經人工提交與審閱後更新。發布前驗證 APK 內的 Profile、package、版本與 ABI。正式 tag 使用 `CHANGELOG.md` 對應版本說明，推送前先請維護者確認。Dev 使用相同 package、簽章與真實資料，安裝會覆蓋正式版。設定、Profile 更新與升降版限制見 [Release 流程](release.md)。

Macrobenchmark 數據應在實體 Android 裝置取得；模擬器可用於功能檢查，但不把模擬器數字當成正式效能基準。

## Gradle configuration 警告

2026-10-14 以 `help '-Dorg.gradle.deprecation.trace=true' --warning-mode all --no-configuration-cache` 追出 `Configuration.setVisible(boolean)` 的呼叫：KSP 2.3.12、AGP 9.4.1 與 Protobuf Gradle Plugin 0.10.0 都仍使用該 API。這些版本目前是各自最新的穩定發布；僅升級一個外掛不能消除所有呼叫。Gradle 預告在 11 移除該方法，這是未來升級的相容性待辦，不是本次 build failure。

保留 `--warning-mode all`。外掛發布穩定修正版後，重新開啟 deprecation trace 確認所有來源消失，再跑既有 Android gate；若改到 runtime／packaging／R8，另驗證 Production Release。不要以全面 suppression、降版 Gradle、未發布 snapshot 或修改本機 cache 的 JAR 取代修正。

`android.sync.suppressAgpWarnings=CANNOT_BUILD_SELECTED_TARGET_ABI` 會使 AGP 自身回報實驗選項警告，已從 `gradle.properties` 移除；不另加 `UNSUPPORTED_PROJECT_OPTION_USE` 來隱藏它。Android Studio 若仍有選定 ABI 提示，應檢查選定裝置與 variant。

發布來源：[AGP 官方 Maven metadata](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml)、[KSP 官方 Maven metadata](https://repo.maven.apache.org/maven2/com/google/devtools/ksp/symbol-processing-gradle-plugin/maven-metadata.xml)、[Protobuf 外掛官方 Maven metadata](https://repo.maven.apache.org/maven2/com/google/protobuf/protobuf-gradle-plugin/maven-metadata.xml)。另見 [Gradle 的 visible property 升級指引](https://docs.gradle.org/9.8.0/userguide/upgrading_version_9.html#deprecate-visible-property) 與 [Protobuf 外掛已知問題](https://github.com/google/protobuf-gradle-plugin/issues/815)。
