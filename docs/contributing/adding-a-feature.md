# 新增功能的落點

1. 先寫清楚使用情境、資料來源、外部入口、所需權限與離線行為。
2. 找最接近的既有功能，確認 screen、ViewModel、repository 與 test 分別放在哪裡。
3. 若需要校務資料，使用既有 `SchoolGradeClient` / repository 與 session 邊界；若是公開資料，建立無 cookie client 路徑。
4. 先建立 immutable UI state 與 loading / empty / error 行為，再接 UI。
5. 若是新頂層目的地，經 `TopLevelDestination`、`AppRoute` 與 `AppNavigationState` 擴充；不是每個頁面都需要頂層入口。
6. 補最小的 regression test，跑相符 Gradle 檢查，並更新使用者或開發文件。

## 最小 skeleton（以「圖書館」為例）

先沿用目前單一 `:app` module 的 package 慣例，不要先建立 Gradle module：

```text
ui/library/LibraryScreen.kt                 # 畫面與 feature 內元件
viewmodel/LibraryViewModel.kt               # immutable UI state 與使用者 action
data/LibraryRepository.kt                   # 資料來源與 cache 邊界（若真的需要）
app/src/test/.../LibraryViewModelTest.kt    # action -> state regression test
```

若「圖書館」是頂層目的地，才擴充 `TopLevelDestination`、`AppRoute` 和 `AppNavigation` 的 entry provider；若只是校園頁的一個子頁，加入既有 campus nested graph 即可。UI 不能直接呼叫 repository；ViewModel 不能讓公開資料改用登入 session。先完成 loading / empty / error state，再補畫面細節。

不要在 feature 首次實作時順帶拆 module、替換 navigation 或引進 DI framework。這些是獨立、需先證明收益的決策。
