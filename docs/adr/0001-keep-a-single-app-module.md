# ADR 0001：維持單一 Android app module

- **狀態：** Accepted
- **日期：** 2026-08-30

## 背景

專案功能多，但目前唯一的 `:app` module 已有清楚的 package 邊界、可運作的測試與 release 流程。直接拆成多 module 會增加 Gradle、依賴圖、資源與 navigation 的維護成本。

## 決策

維持單一 app module。先以 feature 就近放置、縮小檔案責任、補齊文件與測試降低閱讀成本。

## 後果

新功能仍要克制跨 package 依賴。只有在有明確的獨立建置、重用或擁有權需求，而且成本／收益被量測後，才重新評估 module 化。
