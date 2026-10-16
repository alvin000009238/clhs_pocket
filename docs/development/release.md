# Release 流程

功能從獨立 worktree 開發，以 PR 合併 `dev`。`dev` push 通過發布流程後，自動將使用真實環境的 `productionRelease` 發給 Firebase App Distribution 測試群組。正式版仍由 main 已包含的 `vMAJOR.MINOR.PATCH` tag 發布至 GitHub Releases。

## 從功能 worktree 交付

在主要 checkout 執行，將範例名稱換成這次功能。

```powershell
git fetch origin
git worktree add -b feat/schedule-cache ../clhs-pocket-schedule-cache origin/dev
Set-Location ../clhs-pocket-schedule-cache
```

1. 修改、驗證並提交功能，將分支推送後開啟以 `dev` 為目標的 PR。一般功能 PR 可依需要 squash。
2. 等待 **Test, lint, and assemble Debug APK** 通過，保持分支最新並完成審閱，再合併 `dev`。合併會觸發 Android CI 與同一份 `android-release.yml` 發布流程。
3. 發布流程先驗證 checkout 等於觸發的 commit，且該 commit 是 `origin/dev` 的 ancestor。完整 CI、歷史版本檢查與 APK 檢查通過後，將簽署 APK 發到 `dev-testers`。
4. 由測試者確認 Firebase 版本。準備正式發布時，完成 `CHANGELOG.md` 對應區塊，與前一正式版比較 Features、Bug Fixes 及 Performance Improvements，交由維護者確認。
5. 開啟 `dev → main` PR，等待相同 required check 通過並保持分支最新，以 **Create a merge commit** 合併，保留長期分支的共同歷史。普通 main push 執行 CI，不發布 APK。
6. 確認預定 release commit 的 main 檢查通過。在 main 已包含的 commit 建立新 tag，例如 `v2.0.1`，經維護者確認後推送。對應 changelog 不可為空。
7. 確認 GitHub Release 有 APK、release notes 與 API 提供的 APK SHA-256 digest。App 內正式更新仍使用既有 GitHub Releases 流程，不透過 Firebase 偵測更新。

功能完成後保留 worktree 供審閱。確認 PR 已合併、工作目錄乾淨且不再需要後，才在主要 checkout 清理。以下命令不會自動執行。

```powershell
git worktree remove ../clhs-pocket-schedule-cache
git branch -d feat/schedule-cache
```

若 PR 使用 squash，`git branch -d` 可能拒絕刪除。先確認原分支所有改動均已合併，再決定是否手動刪除，勿直接改用強制刪除。

## 共用版本序列與發布檢查

兩個管道共用既有 `android-release.yml` 的精確 `GITHUB_RUN_NUMBER` 作為 versionCode。不要更名或替換 workflow 來重設序列，也不從歷史最高碼加一、不建立 counter tag 或其他計數儲存。歷史只驗證 run number 是否可發布。

每次都讀取 GitHub 所有分頁與所有已發布 APK，包含 prerelease，驗證下載 SHA-256 digest 並解析實際 versionCode。GitHub 必須至少保留一個已發布 APK 與正式版本。Firebase REST 同時讀取 production app 的全部 release 分頁，以 `buildVersion` 比較。Firebase 尚無 release 可作為初次啟用，未知歷史、格式錯誤、認證、網路、digest 或 APK metadata 失敗都會停止，不能以 0 代替。新碼必須嚴格大於兩邊所有碼，且不超過 Android 上限 2100000000。

正式版 versionName 精確等於 tag 去掉 `v`，且語意版本高於 GitHub 所有已發布正式版本。Dev versionName 以 GitHub 目前最高的已發布正式版為基底，例如 `2.0.1-dev.42.abc1234`，其中 `42` 是共用 run number，`abc1234` 是觸發 commit 的前七碼。這個基底不是下一版預告。既有 UpdateChecker 會移除後綴比較正式版本，後續較高的正式版仍可更新，不修改 runtime 邏輯。

所有 dev 與 tag run 共用 `android-release` concurrency group，使用 `queue: max` 且 `cancel-in-progress: false`。GitHub 排程不保證發版順序；較大碼先發布後，歷史檢查會阻擋較小碼，不能以刪除歷史或放寬檢查解決。重跑使用相同 run number，已發布的碼不能再次發布。

穩定 tag 必須與 checkout HEAD 一致，commit 必須是 `origin/main` 的 ancestor，且對應 changelog 非空。允許選擇較舊的 main 穩定點，但該 commit 必須包含目前的 workflow、guard 與新版 changelog。已發布 tag 不可覆寫。GitHub 會使用 tag 上的 workflow，新版 guard 無法追溯保護舊 workflow。

兩個管道在解碼 signing key 前，均先執行完整 Android gate。在 `android/` 的本機對應命令為下列內容。

```powershell
.\gradlew.bat :app:testDemoDebugUnitTest :app:testProductionDebugUnitTest --tests 'com.clhs.score.FlavorConfigurationTest' :app:lintDemoDebug :app:assembleDemoDebug :app:assembleProductionDebug --warning-mode all
```

每次發布沿用既有 Gradle 管理的 API 35 AOSP x86_64 模擬器，以 `demo` 假資料產生一次 Baseline 與 Startup Profile，複製到 `android/app/src/productionRelease/generated/baselineProfiles/`，再用既有 release keystore 建置 signed arm64-v8a `productionRelease`。產物不會自行提交。本機一般 `assembleProductionRelease` 不會自動啟動模擬器。

發布前確認 APK 含 `assets/dexopt/baseline.prof`，並驗證 `com.clhs.score`、精確的已選 versionName、versionCode 與 `arm64-v8a` ABI 集合。任一失敗均停止。簽署由 Gradle 使用既有 keystore 完成，guard 不另行比對憑證指紋。Dev 以固定 `firebase-tools@15.28.1` CLI 上傳，帶入 release notes 與 tester group。正式版建立 GitHub Release。

