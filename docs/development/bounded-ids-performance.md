# 公告 ID 合併配置量測

2026-10-30，Windows x64、OpenJDK 25.0.3、專案 Kotlin/JVM unit-test toolchain。
本次採用 `boundedIds` 的 LinkedHashSet 改寫：依 priority、existing 的迭代順序去重，達既有正數上限立即返回。
既有呼叫上限只有 500、100；不改通知 baseline、DataStore 或 API 流程。

## 方法與限制

`android/scripts/perf/CollectionAllocationProbeTest.kt` 保留前後兩個實作，使用合成 ID；每案例先驗證結果及迭代順序一致，再各暖機 10,000 次。每側 10 輪、每輪 3,000 次，交錯 AB/BA，volatile sink 消費完整結果；ThreadMXBean 量測執行緒配置 bytes。所有案例零結果不一致。

這是 HotSpot JVM helper microbenchmark，未測 Android ART、R8、裝置、通知 Worker 的 HTTP／DataStore 或 Widget 更新端到端耗時。背景有其他桌面程序，時間波動明顯；不能宣稱 App 加快某個百分比。配置下降來自移除串接、distinct、take 和第二次 set 建構；字串 hash、結果集合建構仍存在。沒有獨立 CPU profile，因此時間結果僅供參考。

| 案例（priority 筆數／existing 筆數／上限） | 前／後 bytes 每次（典型） | 前／後中位數 µs | 前／後範圍 µs |
| --- | --- | --- | --- |
| small（20／500／500） | 56,760／28,352 | 27.34／16.00 | 25.93–48.51／15.06–28.94 |
| baseline（20／0／500） | 1,600／560 | 1.123／0.409 | 1.109–2.286／0.402–3.040 |
| known-cap（600／500／500） | 65,520／28,352 | 66.00／42.56 | 54.59–77.84／28.82–52.21 |
| unread-cap（120／100／100） | 14,064／6,176 | 9.02／6.53 | 7.68–16.14／5.15–10.85 |

priority 使用每個 ID 重複兩次的輸入，與 existing 有重疊。原始完整樣本保留在 `android/scripts/perf/bounded.csv`；未丟棄離群值。第一次執行因輸出目錄不存在失敗，沒有使用該次數據；修正後重新完整量測。

## 重現

從 repository root 的 PowerShell 執行（JDK 25）：

```powershell
Copy-Item android/scripts/perf/CollectionAllocationProbeTest.kt android/app/src/test/java/com/clhs/score/data/CollectionAllocationProbeTest.kt
Push-Location android
try {
    .\gradlew.bat :app:testDemoDebugUnitTest --tests 'com.clhs.score.data.CollectionAllocationProbeTest' --no-daemon --no-configuration-cache
} finally {
    Pop-Location
    Remove-Item -LiteralPath android/app/src/test/java/com/clhs/score/data/CollectionAllocationProbeTest.kt
}
```

結果為 `android/app/build/reports/bounded.csv`。量測檔案不放在一般 test source set，避免日常 suite 執行效能實驗。

## 其他候選

`CourseResolution.resolveCourses` 的直接呼叫者是 `CourseNotificationCoordinator`；未證實為 Widget 的高頻熱點。其完整成本還包含日期／時間解析、時區轉換和排序，不能套用單純 flatMap 合成測試的 20–40% 主張。
`simulationHistorySource` 處理使用者選定考試的歷史資料；目前沒有熱點或完整函式量測證據。本 PR 不改這兩個函式，也不宣稱已證明它們無法優化。
