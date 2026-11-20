import Foundation
import XCTest
@testable import CLHSPocket
@preconcurrency import PocketShared

/// Answers every request of a `URLSessionTransport` locally; nothing in these tests reaches a network.
final class StubURLProtocol: URLProtocol, @unchecked Sendable {
    enum Reply {
        case response(status: Int, headers: [(String, String)], body: Data)
        case redirect(to: String)
        case failure(URLError.Code)
        /// Never answers; the request stays on the wire until it is cancelled.
        case hang
    }

    struct Recorded {
        let method: String
        let url: URL
        let headers: [String: String]
        let body: String
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var handler: (@Sendable (Recorded) -> Reply)?
    nonisolated(unsafe) private static var recorded: [Recorded] = []

    static func install(_ handler: @escaping @Sendable (Recorded) -> Reply) {
        lock.withLock {
            self.handler = handler
            recorded = []
        }
    }

    static var requests: [Recorded] { lock.withLock { recorded } }

    static func text(_ body: String, status: Int = 200, headers: [(String, String)] = []) -> Reply {
        .response(status: status, headers: headers, body: Data(body.utf8))
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        var body = request.httpBody ?? Data()
        if let stream = request.httpBodyStream {
            stream.open()
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let count = stream.read(&buffer, maxLength: buffer.count)
                if count <= 0 { break }
                body.append(buffer, count: count)
            }
            stream.close()
        }
        let entry = Recorded(
            method: request.httpMethod ?? "GET", url: request.url!,
            headers: request.allHTTPHeaderFields ?? [:], body: String(decoding: body, as: UTF8.self)
        )
        let reply = Self.lock.withLock { () -> Reply in
            Self.recorded.append(entry)
            return Self.handler?(entry) ?? .failure(.notConnectedToInternet)
        }
        switch reply {
        case let .response(status, headers, body):
            var fields: [String: String] = [:]
            for (name, value) in headers {
                fields[name] = fields[name].map { "\($0), \(value)" } ?? value
            }
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: fields)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
        case let .redirect(location):
            let response = HTTPURLResponse(
                url: request.url!, statusCode: 302, httpVersion: "HTTP/1.1", headerFields: ["Location": location]
            )!
            client?.urlProtocol(self, wasRedirectedTo: URLRequest(url: URL(string: location)!), redirectResponse: response)
            client?.urlProtocolDidFinishLoading(self)
        case let .failure(code):
            client?.urlProtocol(self, didFailWithError: URLError(code))
        case .hang:
            break
        }
    }

    override func stopLoading() {}
}

/// Collects the single result of one transport call.
final class CallbackProbe: NSObject, HttpCallback, @unchecked Sendable {
    private let expectation: XCTestExpectation
    private let lock = NSLock()
    private var storedResponse: HttpResponse?
    private var storedFailure: HttpFailure?

    init(_ expectation: XCTestExpectation) {
        self.expectation = expectation
    }

    var response: HttpResponse? { lock.withLock { storedResponse } }
    var failure: HttpFailure? { lock.withLock { storedFailure } }

    func onResponse(response: HttpResponse) {
        lock.withLock { storedResponse = response }
        expectation.fulfill()
    }

    func onFailure(failure: HttpFailure) {
        lock.withLock { storedFailure = failure }
        expectation.fulfill()
    }
}

final class FakeWebDataCleaner: NSObject, WebDataCleaner, @unchecked Sendable {
    nonisolated(unsafe) var succeeds = true
    nonisolated(unsafe) var calls = 0

    func clear(completion: @escaping (KotlinBoolean) -> Void) {
        calls += 1
        completion(KotlinBoolean(bool: succeeds))
    }
}

func temporaryDirectory() -> URL {
    FileManager.default.temporaryDirectory.appendingPathComponent("pocket-tests-\(UUID().uuidString)", isDirectory: true)
}
