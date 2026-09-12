# 模組與責任

目前有產品 module `:app` 與測量／Baseline Profile module `:benchmark`；產品功能仍維持單一 app module。以下是 app 內以責任區分的主要 package；它們不是強制的 Clean Architecture 層。

| 路徑 | 責任 | 不應放入 |
| --- | --- | --- |
| `ui/` | Compose 畫面、視覺元件、使用者互動 | HTTP、cookie、DataStore 寫入 |
| `ui/navigation/` | `AppRoute`、Nav3 entry 與頂層 stack | feature 資料載入邏輯 |
| `viewmodel/` | UI state、使用者 action、取消與資料協調 | Android View 直接操作 |
| `data/` | model、repository、client、cache、加密 session | Composable state |
| `domain/overview/` | 跨資料來源的總覽聚合 | UI navigation |
| `ui/overview/` | 總覽畫面與公開／私人內容的呈現 | 直接查 repository |
| `ui/announcements/`、`ui/calendar/` | 公開公告、公告更新提醒入口與行事曆畫面 | 校務 Session |
| `widget/` | Glance widget 與各 Widget 偏好 | 一般登入 session |
| `reminders/` | WorkManager 段考更新提醒與公告更新提醒 | 原始成績、公告回應或例外文字對外送出 |
| `analytics/` | 匿名事件白名單與 logger | 直接收集個資 |
| `notifications/` | 一次性內部 action capability | 任意 Intent extra 的信任 |

新增功能時先找最接近的既有 feature：畫面放在對應 UI package，狀態放在既有 ViewModel，外部資料接在 repository。只有當兩個以上 feature 共用且責任穩定時，才抽出共用型別。

相關 ADR：[單一 module](../adr/0001-keep-a-single-app-module.md)、[頂層導航](../adr/0002-navigation-3-owns-top-level-stacks.md)。