Dev 與正式版使用相同 package、簽章、正式資料與 Firebase 設定。安裝 dev 會覆蓋原正式版並保留相同 app 資料，測試操作也會使用真實服務。較低碼的舊正式 APK 無法直接覆蓋較高碼 dev；下一次碼更大的 main 正式版可正常升級。本機手動安裝 versionCode 999 的 APK 不在發布歷史內，仍可能阻擋碼較小的 CI 版本。不要為測試降版而任意清除使用者資料。

## 設定 Firebase 與 GitHub secrets

Production Firebase project 是 `clhs-score`，production app ID 是 `1:1021793264456:android:d63f8d7b98f264b124497d`，已記錄在 `android/app/google-services.json` 的 `com.clhs.score` client。不要選 `.debug` app。Workflow 由這份設定取得 app ID 與 project number，若有設定 repository variable `FIREBASE_APP_ID`，值必須完全一致。

1. 在該 project 的 App Distribution 確認 production app 可用，建立群組 alias `dev-testers`，加入測試者。
2. 建立專用 service account，於該 project 授予 **Firebase App Distribution Admin**。為取得 auth action 的 OAuth access token，另授予該 service account 對自身的 **Service Account Token Creator**，並確認 **IAM Service Account Credentials API** 已啟用。
3. 將完整 JSON key 放入 repository secret `FIREBASE_SERVICE_ACCOUNT_JSON`，不可提交到 Git、貼到聊天或 log。
4. 如需指定 app，設定 repository variable `FIREBASE_APP_ID`。群組預設 `dev-testers`，可用 variable `FIREBASE_DISTRIBUTION_GROUP` 指定既有群組 alias。群組必須存在；CLI 上傳或群組分發失敗均視為失敗。
5. 保留既有 signing secrets `ANDROID_RELEASE_KEYSTORE_BASE64`、`ANDROID_RELEASE_KEYSTORE_PASSWORD`、`ANDROID_RELEASE_KEY_ALIAS`、`ANDROID_RELEASE_KEY_PASSWORD`。

`google-github-actions/auth` 以 service-account secret 產生 history REST access token 與 Application Default Credentials。Token 僅經 step environment 傳入 Python，不列印或存入發布產物。Firebase CLI 使用 ADC，長時間建置後可自行更新憑證。Auth action 結束時自動移除暫存憑證，signing cleanup 以 `always()` 移除 keystore。`gha-creds-*.json` 已加入 `.gitignore`。App 不新增 Firebase runtime 依賴。

Main 正式發布現在也必須有 Firebase 讀取權限與上述設定，因為正式碼必須高於曾發給測試者的碼。設定只在 GitHub repository settings 與 Firebase 管理介面完成。

不要以 `firebase login:list --json` 蒐集診斷，輸出可能包含 token。

官方文件：[Firebase CLI 分發](https://firebase.google.com/docs/app-distribution/android/distribute-cli)、[Firebase releases.list REST](https://firebase.google.com/docs/reference/app-distribution/rest/v1/projects.apps.releases/list)、[Google GitHub Actions auth](https://github.com/google-github-actions/auth)。

## 保護 dev、main 與版本 tag

由管理員至 **Settings → Rules → Rulesets**，確認既有 main 規則，並為 `dev` 建立或調整同等規則。

- Enforcement 設為 **Active**，明確指定目標分支。
- 啟用 **Require a pull request before merging**。
- 啟用 **Require status checks to pass**，加入精確 job 名稱 **Test, lint, and assemble Debug APK**，來源選 **GitHub Actions**。
- 啟用 **Require branches to be up to date before merging**。找不到 check 時，先讓該 workflow 實際完成一次。
- 啟用 **Restrict deletions** 與 **Block force pushes**。
- main 的 **Require linear history** 保持關閉，並保留 **Allow merge commits**，供 `dev → main` 合併使用。
- CodeQL 先驗證實際掃描與上傳結果，再決定是否設為 required。

另建 tag ruleset，限制 `v*` 的建立、更新與刪除給發布維護者。`dev` 是獲授權使用正式簽章及 Firebase 憑證的發布分支，合併 workflow、guard 或 Gradle 變更前必須審查。Repository secrets 本身無法限制只有 main 能使用，也無法阻止有權限者修改 workflow 繞過檢查。

參考：[GitHub Ruleset 可用規則](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets)。

## 失敗恢復

- 來源或 changelog 錯誤，透過 PR 修正。已發布 tag 不搬移或強推；未發布 tag 需要更新時，先取得維護者明確授權，並指向 main 已包含的 commit。
- 暫時性網路、SDK 或建置失敗，且兩邊均未發布時，排除原因後可重跑原 run。原 run number 不變，仍重新檢查全部歷史。
- versionCode 或 versionName 退版，停止發布並檢查歷史與 workflow 序列。Workflow 遷移若重設 run number，也須先審核延續序列的方案，不可移除 guard。
- package、版本、digest、profile 或 ABI 不符，修正後重新走 PR 與發布流程，不略過驗證。
- GitHub Release 已建立、Firebase 已上傳或上傳結果不明時，先人工確認服務端狀態與群組分發結果。不要盲目重跑或覆寫。需要替代 APK 時，以新 dev commit 或新正式 tag 取得新碼。

GitHub 與 Firebase 的可讀歷史可能因刪除或保留期限而減少，共用 workflow 的持續 run number 才是長期計數來源。外部手動發布、重設 workflow 序列或刪除歷史不在 guard 能保證的範圍內。發布期間不要另行手動修改兩邊 release。
