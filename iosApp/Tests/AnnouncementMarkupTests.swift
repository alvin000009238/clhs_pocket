import SwiftUI
import XCTest
@testable import CLHSPocket

final class AnnouncementMarkupTests: XCTestCase {
    func testKeepsWhitelistedFormattingAndOnlyWebLinks() throws {
        let html = """
        <p>請<b>準時</b>出席<i>典禮</i>，詳見<a href="https://www.clhs.tyc.edu.tw/a?x=1&amp;y=2">公告</a>。</p>\
        <p><a href="javascript:alert(1)">危險連結</a><a href="file:///etc/passwd">本機檔案</a></p>
        """
        let body = try XCTUnwrap(AnnouncementMarkup.attributed(html: html))
        XCTAssertEqual(String(body.characters), "請準時出席典禮，詳見公告。\n\n危險連結本機檔案")
        let links = body.runs.compactMap(\.link)
        XCTAssertEqual(links, [URL(string: "https://www.clhs.tyc.edu.tw/a?x=1&y=2")!])
        let bold = body.runs.filter { $0.inlinePresentationIntent?.contains(.stronglyEmphasized) == true }
        XCTAssertEqual(bold.map { String(body[$0.range].characters) }, ["準時"])
        let italic = body.runs.filter { $0.inlinePresentationIntent?.contains(.emphasized) == true }
        XCTAssertEqual(italic.map { String(body[$0.range].characters) }, ["典禮"])
    }

    func testDropsActiveContentAndKeepsOnlyText() throws {
        let html = """
        <style>p{color:red}</style><script>document.cookie</script><!-- note -->\
        <div onclick="x()"><img src="https://evil.example/t.png"><iframe src="https://x">內嵌</iframe>報名&lt;截止&gt;&#x4E2D;&#25991;</div>
        """
        let body = try XCTUnwrap(AnnouncementMarkup.attributed(html: html))
        XCTAssertEqual(String(body.characters), "報名<截止>中文")
        XCTAssertTrue(body.runs.allSatisfy { $0.link == nil })
    }

    func testListItemsStayTogetherAndParagraphsAreSeparated() throws {
        let html = "<p>注意事項：</p><ul><li>攜帶學生證</li><li>穿著<strong>制服</strong></li></ul>第二行<br>第三行"
        let body = try XCTUnwrap(AnnouncementMarkup.attributed(html: html))
        XCTAssertEqual(String(body.characters), "注意事項：\n\n• 攜帶學生證\n• 穿著制服\n\n第二行\n第三行")
    }

    func testThousandsOfNestedTagsAreFlattenedWithoutOverflowingTheStack() throws {
        let html = String(repeating: "<b><i>", count: 5000) + "深層" + String(repeating: "</i></b>", count: 5000)
        let body = try XCTUnwrap(AnnouncementMarkup.attributed(html: html))
        XCTAssertEqual(String(body.characters), "深層")
    }

    func testTextAfterADeepSectionStillRenders() throws {
        let deep = String(repeating: "<b>", count: 40) + "深<table><tr><td>格</td></tr></table>" + String(repeating: "</b>", count: 40)
        let body = try XCTUnwrap(AnnouncementMarkup.attributed(html: deep + "<p>後面的段落</p>"))
        XCTAssertTrue(String(body.characters).hasSuffix("後面的段落"), String(body.characters))
        XCTAssertTrue(String(body.characters).contains("格"))
    }

    func testUnclosedAndMalformedMarkupDoesNotLoseText() throws {
        let body = try XCTUnwrap(AnnouncementMarkup.attributed(html: "<p>a < b and <b>c"))
        XCTAssertEqual(String(body.characters), "a < b and c")
        XCTAssertNil(AnnouncementMarkup.attributed(html: "<p> </p><script>x</script>"))
        XCTAssertNil(AnnouncementMarkup.attributed(html: ""))
    }
}
