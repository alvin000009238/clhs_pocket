# Pull Request 審查清單

## 作者

- 一個 PR 解決一個可說清楚的問題，避免混入無關重排。
- 說明使用者可觀察到的行為、未處理的範圍與風險。
- 列出實際執行的驗證命令與結果；UI 改動附截圖或錄影。
- 確認 diff 沒有個資、secret、cookie、token、keystore 或 debug log。

## Reviewer

- 資料流是否仍從 UI → ViewModel → repository → client/store？
- session、cookie、WebView、公開資料與 Widget 的邊界是否仍被保留？
- loading、empty、error state 是否符合現有 UI，而非只處理成功路徑？
- 是否新增了不必要的 dependency、抽象或 module？
- test 是否會在同樣的回歸再次失敗？

PR 模板提供同一份最小清單；不要以「之後再補」取代安全或可重現驗證。
