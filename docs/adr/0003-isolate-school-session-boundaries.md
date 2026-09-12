# ADR 0003：隔離校務系統 session 與公開資料路徑

- **狀態：** Accepted
- **日期：** 2026-08-30

## 背景

校務系統 session、cookie 與成績資料很敏感；公告、行事曆與 Widget 不需要登入資料。若共用 HTTP client 或 session fallback，容易造成資料外洩、鎖定繞過或平行 cookie 競態。

## 決策

校務 API 只經 `SchoolGradeClient.prepareSession(session)` 與同步的 `SchoolCookieJar` 存取。公告與行事曆用固定 URL 的無 cookie client；Widget 只讀專用快照。WebView 只使用已解鎖的記憶體 session，並限制至固定 HTTPS origin。

## 後果

外部資料來源不能因方便而共用登入 client。改動 session、WebView、Widget、通知或 deep link 時需保留並驗證 `ArchitectureBoundaryTest` 的安全不變量。
