# 程式風格

- 依現有 Kotlin / Compose formatting 與命名；不要在無行為改動的 PR 做全檔重排。
- 函式與變數使用能說明責任的名稱；UI state 是 immutable data，副作用留在 ViewModel 或 repository。
- 先使用既有 model、repository、error type 或 test fixture；沒有第二個使用者時不要建立抽象層。
- 註解用來解釋安全、不明顯限制或外部系統限制，不重述程式碼。
- 使用者可見文字是繁體中文；錯誤文案不能包含 URL、cookie、token、原始例外或學生資料。
- Android UI 遵循現有 Material 3 Expressive theme、shape 與無障礙觸控規則。

更多 UI、資料流與安全限制請見 [architecture](../architecture/overview.md) 與 repository 的 `AGENTS.md`。
