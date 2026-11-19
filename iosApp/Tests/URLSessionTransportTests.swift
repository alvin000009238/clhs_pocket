import XCTest
@testable import CLHSPocket
@preconcurrency import PocketShared

final class URLSessionTransportTests: XCTestCase {
    private var transport: URLSessionTransport!

    override func setUp() {
        transport = URLSessionTransport(protocolClasses: [StubURLProtocol.self])
    }

    private func request(
        _ url: String, method: String = "GET", headers: [(String, String)] = [], body: String? = nil, limit: Int64 = 1_000_000
    ) -> HttpRequest {
        HttpRequest(
            method: method, url: url, headers: headers.map { HttpHeader(name: $0.0, value: $0.1) },
            body: body, timeoutMillis: 5_000, maxResponseBytes: limit
        )
    }

    private func run(_ request: HttpRequest) -> CallbackProbe {
        let done = expectation(description: "transport")
        let probe = CallbackProbe(done)
        _ = transport.start(request: request, callback: probe)
        wait(for: [done], timeout: 10)
        return probe
    }

    func testSendsExactlyTheGivenRequestAndReturnsStatusHeadersBodyAndCookies() throws {
        StubURLProtocol.install { _ in
            StubURLProtocol.text("{\"ok\":true}", status: 201, headers: [
                ("Content-Type", "application/json; charset=utf-8"),
                ("Set-Cookie", "ASP.NET_SessionId=abc; Path=/; Secure; HttpOnly"),
                ("Set-Cookie", "wide=1; Domain=k12ea.gov.tw; Path=/CLHSTYC; Max-Age=3600"),
            ])
        }
        let probe = run(request(
            "https://shcloud2.k12ea.gov.tw/CLHSTYC/x", method: "POST",
            headers: [("Cookie", "a=b"), ("X-Requested-With", "XMLHttpRequest")], body: "k=v&名=值"
        ))
        let response = try XCTUnwrap(probe.response)
        XCTAssertEqual(response.statusCode, 201)
        XCTAssertEqual(response.body, "{\"ok\":true}")
        XCTAssertEqual(response.header(name: "content-type"), "application/json; charset=utf-8")
        XCTAssertNil(response.header(name: "Set-Cookie"))

        let cookies = Dictionary(uniqueKeysWithValues: response.setCookies.map { ($0.name, $0) })
        let session = try XCTUnwrap(cookies["ASP.NET_SessionId"])
        XCTAssertEqual(session.value, "abc")
        XCTAssertNil(session.domain, "a cookie without Domain belongs to the responding host only")
        XCTAssertTrue(session.secure)
        XCTAssertNil(session.expiresAtEpochMillis)
        let wide = try XCTUnwrap(cookies["wide"])
        XCTAssertEqual(wide.domain, "k12ea.gov.tw")
        XCTAssertEqual(wide.path, "/CLHSTYC")
        let expires = try XCTUnwrap(wide.expiresAtEpochMillis?.int64Value)
        XCTAssertEqual(Double(expires) / 1000, Date().timeIntervalSince1970 + 3600, accuracy: 60)

        let sent = try XCTUnwrap(StubURLProtocol.requests.first)
        XCTAssertEqual(sent.method, "POST")
        XCTAssertEqual(sent.headers["Cookie"], "a=b")
        XCTAssertEqual(sent.headers["X-Requested-With"], "XMLHttpRequest")
        XCTAssertEqual(sent.body, "k=v&名=值")
    }

    func testNeverAddsStoredCookiesAndNeverStoresReceivedOnes() throws {
        let url = URL(string: "https://shcloud2.k12ea.gov.tw/CLHSTYC/x")!
        let planted = try XCTUnwrap(HTTPCookie(properties: [
            .name: "planted", .value: "1", .domain: "shcloud2.k12ea.gov.tw", .path: "/",
        ]))
        HTTPCookieStorage.shared.setCookie(planted)
        addTeardownBlock { HTTPCookieStorage.shared.deleteCookie(planted) }
        StubURLProtocol.install { _ in StubURLProtocol.text("", headers: [("Set-Cookie", "server=1; Path=/")]) }

        XCTAssertNotNil(run(request(url.absoluteString)).response)
        XCTAssertNotNil(run(request(url.absoluteString)).response)
        for sent in StubURLProtocol.requests {
            XCTAssertNil(sent.headers["Cookie"])
        }
        XCTAssertFalse((HTTPCookieStorage.shared.cookies(for: url) ?? []).contains { $0.name == "server" })
    }

    func testRedirectIsReturnedToTheCallerInsteadOfFollowed() throws {
        StubURLProtocol.install { request in
            request.url.path == "/start" ? .redirect(to: "https://other.example/landing") : StubURLProtocol.text("followed")
        }
        let response = try XCTUnwrap(run(request("https://shcloud2.k12ea.gov.tw/start", headers: [("Cookie", "a=b")])).response)
        XCTAssertEqual(response.statusCode, 302)
        XCTAssertEqual(response.header(name: "Location"), "https://other.example/landing")
        XCTAssertEqual(StubURLProtocol.requests.map(\.url.host), ["shcloud2.k12ea.gov.tw"])
    }

    func testOversizedBodyAndTransportErrorsAreReportedByKind() {
        StubURLProtocol.install { _ in .response(status: 200, headers: [], body: Data(count: 2_000)) }
        XCTAssertEqual(run(request("https://example.org/big", limit: 1_000)).failure?.kind, .tooLarge)
        XCTAssertNotNil(run(request("https://example.org/fits", limit: 2_000)).response)

        StubURLProtocol.install { _ in .failure(.timedOut) }
        XCTAssertEqual(run(request("https://example.org/slow")).failure?.kind, .timeout)
        StubURLProtocol.install { _ in .failure(.notConnectedToInternet) }
        let offline = run(request("https://example.org/secret-path?token=abc")).failure
        XCTAssertEqual(offline?.kind, .connection)
        XCTAssertFalse(offline?.message.contains("secret-path") ?? true, "failure text must not echo the URL")

        XCTAssertEqual(run(request("file:///etc/hosts")).failure?.kind, .invalidRequest)
        XCTAssertEqual(run(request("not a url")).failure?.kind, .invalidRequest)
    }

    func testCancellingTheCallReportsCancelled() {
        StubURLProtocol.install { _ in .hang }
        let done = expectation(description: "cancelled")
        let probe = CallbackProbe(done)
        let call = transport.start(request: request("https://example.org/hang"), callback: probe)
        call.cancel()
        wait(for: [done], timeout: 10)
        XCTAssertEqual(probe.failure?.kind, .cancelled)
    }
}
