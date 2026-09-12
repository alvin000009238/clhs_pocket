# 安全性政策

壢中 Pocket 處理校務系統 session 與成績快取，請不要在公開 issue、PR 或討論區揭露安全問題的完整細節。

## 支援範圍

| 版本 | 支援狀態 |
| --- | --- |
| `main` 與最新 GitHub Release | 支援 |
| 舊版 release | 請先升級後再確認 |

## 回報方式

1. 優先透過 [repository Security 頁](https://github.com/alvin000009238/clhs_pocket/security) 的私密漏洞回報功能提出。
2. 說明影響、重現條件、受影響版本與安全的 PoC。

我們會確認收到回報、評估影響與安排修補；修補發布前請避免公開揭露可被利用的細節。

## 安全邊界

- session 以 Android Keystore AES-GCM 或生物識別保護儲存，且不得進入備份。
- Widget、公開公告與公開行事曆不得讀取登入 session 或 cookie。
- WebView 只可連到校務系統固定 HTTPS 網域，離開時清除 WebView 資料。
- 分析事件、issue 與測試資料不得包含學生或校務系統敏感資料。

更完整的資料流請見 [校務系統整合](docs/architecture/school-system.md)。
