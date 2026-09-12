# 校務系統整合與安全邊界

校務系統資料是敏感資料。這份文件描述程式內已存在的邊界，新增功能不得繞過它們。

## Session 與 cookie

- `SessionStore` 將一般與段考更新提醒 session 加密保存，並使用不同 AAD；生物識別 session 另有 PIN 與硬體金鑰保護流程。
- 目前版本不以舊版 `EncryptedSharedPreferences` 還原登入狀態；找不到 `has_completed_onboarding_v2` 時重新進入首次設定並要求重新登入，舊版資料只由清理路徑處理。
- `SchoolGradeClient.prepareSession(session)` 是校務 API 使用 session/cookie 的唯一準備路徑；不要在每個 API 方法直接覆寫 cookie jar。
- `SchoolCookieJar` 的讀、寫、取代與清除都必須同步，避免平行請求互相覆蓋。
- 登出、學生切換與提醒停止必須清除對應資料；清除失敗不能假裝成功。

## WebView

- 已登入 WebView 只接受 `ScoreViewModel.getCurrentSession()` 的記憶體 session。
- 只允許 `https://shcloud2.k12ea.gov.tw`，不允許其他 host、file/content URL、mixed content、第三方 cookie 或多視窗。
- 離開 WebView 或登出時清除 cookie、cache 與網站資料。

## 公開資料與 Widget

- 學校公告、校務行事曆與公告更新提醒使用固定公開 HTTPS URL 和 `CookieJar.NO_COOKIES`，不得讀取登入 session；公告更新提醒的背景工作也不使用 FCM topic。
- 總覽天氣使用固定的中壢高中座標，不取得定位；Open-Meteo 與中央氣象署使用不同 cache，中央氣象署授權碼由 Keystore 保護並只放在 Authorization header。
- Widget 只讀 `GradeCacheStore` 的 widget 專用課表快照，沒有登入或 API 查詢權限。
- 深連結與通知 action 必須經過既有 capability 與生物識別鎖，不能直接觸發受保護查詢。

修改這些區域時，先跑 `ArchitectureBoundaryTest`，再跑對應本機測試；只有使用者明確要求時才進行裝置驗證。
