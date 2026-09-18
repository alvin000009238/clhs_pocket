# 貢獻指南

謝謝你願意協助壢中 Pocket。這是 Android 原生專案；小而可驗證的改動，比一次重整整個架構更容易審查與回歸。

## 開始前

```powershell
git clone https://github.com/alvin000009238/clhs_pocket.git
Set-Location clhs_pocket
```

1. 閱讀 [架構總覽](docs/architecture/overview.md) 與 [開發環境設定](docs/development/setup.md)。
2. 先用 `-PuseFakeData=true` 熟悉 UI；不要為了畫面驗證使用真實帳號或上傳校務資料。
3. 先搜尋既有 screen、repository、test 與 `AGENTS.md`，沿用現有資料流和命名。
4. 行為改動前先開 issue 或在 PR 說明問題、範圍與驗證方式。

## 開發原則

- 不提交學號、姓名、密碼、cookie、token、成績、截圖中的個資或 Firebase service-account JSON。
- 校務系統 session、cookie、WebView、Widget 與通知的安全邊界不能繞過；詳見 [校務系統整合](docs/architecture/school-system.md)。
- 不要為單一用途加 DI framework、額外 app module 或新依賴；先使用現有 Kotlin、AndroidX 與專案模式。
- UI 請維持繁體中文、Material 3 Expressive 與至少 48dp 的互動觸控區。視覺改動附模擬器或實機截圖。
- 文件預設用繁體中文，且連結要能從 GitHub 直接開啟。

## 本機驗證

在 `android/` 執行與改動相符的最小檢查；送 PR 前至少跑：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --warning-mode all
```

若觸及裝置功能（Widget、通知、WebView、檔案匯出），再於模擬器或裝置驗證該流程。詳見 [測試指南](docs/development/testing.md)。

## 分支與 commit

日常開發整合到 `dev`；功能分支從 `dev` 建立並以 PR 合回 `dev`。`dev`／`main` push 與目標為這兩個分支的 PR 都會執行 Android CI。

交付時開啟 `dev → main` PR，等待 `Test, lint, and assemble Debug APK` 通過並保持分支最新，再以 **Create a merge commit** 合併，保留長期分支的共同歷史。一般功能 PR 可依需要 squash。合併後才依 [Release 流程](docs/development/release.md) 在 main 已包含的 commit 標記版本。

使用描述用途的短分支，例如 `feat/schedule-cache`、`fix/webview-cleanup` 或 `docs/onboarding`。commit 使用 Conventional Commits：

```text
feat(scope): 簡短說明
fix(scope): 簡短說明
docs(scope): 簡短說明
refactor(scope): 簡短說明
test(scope): 簡短說明
chore(scope): 簡短說明
```

主旨不超過 50 個字元；需要時在 body 說明「為什麼」與驗證結果。不要把無關格式化、重命名或既有使用者改動混入同一 PR。

## Pull Request

請使用 PR 範本，並提供：

- 改動目的與不改動的範圍。
- 跑過的命令及結果；未跑的檢查要說明原因。
- UI 截圖、行為錄影或可重現步驟（若適用）。
- 風險、資料遷移、向下相容或安全性影響（若適用）。

進一步的程式風格、功能落點與審查標準請看 [`docs/contributing/`](docs/contributing/)。行為規範見 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。
