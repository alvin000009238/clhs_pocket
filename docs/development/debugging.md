# 除錯指南

## 先縮小範圍

1. 用假資料確認問題是否只出現在網路／session 路徑。
2. 找出最小可重現 action、畫面與裝置版本。
3. 從對應 ViewModel、repository 與 test 追資料流；不要直接在 UI 加 workaround。
4. 保留可安全分享的錯誤類別與時間資訊，移除學生與 session 資料。

## 常見情況

| 症狀 | 先看哪裡 |
| --- | --- |
| Gradle 找不到 JDK 25 | [setup.md](setup.md) 的 JBR 設定 |
| 網路 401 或 session 不一致 | `prepareSession(session)`、`SchoolCookieJar` 與取消舊 job 的流程 |
| Widget 資料舊或空白 | widget 專用快照與 `ScheduleWidget`，不要讓 Widget 讀 session |
| deep link 被鎖住 | `AppLaunchTarget`、生物識別鎖與 `MainActivity` intent 處理 |
| 公告／行事曆載入問題 | 公開 repository；它們不應使用校務系統 cookie |

遇到例外時不要把 exception message、HTTP body、URL、cookie、token 或成績直接展示給使用者或貼到 issue。
