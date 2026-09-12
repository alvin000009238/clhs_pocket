# ADR 0002：Navigation 3 擁有頂層返回堆疊

- **狀態：** Accepted
- **日期：** 2026-08-30

## 背景

App 在手機使用 Navigation Bar、較寬螢幕使用 Navigation Rail，且各頂層目的地需要保留巢狀頁面的返回紀錄。把 navigation state 放在 UI control 或各 screen 會讓 deep link、Predictive Back 與狀態保存分散。

## 決策

以 `AppRoute`、`AppNavigationState`、`rememberNavBackStack()` 與 `NavDisplay` 管理頂層導覽。外部入口先解析為 `AppLaunchTarget`，通過登入和生物識別 gate 後才導向 nested graph。

## 後果

新頁面要加入既有 entry provider 與 back stack，不能在 Composable 自行建立平行的 navigation state 或重複攔截 Back。
