import Foundation
@preconcurrency import PocketShared

/// `HttpTransport` on `URLSession`.
///
/// The shared core owns redirects, cookies and caching decisions, so this transport does none of
/// them: it never follows a redirect, never stores or sends a cookie on its own, and keeps no cache
/// and no credential storage. The same instance serves public and school requests; whatever makes a
/// request authenticated is in the headers the shared core passes in.
final class URLSessionTransport: NSObject, HttpTransport, URLSessionDataDelegate, @unchecked Sendable {
    private final class Pending {
        let callback: HttpCallback
        let limit: Int64
        var data = Data()
        var tooLarge = false
        var redirect: HTTPURLResponse?

        init(callback: HttpCallback, limit: Int64) {
            self.callback = callback
            self.limit = limit
        }
    }

    private final class Call: NSObject, HttpCall {
        private let task: URLSessionTask

        init(task: URLSessionTask) {
            self.task = task
        }

        func cancel() {
            task.cancel()
        }
    }

    private final class FinishedCall: NSObject, HttpCall {
        func cancel() {}
    }

    private let lock = NSLock()
    private var pending: [Int: Pending] = [:]
    private var session: URLSession!

    /// - Parameter protocolClasses: `URLProtocol` stubs for tests; production passes none.
    init(protocolClasses: [AnyClass] = []) {
        super.init()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        configuration.urlCredentialStorage = nil
        configuration.waitsForConnectivity = false
        if !protocolClasses.isEmpty {
            configuration.protocolClasses = protocolClasses
        }
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        session = URLSession(configuration: configuration, delegate: self, delegateQueue: queue)
    }

    func start(request: HttpRequest, callback: HttpCallback) -> HttpCall {
        guard let url = URL(string: request.url), let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http", url.host != nil
        else {
            callback.onFailure(failure: HttpFailure(kind: .invalidRequest, message: "Invalid URL"))
            return FinishedCall()
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = request.method
        urlRequest.timeoutInterval = max(1, TimeInterval(request.timeoutMillis) / 1000)
        urlRequest.httpShouldHandleCookies = false
        urlRequest.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        for header in request.headers {
            urlRequest.addValue(header.value, forHTTPHeaderField: header.name)
        }
        if let body = request.body {
            urlRequest.httpBody = Data(body.utf8)
        }
        let task = session.dataTask(with: urlRequest)
        lock.withLock {
            pending[task.taskIdentifier] = Pending(callback: callback, limit: request.maxResponseBytes)
        }
        task.resume()
        return Call(task: task)
    }

    // The redirect response itself is delivered to the shared core, which decides what to do with it.
    func urlSession(
        _ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void
    ) {
        lock.withLock { pending[task.taskIdentifier]?.redirect = response }
        completionHandler(nil)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        let exceeded = lock.withLock { () -> Bool in
            guard let entry = pending[dataTask.taskIdentifier] else { return false }
            if Int64(entry.data.count) + Int64(data.count) > entry.limit {
                entry.tooLarge = true
                entry.data = Data()
                return true
            }
            entry.data.append(data)
            return false
        }
        if exceeded {
            dataTask.cancel()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let entry = lock.withLock({ pending.removeValue(forKey: task.taskIdentifier) }) else { return }
        if entry.tooLarge {
            entry.callback.onFailure(failure: HttpFailure(kind: .tooLarge, message: "Response too large"))
            return
        }
        if let error, entry.redirect == nil {
            entry.callback.onFailure(failure: Self.failure(for: error))
            return
        }
        guard let response = entry.redirect ?? (task.response as? HTTPURLResponse), let url = response.url else {
            entry.callback.onFailure(failure: HttpFailure(kind: .connection, message: "No HTTP response"))
            return
        }
        var fields: [String: String] = [:]
        for (name, value) in response.allHeaderFields {
            if let name = name as? String, let value = value as? String {
                fields[name] = value
            }
        }
        let cookies = HTTPCookie.cookies(withResponseHeaderFields: fields, for: url).map(Self.setCookie)
        let headers = fields
            .filter { $0.key.caseInsensitiveCompare("Set-Cookie") != .orderedSame }
            .map { HttpHeader(name: $0.key, value: $0.value) }
        entry.callback.onResponse(response: HttpResponse(
            statusCode: Int32(response.statusCode),
            headers: headers,
            setCookies: cookies,
            body: Self.text(entry.data, encodingName: response.textEncodingName)
        ))
    }

    private static func setCookie(_ cookie: HTTPCookie) -> HttpSetCookie {
        // Foundation marks a cookie that carried a Domain attribute with a leading dot;
        // without one the cookie belongs to the responding host only.
        let domain = cookie.domain.hasPrefix(".") ? String(cookie.domain.dropFirst()) : nil
        let expires = cookie.expiresDate.map { KotlinLong(value: Int64($0.timeIntervalSince1970 * 1000)) }
        return HttpSetCookie(
            name: cookie.name, value: cookie.value, domain: domain, path: cookie.path,
            expiresAtEpochMillis: expires, secure: cookie.isSecure
        )
    }

    private static func text(_ data: Data, encodingName: String?) -> String {
        if let encodingName {
            let cfEncoding = CFStringConvertIANACharSetNameToEncoding(encodingName as CFString)
            if cfEncoding != kCFStringEncodingInvalidId {
                let encoding = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(cfEncoding))
                if encoding != .utf8, let text = String(data: data, encoding: encoding) {
                    return text
                }
            }
        }
        return String(decoding: data, as: UTF8.self)
    }

    private static func failure(for error: Error) -> HttpFailure {
        let code = (error as NSError).domain == NSURLErrorDomain ? (error as NSError).code : 0
        let kind: HttpFailureKind = switch code {
        case NSURLErrorCancelled: .cancelled
        case NSURLErrorTimedOut: .timeout
        case NSURLErrorBadURL, NSURLErrorUnsupportedURL: .invalidRequest
        default: .connection
        }
        // The system description can contain the request URL; keep only the error code.
        return HttpFailure(kind: kind, message: "URLSession error \(code)")
    }
}
