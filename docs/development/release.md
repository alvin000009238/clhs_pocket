# Release 流程

1. 在功能完成後，更新 `CHANGELOG.md` 的新版本區塊，內容只記錄 Android app 改動。
2. 先請維護者檢查與修改 changelog；不要跳過這一步推送 tag。
3. 在本機跑 `.\gradlew.bat test`、`.\gradlew.bat lint`、`.\gradlew.bat assembleDebug`。
4. 推送 `v<version>` tag。GitHub release workflow 會建置 signed arm64-v8a APK、驗證簽章和 ABI、萃取 changelog 並建立／更新 GitHub Release。
5. 確認 GitHub Release 有合法 APK 與 release notes；App 內更新還會要求 Release API 提供 SHA-256 digest 才會交給系統安裝器。

必要 secrets 只在 GitHub repository settings 設定：`ANDROID_RELEASE_KEYSTORE_BASE64`、`ANDROID_RELEASE_KEYSTORE_PASSWORD`、`ANDROID_RELEASE_KEY_ALIAS`、`ANDROID_RELEASE_KEY_PASSWORD`。不得放入原始碼、log、issue、PR 或本機可提交檔案。
