package com.clhs.score.shared.net

/** Synthetic public-data responses in the shapes the Android parser tests accept. */
internal object PublicFixtures {
    val RECORDED_LIST_JSON = """
    [
      {"pageNum":0,"maxRows":2,"totalPages":7532},
      {"newsId":"45072","top":1,"time":"2026/08/06","attr":"1","attr_name":"公告","title":"圖書館八月閉館時間異動公告","unit":"399","unit_name":"圖書館官網","issuer":"100103","name":"讀者服務組","content_type":"content","content":null},
      {"newsId":"45068","top":"0","time":"2026/08/06","attr_name":"最新消息","title":"中央研究院高中生命科學研究人才培育計畫","unit_name":"設備組官網","name":"設備組","content_type":"content"},
      {"newsId":"external","top":false,"time":"","attr_name":"通知","title":"校外活動","unit_name":"學務處","content_type":"url","content":"https://example.org/activity","future_field":"ignored"}
    ]
    """.trimIndent()

    val RECORDED_SEARCH_JSON = """
    [
      {"pageNum":0,"maxRows":20,"totalPages":2},
      {"newsId":"search-result","top":0,"time":"2026/08/17","attr_name":"公告","title":"高三第二次模擬考日程表","name":"教學組","content_type":"content"}
    ]
    """.trimIndent()

    val RECORDED_DETAIL_JSON = """
    [{
      "rcode":200,
      "newsId":"45072",
      "time":"2026-08-06 11:38:16",
      "title":"圖書館八月閉館時間異動公告",
      "unit":"圖書館官網",
      "issuer":"讀者服務組",
      "content":"%3Cscript%3Ealert(1)%3C%2Fscript%3E%3Cp%3E%3Cstrong%3E%E9%96%89%E9%A4%A8%E6%97%A5%E6%9C%9F%3C%2Fstrong%3E%3Ca%20href%3D%22%2Fnotice%22%3E%E8%A9%B3%E6%83%85%3C%2Fa%3E%3Ca%20href%3D%22javascript%3Aalert(1)%22%3Ebad%3C%2Fa%3E%3C%2Fp%3E%3Cimg%20src%3D%22%2Fischool%2Fstatic%2Fimage%2Fnews.jpg%22%20alt%3D%22%E5%85%AC%E5%91%8A%E5%9C%96%E7%89%87%22%3E%3Cimg%20src%3D%22https%3A%2F%2Ftracker.example%2Fpixel.png%22%3E%3Ctable%3E%3Ctr%3E%3Ctd%3Edata%3C%2Ftd%3E%3C%2Ftr%3E%3C%2Ftable%3E%3Ctable%3E%3Ctr%3E%3Ctd%3Emore%3C%2Ftd%3E%3C%2Ftr%3E%3C%2Ftable%3E",
      "content_type":"content",
      "attachedfile":"[[\"opaque\",245760,\"%u516B%u6708%u958B%u9928%u6642%u9593.pdf\"]]"
    }]
    """.trimIndent()

    val HEADING_DETAIL_JSON = """
    [{
      "rcode":200,
      "newsId":"45049",
      "title":"重補修課程開課時間及學生名單公告",
      "content":"%3Ch1%3E%3Cspan%20style%3D%22font-size%3A%2020px%3B%22%3E1.%E5%AD%B8%E7%94%9F%E5%90%8D%E5%96%AE%E8%AB%8B%E5%8F%83%E9%96%B1%E9%99%84%E5%8A%A0%E6%AA%94%E6%A1%88%3C%2Fspan%3E%3C%2Fh1%3E",
      "attachedfile":"[]"
    }]
    """.trimIndent()

    val OPEN_METEO_RESPONSE = """
    {
      "current": {
        "time": "2026-08-30T12:30",
        "temperature_2m": 26.6,
        "apparent_temperature": 29.2,
        "weather_code": 61
      },
      "hourly": {
        "time": ["2026-08-30T12:00", "2026-08-30T13:00"],
        "precipitation_probability": [20, 65]
      }
    }
    """.trimIndent()

    val CWA_RESPONSE = """
    {
      "success": "true",
      "records": {
        "Locations": [{
          "Location": [{
            "LocationName": "中壢區",
            "WeatherElement": [
              {"ElementName":"溫度","Time":[{"DataTime":"2026-08-30T15:00:00+08:00","ElementValue":[{"Temperature":"28"}]}]},
              {"ElementName":"體感溫度","Time":[{"DataTime":"2026-08-30T15:00:00+08:00","ElementValue":[{"ApparentTemperature":"31"}]}]},
              {"ElementName":"天氣現象","Time":[{"StartTime":"2026-08-30T12:00:00+08:00","EndTime":"2026-08-30T15:00:00+08:00","ElementValue":[{"Weather":"多雲短暫雨"}]}]},
              {"ElementName":"3小時降雨機率","Time":[{
                "StartTime":"2026-08-30T12:00:00+08:00",
                "EndTime":"2026-08-30T18:00:00+08:00",
                "ElementValue":[{"ProbabilityOfPrecipitation":"40"}]
              }]}
            ]
          }]
        }]
      }
    }
    """.trimIndent()

    val CWA_NEAREST_POINT_RESPONSE = """
    {
      "success": "true",
      "records": {
        "Locations": [{
          "Location": [{
            "LocationName": "中壢區",
            "WeatherElement": [
              {"ElementName":"溫度","Time":[
                {"DataTime":"2026-08-30T09:00:00+08:00","ElementValue":[{"Temperature":"25"}]},
                {"DataTime":"2026-08-30T12:00:00+08:00","ElementValue":[{"Temperature":"28"}]}
              ]},
              {"ElementName":"天氣現象","Time":[
                {"DataTime":"2026-08-30T12:00:00+08:00","ElementValue":[{"Weather":"晴"}]}
              ]}
            ]
          }]
        }]
      }
    }
    """.trimIndent()

    val CWA_ALL_PAST_RESPONSE = """
    {
      "success": "true",
      "records": {
        "Locations": [{
          "Location": [{
            "LocationName": "中壢區",
            "WeatherElement": [
              {"ElementName":"溫度","Time":[
                {"DataTime":"2026-08-29T12:00:00+08:00","ElementValue":[{"Temperature":"28"}]}
              ]}
            ]
          }]
        }]
      }
    }
    """.trimIndent()

    val SAMPLE_ICS = """
    BEGIN:VCALENDAR
    VERSION:2.0
    X-WR-TIMEZONE:Asia/Taipei
    BEGIN:VEVENT
    UID:all-day
    DTSTART;VALUE=DATE:20260810
    DTEND;VALUE=DATE:20260812
    SUMMARY:段考\,第一天
     延長
    END:VEVENT
    BEGIN:VEVENT
    UID:timed
    DTSTART:20260812T010000Z
    DTEND:20260812T023000Z
    SUMMARY:新生訓練
    LOCATION:圖書館\;二樓
    END:VEVENT
    BEGIN:VEVENT
    UID:cancelled
    DTSTART;VALUE=DATE:20260813
    STATUS:CANCELLED
    SUMMARY:取消活動
    END:VEVENT
    BEGIN:VEVENT
    UID:recurring
    DTSTART;VALUE=DATE:20260814
    RRULE:FREQ=WEEKLY
    SUMMARY:重複活動
    END:VEVENT
    END:VCALENDAR
    """.trimIndent()

}
