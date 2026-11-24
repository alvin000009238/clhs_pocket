package com.clhs.score.shared.net

/** Synthetic school responses in the shapes the Android client tests accept. */
internal object SchoolFixtures {
    val gradeJson = """
        {
          "Message": "",
          "Result": {
            "StudentNo": "DEMO-001",
            "StudentName": "範例學生",
            "StudentClassName": "示範班級",
            "StudentSeatNo": "00",
            "Show班級排名": true,
            "Show班級排名人數": true,
            "Show類組排名": true,
            "Show類組排名人數": true,
            "ExamItem": {
              "Year": 114,
              "Term": "上",
              "ExamName": "期末考",
              "ClassRank": 15,
              "ClassCount": 37,
              "類組排名": 78,
              "類組排名Count": 221
            },
            "SubjectExamInfoList": [
              {
                "Year": 114,
                "Term": 2,
                "ExamName": "第二次段考",
                "YearTermItem": {
                  "Year": 114,
                  "Term": 2,
                  "TermText": "下"
                },
                "SubjectName": "國語文",
                "Score": 78,
                "ScoreDisplay": "78.00",
                "ClassAVGScore": 70.11,
                "ClassAVGScoreDisplay": "70.11",
                "ClassRank": 6,
                "ClassRankCount": 37,
                "YearTermDisplay": "114學年度 上學期"
              }
            ],
            "成績五標List": [
              {
                "SubjectName": "國語文",
                "頂標": 80,
                "前標": 75,
                "均標": 70,
                "後標": 60,
                "底標": 50,
                "標準差": 12,
                "大於90Count": 1,
                "大於80Count": 3,
                "大於70Count": 10,
                "大於60Count": 12,
                "大於50Count": 5,
                "大於40Count": 2,
                "大於30Count": 1,
                "大於20Count": 1,
                "大於10Count": 1,
                "大於0Count": 1
              }
            ]
          }
        }
    """.trimIndent()

    val scheduleJson = """
        {
          "payload": {
            "weeks": [
              {
                "items": [
                  {
                    "SubjectName": "數學",
                    "WeekDay": "1",
                    "SectionSeq": "2",
                    "TeacherNameDisplay": "範例教師",
                    "ClassroomName": "示範教室"
                  },
                  {
                    "SubjectName": "重複資料",
                    "WeekDay": "1",
                    "SectionSeq": "2"
                  },
                  {
                    "CourseName": "英語文",
                    "DayOfWeek": "3",
                    "Period": "4",
                    "FirstTeacherName": "代理教師",
                    "ClassroomDisplay": "語言教室"
                  },
                  {
                    "SubjectDisplay": "",
                    "DayOfWeek": "5",
                    "Period": "6"
                  }
                ]
              }
            ]
          }
        }
    """.trimIndent()

    val currentWeekJson = """
        [
          {
            "DisplayText": "第4週 (2026-07-19~2026-07-25)",
            "Value": "4",
            "Selected": true,
            "Item": {
              "WeekNo": "4",
              "StartDateDisplay": "2026-07-19",
              "EndDateDisplay": "2026-07-25",
              "IsSelected": false
            }
          },
          {
            "DisplayText": "第5週 (2026-07-26~2026-08-01)",
            "Value": "5",
            "Selected": false,
            "Item": {
              "WeekNoInt": 5,
              "WeekNo": "5",
              "StartDateDisplay": "2026-07-26",
              "EndDateDisplay": "2026-08-01",
              "IsSelected": false
            }
          }
        ]
    """.trimIndent()

}
