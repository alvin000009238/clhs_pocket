# Release 流程

1. 功能整合到 `dev`，push 會執行 CI。Android app 改動需更新 `CHANGELOG.md`，由維護者審閱；workflow／文件改動不增加 Android 版本。
2. 開啟 `dev → main` PR，等待 `Test, lint, and assemble Debug APK` 通過。本機對應命令為 `android/` 下的 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --warning-mode all`。
3. 分支保持最新，以 **Create a merge commit** 合併。main push 也執行 CI；確認預定 release commit 的檢查通過後再標記。
4. 在 main 已包含的 commit 建立 `vMAJOR.MINOR.PATCH` tag（例如 `v2.0.1`），經維護者確認後推送。普通 main／dev push 不會發布。
5. Workflow 完整 checkout、fetch `origin/main`，先驗證 tag 格式、tag 與 HEAD 一致、commit 為 main 的 ancestor，以及非空的對應 changelog 區塊，再產生 release notes。未合併 dev commit 會失敗。
6. 比對所有已發布 APK 的 SHA-256 與實際 versionCode，通過後才解碼既有 signing secrets、建置 signed arm64-v8a APK。
7. 發布前驗證 `com.clhs.score`、tag 對應 versionName、指定 versionCode、APK 簽章、正式憑證 SHA-256 與精確的 `arm64-v8a` ABI 集合；任一失敗均停止。通過後建立 GitHub Release。
8. 確認 Release 有 APK 與 release notes；App 內更新還要求 Release API 提供 APK 的 SHA-256 digest。

## 版本與相容性

保留 `GITHUB_RUN_NUMBER` 作為正式 versionCode，不改成語意版本公式，也不重設序列。2026-09-18 實際檢查 v2.0.0 APK 為 versionCode **34**，與 release run `34702023035` 的 run number 相同。每次發布都重新讀取所有已發布 APK（含 prerelease）的最高碼，要求新碼嚴格較大且不超過 Android 上限 2100000000。API、下載、digest 或 APK metadata 讀取失敗均停止，不能以 0 代替未知歷史。

允許 tag 指向較舊但已在 main 的 commit，以支援選定穩定點；versionName 必須高於所有已發布穩定版本，versionCode 也必須遞增。該 commit 本身必須包含新版 workflow、guard 與 changelog；GitHub 使用 tag 上的 workflow，這份修正無法追溯保護舊 workflow。

已發布版本不可覆寫；重跑已成功發布的 tag 會失敗。所有 release run 共用 concurrency group，不取消正在發布的 run；GitHub 可能替換尚未開始的 pending run，請逐版發布，不假設排隊順序。workflow 遷移若造成 run number 重設，guard 會阻擋；維護者須先審核延續序列的方案，不能移除檢查。

正式憑證指紋固定於 `.github/scripts/release_guard.py`，來源是公開 v2.0.0 APK。這不是私鑰；更換簽章前須另行規劃 signing lineage 與實際升級驗證。手動安裝 versionCode 999 的本機版不屬於公開 release 序列，不能保證由較小碼的正式版直接升級。

必要 secrets 維持：`ANDROID_RELEASE_KEYSTORE_BASE64`、`ANDROID_RELEASE_KEYSTORE_PASSWORD`、`ANDROID_RELEASE_KEY_ALIAS`、`ANDROID_RELEASE_KEY_PASSWORD`。只在 GitHub repository settings 設定，不放入 log、原始碼或 PR。release job 結束時移除暫存 keystore。

## main Ruleset（管理員手動設定）

至 **Settings → Rules → Rulesets**。2026-09-18 現有規則名為 `set`，套用 default branch，required checks 清單是空的。編輯該規則並可改名為 **Protect main**，避免另建重複規則：

- Enforcement 設為 **Active**；Target branches 指定 `main`（或確認 default branch 為 main）。
- 啟用 **Require a pull request before merging**。
- 啟用 **Require status checks to pass**，按 **Add checks**，選精確名稱 **`Test, lint, and assemble Debug APK`**，來源選 **GitHub Actions**。這是 job 名稱，不是 workflow 名稱 Android CI。
- 啟用 **Require branches to be up to date before merging**。找不到 check 時，先讓更新後的 dev push／PR 實際完成一次，再回來選擇。
- 啟用 **Restrict deletions** 與 **Block force pushes**。
- **Require linear history 保持關閉**，並在 Settings → General → Pull Requests 保留 **Allow merge commits**。
- CodeQL 暫不設為 required，先驗證修正後的實際掃描與上傳結果。

另建 tag ruleset 限制 `v*` 的建立、更新、刪除給發布維護者，可避免有寫入權限的人以舊版或修改過的 tag workflow 繞過 guard；repository secrets 本身無法保證只能由 main 使用。遠端設定須由管理員另行執行，本次不修改。

參考：[GitHub Ruleset 可用規則](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets)。

## 失敗恢復

- 來源／changelog 錯誤：將修正透過 dev → main 合併，再使用新的合法版本 tag；不搬移或強推既有 tag。
- 暫時性網路／SDK／建置失敗，且尚未建立 Release：排除原因後重跑原 run，run number 不變，歷史檢查仍重新執行。
- versionCode 或 versionName 退版：停止發布，檢查歷史與 workflow 序列；不能降低門檻或刪除歷史來繞過。
- 簽章／package／ABI 不符：修正設定後重新走 PR 與版本流程，不略過驗證。
- 已建立 Release 或上傳途中失敗：先人工確認公開 assets；guard 不覆寫已發布版本。需要替代 APK 時使用新版本，避免同版本不同內容。

歷史檢查依賴 GitHub 上保留的完整 Release 資產；刪除歷史、外部手動發布或繞過 tag 保護不在 workflow 能保證的範圍內。發版期間勿另行手動修改 Release。
