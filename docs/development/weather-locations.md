# 天氣地點目錄

App 內建 `android/app/src/main/assets/weather_locations.json`，包含全臺
22 縣市、368 個鄉鎮市區。地點搜尋只比對本機台灣行政區，支援「台／臺」
別名及縣市、鄉鎮組合查詢；行政區同名時以縣市區分。搜尋無須網路，
沒有符合的行政區時顯示無結果；Open-Meteo 仍可作為所選台灣鄉鎮的預報來源。

執行入口為 [`WeatherLocationCatalog.kt`](../../android/app/src/main/java/com/clhs/score/data/WeatherLocationCatalog.kt)，
選定的 `WeatherLocation` 由首頁元件設定保存於獨立 Room 配置；CWA 授權碼仍
由既有加密儲存管理，不進入此目錄或元件 JSON。介面及資料約束見
[設計規範](../../DESIGN.md)與[公開資料／首頁維護契約](../architecture/maintenance-contracts.md#公開資料與首頁)。
本機對照授予的 `supportsCwa` 只隨明確選擇的鄉鎮保存，不由名稱或相近座標猜測。

## 資料來源

- [內政部鄉鎮市區界線服務](https://data.gov.tw/dataset/32157)的相關資料連結，
  指向[鄉鎮市區界線 TWD97 經緯度](https://data.gov.tw/dataset/7441)。
  本版採官方 TGOS 的 `TOWN_MOI_1140318` SHP／DBF，使用 `TOWNCODE`、
  `COUNTYNAME`、`TOWNNAME`，授權為政府資料開放授權條款第 1 版。
- [CWA 縣市目錄](https://www.cwa.gov.tw/Data/js/info/Info_County.js)及
  [鄉鎮目錄](https://www.cwa.gov.tw/Data/js/info/Info_Town.js)驗證每一筆縣市與
  鄉鎮名稱；不把網站七碼 ID 當成 API Geocode，也不依相近座標猜測行政區。
- [CWA OpenAPI](https://opendata.cwa.gov.tw/apidoc/v1)的「未來 3 天」端點摘要
  驗證縣市與 `F-D0047` 資料集對照。`LocationName` 沒有列出 enum，
  因此鄉鎮名稱另以上述官方目錄核對。

代表座標由行政區多邊形產生：選最大土地區塊的中間緯線，求所有邊界交點，
採最長內部區段中點，避開孔洞。座標是地區預報用的代表點，不代表測站、
人口中心或使用者所在位置。TWD97 經緯度保留六位小數；預報解析度下與 WGS84
差異可忽略，不作測量用途。

## 重建

從 repository root 執行，僅需 Python 標準函式庫，無須 API 授權碼：

```text
python android/scripts/generate_weather_locations.py
python android/scripts/generate_weather_locations.py --offline
```

第一次下載官方資料到已忽略的 `android/.artifacts/weather-catalog-source/`，
`--offline` 使用同一份來源重建。腳本列出每份來源的 SHA-256，檢查 368 筆
唯一行政區代碼、22 個縣市、CWA 名稱及經緯度範圍；任何不符都停止生成。
Python 3.13 僅關閉額外嚴格 X.509 格式檢查，仍保留 CA 信任鏈與主機名稱驗證。

2026-10-04 生成來源摘要：

| 來源 | SHA-256 |
| --- | --- |
| bounds.zip | `c5dc6c0f9a8cc1aad6758a0a6fb81b203718b37f42c5aa8e684262ca24a7d9dd` |
| cwa.yaml | `7147380d31e1537ef780941e18c6a0cfc0602be92f76f380172b621fe0653487` |
| town.js | `79fd50d5a80f3b1c35b1cce9acb62d721aca6419e158540721acbb899b5c0c07` |
| county.js | `fd8c3430f43e439fd30a6cc74e630f246aef60b137c1995071dd64a193807f5e` |
