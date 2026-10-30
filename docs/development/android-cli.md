# Android CLI 開發與驗證

Android 開發優先使用 `android-cli` skill 與 Android CLI。原本透過 ADB 完成的工作，只要 CLI 提供對應能力，就先使用 CLI；沒有對應命令時才使用明確指定 serial 的 ADB。此環境要求的 Device 工具優先於 CLI 的裝置操作命令。

## Compose Preview

從 `android/` 執行，明確選擇假資料 variant。每次 UI 修改後都必須 render 受影響的 Preview，開啟產出的 PNG 檢查；必要時補上對應假資料 Preview。渲染失敗不能當成視覺驗證通過。預覽不涵蓋手勢、生命週期、通知、系統權限或 OEM 行為，需要時再做裝置驗證。

```powershell
android --version
android info
android preview list ':app@demoDebug' --format=json
android preview render ':app@demoDebug' --fqn=com.clhs.score.ui.ScorePreviewsKt.YouBikePreview --component-tree --output=build/reports/android-cli-preview
```

使用 `preview list` 回傳的 `methodFqn`，不要自行拼接。Kotlin 頂層函式包含檔案類別名稱，例如 `ScorePreviewsKt.YouBikePreview`。同一函式有多個 `@Preview` 時需檢視各種尺寸、主題與字級輸出；可重複傳入 `--fqn`。`--component-tree` 額外匯出元件樹 JSON；`--daemon` 可供重複渲染使用，閒置十分鐘後結束。

Reference 格式是 `[system://][path]?[target]`，Gradle target 使用 `:module@variant`。此專案使用 `:app@demoDebug`，等價完整形式為 `gradle://.?app@demoDebug`。`:app` 預設選 `debug`，此專案沒有這個 variant；`:app:demoDebug` 則會被當成巢狀 module。

不要使用前次嘗試的 `-Pandroid.compose.preview.variant=demoDebug`。這不是已驗證的 CLI variant 選項，且未引用的含點參數在 Windows PowerShell 傳遞時曾被拆開，Gradle 實際收到 `.compose.preview.variant=demoDebug`，隨後在 Protobuf 套件發生 `No such property: sourceSets for class: java.lang.String`。正確 reference 已能通過該建置階段，無須因此修改 Protobuf 設定。需要傳遞 Gradle 參數時，完整引用各參數，並檢查 CLI 印出的 build log。

首次使用會下載 Gradle、Preview 與文件索引套件，需要網路與使用者快取目錄寫入權限。受限沙箱中 `android.exe` 曾回報無法啟動／Access denied；本機經授權的沙箱外執行可啟動，勿直接認定安裝損壞。JDK 仍依 [環境設定](setup.md) 驗證實際 JDK 25 與 `lib/jvm.cfg`，不要改寫全域設定。

## 工作對照與限制

以下命令以本機 `android --help`、子命令說明與官方 Knowledge Base 為準；命令存在不代表本機端到端驗證通過。

| 工作 | 優先工具／命令 | 限制 |
| --- | --- | --- |
| 建置 Demo APK | `android build ':app@demoDebug'` | 不取代既有完整 JVM／Lint／組建交付 gate |
| 原地安裝 APK | `android install --device=<serial> <apk>` | 支援 delta install；先核對目標、簽章，不自行卸載或清除資料 |
| 啟動或重新啟動 app | 此環境使用 `device_open` 回傳的 `agent-device` 命令 | 一般 CLI 有 `android run --device=<serial> <apk>`；此環境由 Device 工具操作 |
| UI hierarchy、截圖、輸入、手勢 | 此環境使用 Device 工具 | CLI 有 `layout`、`screen capture`、`input tap/text/scroll/swipe/key`；不可繞過 host 規則 |
| 語意定位與等待 | CLI `input` 提供 selector、`--wait`、`--match` | selector 字串預設為不分大小寫的子字串；精確匹配可用正規表示式。此環境仍由 Device 工具互動 |
| 裝置／模擬器盤點與開啟 | `device_list`、`device_open` | 多台或目標不明先詢問使用者；CLI `emulator list` 列出的是 AVD，不是已連線裝置清單 |
| SDK 查詢與管理 | `android sdk list/install/update/remove` | 安裝或變更套件需符合任務授權；查 API 37 可用 `android sdk list 'platforms*'`，本機名稱為 `platforms/android-37.0` |
| Perfetto／heap dump | `android profiler systemtrace/heapdump/session` | 系統追蹤 stop 可拉回產物；需裝置與對應權限，效能結論另需可重現量測 |
| 官方文件查詢 | `android docs search '<keywords>'`、`android docs fetch kb://...` | 首次建立索引；不需要校務帳號 |
| APK／AAB 分析 | `android analyze <path>` | 依檔案格式與 analysis 類型提供資訊 |
| Studio 整合 | `android studio check`，再用對應 analyze／find／render 命令 | 必須核對 READY 專案路徑，避免誤用另一 worktree |
| Logcat、任意 shell、pull/push、dumpsys、am／pm 診斷 | CLI 無通用對應時保留 `adb -s <serial> ...` | 1.1.16717390 頂層命令沒有通用 shell、logcat 或檔案傳輸命令，不宣稱完全取代 ADB |

Gradle 的 `connected*AndroidTest`、JVM、Lint、R8 與既有 combined warning gate 仍依 [測試策略](testing.md) 執行。執行 connected tests 前設定並核對 `ANDROID_SERIAL`，不在使用者手機上執行可能重設資料的測試。

## 本機驗證紀錄（2026-10-30）

- CLI／launcher：`1.1.16717390`；`android info` 可讀取 SDK。
- `preview list ':app@demoDebug' --format=json` 成功，列出 20 個預覽項目；Preview runfiles 的 Gradle 建置成功，並非只檢查 help。
- `preview render ':app@demoDebug' --fqn=com.clhs.score.ui.ScorePreviewsKt.YouBikePreview --component-tree` 成功產生 PNG 與 JSON，已開啟圖片確認假資料文字與數值可見。這只證明此 Preview 的渲染流程，不代表所有畫面或真實互動已驗收。產物放在本機 `android/build/reports/android-cli-preview/`，不提交。
- `android build ':app@demoDebug'` 成功：46 個 tasks 中 18 個執行、28 個 up-to-date。`android describe --project_dir=.` 成功列出 variants 與 APK 路徑；未執行完整 JVM／Lint gate，因本次僅修改文件。
- `android docs search` 與 `docs fetch` 成功建立並查詢 Knowledge Base。
- `android emulator list` 成功列出兩個已設定 AVD；沒有啟動或操作裝置。
- `android sdk list 'platforms*'` 成功列出 API 35、36、37.0。
- `android studio check` 成功，但 READY 專案位於另一個 worktree，不能視為本 checkout 的 Studio 驗證。
- 安裝、啟動、裝置輸入／截圖、profiling、Crashlytics、SDK 變更僅確認命令介面，未做端到端實測。

官方參考：[Preview render](https://developer.android.com/tools/agents/android-cli/commands/preview_render)、[CLI 命令表](https://developer.android.com/tools/agents/android-cli/commands/index)。可透過 `android docs fetch kb://android/tools/agents/android-cli/commands/preview_render` 取得 CLI 隨附索引內容。
