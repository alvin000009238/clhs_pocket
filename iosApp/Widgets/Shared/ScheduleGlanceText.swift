import Foundation

/// Traditional Chinese copy shared by widgets, the Live Activity and tests.
extension ScheduleGlance {
    /// Short status above the lesson name.
    var headline: String {
        switch state {
        case .empty: "尚無課表"
        case .expired: "課表已過期"
        case .inClass: current.map { "上課中 · 第 \($0.period) 節" } ?? "上課中"
        case .startingSoon: next.map { "即將上課 · 第 \($0.period) 節" } ?? "即將上課"
        case .beforeClass: next.map { "下一堂 · 第 \($0.period) 節" } ?? "下一堂"
        case .dayDone, .freeDay: next.map { "\(nextDayLabel) · 第 \($0.period) 節" } ?? restText
        }
    }

    /// Why nothing is in progress today.
    var restText: String {
        switch state {
        case .dayDone: "今日課程已結束"
        case .freeDay: "今日無排課"
        case .empty: "開啟 App 查看課表"
        case .expired: "開啟 App 更新課表"
        default: ""
        }
    }

    /// One line for VoiceOver and the inline Lock Screen widget.
    var summary: String {
        switch state {
        case .empty, .expired: return "\(headline)，\(restText)"
        case .inClass:
            guard let current else { return headline }
            return "\(current.subject) · \(current.endText) 下課"
        case .startingSoon, .beforeClass:
            guard let next else { return headline }
            return "第 \(next.period) 節 \(next.subject) · \(next.startText)"
        case .dayDone, .freeDay:
            guard let next else { return restText }
            return "\(nextDayLabel) \(next.startText) \(next.subject)"
        }
    }
}

extension ScheduleSnapshot {
    /// Synthetic lessons for the widget gallery and placeholders only.
    static func sample(now: Date = Date()) -> ScheduleSnapshot {
        let rows: [(String, String, String, String, Int64, Int64)] = [
            ("國語文", "高二30", "08:10", "09:00", 0xFFF4A261, 0xFFFFB877),
            ("數學", "高二30", "09:10", "10:00", 0xFF4F8FD9, 0xFF8FC0FF),
            ("英語文", "高二30", "10:10", "11:00", 0xFF5BAE7C, 0xFF8FDBAA),
            ("體育", "操場", "11:10", "12:00", 0xFFE0728A, 0xFFFFA3B5),
            ("物理", "實驗室", "13:00", "13:50", 0xFF8C7AD6, 0xFFBDAEFF),
            ("歷史", "高二30", "14:00", "14:50", 0xFFC79A3B, 0xFFE9C46A),
        ]
        // The third lesson is always 25 minutes in, so previews show progress.
        let anchor = Int64(now.timeIntervalSince1970 * 1000) - 25 * 60_000
        let offsets: [Int64] = [-120, -60, 0, 60, 170, 230]
        let lessons = rows.enumerated().map { index, row in
            let start = anchor + offsets[index] * 60_000
            return Lesson(
                id: index, epochDay: 0, dayOfWeek: 3, period: index + 1, subject: row.0, classroom: row.1,
                startMillis: start, endMillis: start + 50 * 60_000, startText: row.2, endText: row.3,
                colorLight: row.4, colorDark: row.5
            )
        }
        return ScheduleSnapshot(
            schemaVersion: schemaVersion,
            generatedAtMillis: anchor,
            validUntilMillis: anchor + 86_400_000,
            weekLabel: "第 12 週",
            lessons: lessons,
            entries: [Entry(atMillis: anchor, epochDay: 0, phase: .inClass, currentLessonId: 2, nextLessonId: 3)]
        )
    }
}
