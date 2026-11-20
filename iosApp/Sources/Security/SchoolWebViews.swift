import SwiftUI
@preconcurrency import PocketShared
import WebKit

/// Which pages the school web views may show, and when a login result may be trusted.
enum SchoolWebPolicy {
    static let host = "shcloud2.k12ea.gov.tw"
    static let loginURL = URL(string: "https://shcloud2.k12ea.gov.tw/CLHSTYC/Auth/Auth/CloudLogin")!
    static let homeURL = URL(string: "https://shcloud2.k12ea.gov.tw/CLHSTYC/ICampus/Home/Index2")!

    /// HTTPS on the school host and the default port, nothing else.
    static func isTrusted(_ url: URL?) -> Bool {
        guard let url, url.scheme?.lowercased() == "https", url.host?.lowercased() == host,
              url.user == nil, url.password == nil
        else { return false }
        return url.port == nil || url.port == 443
    }

    static func isLoginPage(_ url: URL?) -> Bool {
        guard isTrusted(url), let url else { return false }
        return url.path.contains("/CLHSTYC/Auth/Auth/CloudLogin")
    }

    /// Every page must be a school page. A frame may also be the empty `about:blank` document
    /// the school pages create before filling it; `about:srcdoc` and other hosts are refused.
    static func allowsNavigation(to url: URL?, isMainFrame: Bool) -> Bool {
        if isTrusted(url) { return true }
        return !isMainFrame && url?.absoluteString.lowercased() == "about:blank"
    }

    /// Content rules for both web views: nothing loads over plain HTTP, and requests to other
    /// sites carry no cookies.
    static let contentRules = """
    [
      {"trigger": {"url-filter": "^http://"}, "action": {"type": "block"}},
      {"trigger": {"url-filter": ".*", "load-type": ["third-party"]}, "action": {"type": "block-cookies"}}
    ]
    """

    /// Cookies handed to the shared core: only those the school host would receive over HTTPS.
    static func schoolCookies(from cookies: [HTTPCookie]) -> [String: String] {
        var result: [String: String] = [:]
        for cookie in cookies where !cookie.name.isEmpty {
            let domain = cookie.domain.lowercased()
            let matches = domain.hasPrefix(".")
                ? host == String(domain.dropFirst()) || host.hasSuffix(domain)
                : host == domain
            if matches { result[cookie.name] = cookie.value }
        }
        return result
    }

    /// The student number reported by the login page; rejects anything that is not a plain identifier.
    static func studentNumber(fromMessage body: Any) -> String? {
        guard let text = body as? String else { return nil }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (1...32).contains(trimmed.count),
              trimmed.unicodeScalars.allSatisfy({ CharacterSet.alphanumerics.contains($0) || $0 == "-" || $0 == "_" }),
              trimmed.allSatisfy(\.isASCII)
        else { return nil }
        return trimmed
    }

    /// Reports a successful `DoCloudLoginCheck` response to the app. It passes on the account name
    /// the user typed and never reads the password field or the response beyond the success flag.
    static let loginHook = """
    (function() {
        if (window.__pocketLoginHooked) return;
        window.__pocketLoginHooked = true;
        var origOpen = XMLHttpRequest.prototype.open;
        var origSend = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.open = function(method, url) {
            this.__pocketUrl = url;
            return origOpen.apply(this, arguments);
        };
        XMLHttpRequest.prototype.send = function(body) {
            var self = this;
            if (self.__pocketUrl && String(self.__pocketUrl).indexOf('DoCloudLoginCheck') >= 0) {
                var loginId = '';
                try {
                    var field = document.querySelector('input[name="LoginId"]');
                    if (field) loginId = field.value || '';
                } catch (e) {}
                if (!loginId && typeof body === 'string') {
                    try { loginId = new URLSearchParams(body).get('LoginId') || ''; } catch (e) {}
                }
                self.addEventListener('load', function() {
                    try {
                        var resp = JSON.parse(self.responseText);
                        if (resp && resp.Result && resp.Result.IsLoginSuccess === true) {
                            window.webkit.messageHandlers.pocketLogin.postMessage(loginId);
                        }
                    } catch (e) {}
                });
            }
            return origSend.apply(this, arguments);
        };
    })();
    """
}

/// Page state the surrounding SwiftUI chrome shows.
struct SchoolWebPageState: Equatable {
    var title = ""
    var isLoading = true
    var progress = 0.0
    /// The page could not be loaded (no connection, or the school system is down).
    var loadFailed = false
}

/// School login in a web view, where the student types the account, password and captcha directly
/// into the school's own page. The app never sees the password.
///
/// The web view has its own non-persistent data store that is cleared when the view goes away. Once
/// the school page reports a successful login, `onLogin` receives the account name and the school
/// cookies; pass them to `PocketBackend.login(studentNo:cookies:)`, which verifies them against the
/// school system before anything is stored.
struct SchoolLoginWebView: UIViewRepresentable {
    @Binding var page: SchoolWebPageState
    /// Incremented by the parent to reload the page.
    var reloadToken = 0
    /// Incremented by the parent when verifying a reported login failed, so the student can sign
    /// in again on the same page.
    var loginResetToken = 0
    let onLogin: @MainActor (_ studentNo: String, _ cookies: [String: String]) -> Void

    func makeCoordinator() -> SchoolWebCoordinator {
        SchoolWebCoordinator(page: $page, onLogin: onLogin, onAuthenticationExpired: nil)
    }

    func makeUIView(context: Context) -> WKWebView {
        let coordinator = context.coordinator
        let webView = coordinator.makeWebView(installLoginHook: true)
        coordinator.loadTask = Task { @MainActor in
            guard await coordinator.installContentRules(in: webView), !Task.isCancelled else { return }
            webView.load(URLRequest(url: SchoolWebPolicy.loginURL, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData))
        }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.reloadIfNeeded(webView, token: reloadToken)
        context.coordinator.loginAttemptEnded(token: loginResetToken)
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: SchoolWebCoordinator) {
        coordinator.tearDown(webView)
    }
}

/// The school site for the signed-in student. It is given only the unlocked in-memory session and
/// reports when the school sends the student back to the login page.
struct SchoolSiteWebView: UIViewRepresentable {
    let cookies: [String: String]
    @Binding var page: SchoolWebPageState
    var reloadToken = 0
    let onAuthenticationExpired: @MainActor () -> Void

    func makeCoordinator() -> SchoolWebCoordinator {
        SchoolWebCoordinator(page: $page, onLogin: nil, onAuthenticationExpired: onAuthenticationExpired)
    }

    func makeUIView(context: Context) -> WKWebView {
        let webView = context.coordinator.makeWebView(installLoginHook: false)
        let store = webView.configuration.websiteDataStore.httpCookieStore
        let cookies = cookies.compactMap { name, value in
            HTTPCookie(properties: [
                .name: name, .value: value, .domain: SchoolWebPolicy.host, .path: "/", .secure: "TRUE",
            ])
        }
        let coordinator = context.coordinator
        coordinator.loadTask = Task { @MainActor in
            guard await coordinator.installContentRules(in: webView) else { return }
            for cookie in cookies {
                await store.setCookie(cookie)
            }
            // The view may have been torn down while the cookies were being stored.
            guard !Task.isCancelled else { return }
            webView.load(URLRequest(url: SchoolWebPolicy.homeURL, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData))
        }
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.reloadIfNeeded(webView, token: reloadToken)
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: SchoolWebCoordinator) {
        coordinator.tearDown(webView)
    }
}

@MainActor
final class SchoolWebCoordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
    private static let messageName = "pocketLogin"

    private let page: Binding<SchoolWebPageState>
    private let onLogin: (@MainActor (String, [String: String]) -> Void)?
    private let onAuthenticationExpired: (@MainActor () -> Void)?
    private var observations: [NSKeyValueObservation] = []
    private var loginHandled = false
    private var loginResetToken = 0
    private var rulesInstalled = false
    /// JavaScript dialogs WebKit is still waiting on.
    private var pendingDialogs: [DialogReply] = []
    /// Loads the first page once its cookies are in place; cancelled when the view goes away.
    var loadTask: Task<Void, Never>?
    private var reloadToken = 0

    init(
        page: Binding<SchoolWebPageState>,
        onLogin: (@MainActor (String, [String: String]) -> Void)?,
        onAuthenticationExpired: (@MainActor () -> Void)?
    ) {
        self.page = page
        self.onLogin = onLogin
        self.onAuthenticationExpired = onAuthenticationExpired
    }

    func makeWebView(installLoginHook: Bool) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.upgradeKnownHostsToHTTPS = true
        if installLoginHook {
            configuration.userContentController.addUserScript(WKUserScript(
                source: SchoolWebPolicy.loginHook, injectionTime: .atDocumentStart, forMainFrameOnly: true
            ))
            configuration.userContentController.add(WeakMessageHandler(self), name: Self.messageName)
        }
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsLinkPreview = false
        webView.allowsBackForwardNavigationGestures = true
        observations = [
            webView.observe(\.title) { [weak self] view, _ in
                Task { @MainActor in self?.page.wrappedValue.title = (view.title ?? "").trimmingCharacters(in: .whitespaces) }
            },
            webView.observe(\.estimatedProgress) { [weak self] view, _ in
                Task { @MainActor in self?.page.wrappedValue.progress = view.estimatedProgress }
            },
            webView.observe(\.isLoading) { [weak self] view, _ in
                Task { @MainActor in self?.page.wrappedValue.isLoading = view.isLoading }
            },
        ]
        return webView
    }

    /// Compiles and attaches `SchoolWebPolicy.contentRules`. No page is loaded without them.
    func installContentRules(in webView: WKWebView) async -> Bool {
        let list = try? await WKContentRuleListStore.default().compileContentRuleList(
            forIdentifier: "school-web", encodedContentRuleList: SchoolWebPolicy.contentRules
        )
        guard let list else {
            page.wrappedValue.loadFailed = true
            return false
        }
        webView.configuration.userContentController.add(list)
        rulesInstalled = true
        return true
    }

    /// The parent reports that the last login could not be verified.
    func loginAttemptEnded(token: Int) {
        guard token != loginResetToken else { return }
        loginResetToken = token
        loginHandled = false
    }

    func reloadIfNeeded(_ webView: WKWebView, token: Int) {
        guard token != reloadToken else { return }
        reloadToken = token
        guard rulesInstalled else {
            page.wrappedValue.loadFailed = false
            loadTask?.cancel()
            loadTask = Task { @MainActor in
                guard await installContentRules(in: webView), !Task.isCancelled else { return }
                webView.load(URLRequest(url: onLogin == nil ? SchoolWebPolicy.homeURL : SchoolWebPolicy.loginURL))
            }
            return
        }
        if webView.url == nil {
            webView.load(URLRequest(url: onLogin == nil ? SchoolWebPolicy.homeURL : SchoolWebPolicy.loginURL))
        } else {
            webView.reload()
        }
    }

    /// Stops the page and removes everything the school site stored in this web view.
    func tearDown(_ webView: WKWebView) {
        observations = []
        // WebKit raises an exception when a dialog's completion handler is never called.
        pendingDialogs.forEach { $0.answer(false) }
        pendingDialogs = []
        loadTask?.cancel()
        loadTask = nil
        webView.stopLoading()
        webView.navigationDelegate = nil
        webView.uiDelegate = nil
        webView.configuration.userContentController.removeAllScriptMessageHandlers()
        webView.configuration.userContentController.removeAllUserScripts()
        webView.configuration.userContentController.removeAllContentRuleLists()
        let store = webView.configuration.websiteDataStore
        store.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast) {}
    }

    // Navigation

    func webView(
        _ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void
    ) {
        let isMainFrame = navigationAction.targetFrame?.isMainFrame ?? true
        guard navigationAction.targetFrame != nil,
              SchoolWebPolicy.allowsNavigation(to: navigationAction.request.url, isMainFrame: isMainFrame)
        else {
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    func webView(
        _ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse,
        decisionHandler: @escaping @MainActor (WKNavigationResponsePolicy) -> Void
    ) {
        // Downloads and non-renderable responses are not part of the login or the site view.
        decisionHandler(navigationResponse.canShowMIMEType ? .allow : .cancel)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        // A navigation the policy refused, or one replaced by a newer one, is not a connection problem.
        let code = (error as NSError).code
        if code == NSURLErrorCancelled || code == 102 { return }
        page.wrappedValue.loadFailed = true
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        loginHandled = false
        page.wrappedValue.loadFailed = false
        if SchoolWebPolicy.isLoginPage(webView.url) {
            onAuthenticationExpired?()
        }
    }

    func webView(_ webView: WKWebView, didReceiveServerRedirectForProvisionalNavigation navigation: WKNavigation!) {
        if SchoolWebPolicy.isLoginPage(webView.url) {
            onAuthenticationExpired?()
        }
    }

    // Login result

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == Self.messageName, !loginHandled, let onLogin,
              message.frameInfo.isMainFrame,
              message.frameInfo.securityOrigin.protocol == "https",
              message.frameInfo.securityOrigin.host.lowercased() == SchoolWebPolicy.host,
              let webView = message.webView, SchoolWebPolicy.isLoginPage(webView.url),
              let studentNo = SchoolWebPolicy.studentNumber(fromMessage: message.body)
        else { return }
        // One report per attempt: the page can fire the same success twice in a row. The school's
        // check is an XHR, so no navigation would ever allow a second attempt after a failed
        // verification; the parent's `loginResetToken` or the short window below does.
        loginHandled = true
        webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { [weak self] cookies in
            Task { @MainActor in
                onLogin(studentNo, SchoolWebPolicy.schoolCookies(from: cookies))
                try? await Task.sleep(for: .seconds(2))
                self?.loginHandled = false
            }
        }
    }

    // The school pages report wrong passwords and captcha errors with alert(); without these the
    // messages would be dropped silently.

    func webView(
        _ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
        initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping @MainActor () -> Void
    ) {
        present(on: webView, message: message, actions: [("好", true)]) { _ in completionHandler() }
    }

    func webView(
        _ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
        initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping @MainActor (Bool) -> Void
    ) {
        present(on: webView, message: message, actions: [("取消", false), ("確定", true)], completion: completionHandler)
    }

    // window.open and target=_blank are refused rather than opened in a second web view.
    func webView(
        _ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        nil
    }

    private func present(
        on webView: WKWebView, message: String, actions: [(String, Bool)], completion: @escaping @MainActor (Bool) -> Void
    ) {
        let reply = DialogReply(completion)
        var presenter = webView.window?.rootViewController
        while let presented = presenter?.presentedViewController, !presented.isBeingDismissed {
            presenter = presented
        }
        // A presenter that is leaving or not on screen would drop the alert without ever answering.
        guard let presenter, !presenter.isBeingDismissed, presenter.viewIfLoaded?.window != nil else {
            reply.answer(false)
            return
        }
        pendingDialogs.append(reply)
        let alert = UIAlertController(title: nil, message: String(message.prefix(500)), preferredStyle: .alert)
        for (title, value) in actions {
            alert.addAction(UIAlertAction(title: title, style: value ? .default : .cancel) { [weak self] _ in
                reply.answer(value)
                self?.pendingDialogs.removeAll { $0 === reply }
            })
        }
        presenter.present(alert, animated: true) { [weak self, weak alert] in
            // UIKit can refuse the presentation without telling anyone.
            guard alert?.presentingViewController == nil else { return }
            reply.answer(false)
            self?.pendingDialogs.removeAll { $0 === reply }
        }
    }
}

/// Answers a WebKit dialog exactly once, however many paths try.
@MainActor
final class DialogReply {
    private var completion: (@MainActor (Bool) -> Void)?

    init(_ completion: @escaping @MainActor (Bool) -> Void) {
        self.completion = completion
    }

    func answer(_ value: Bool) {
        let completion = completion
        self.completion = nil
        completion?(value)
    }
}

/// `WKUserContentController` retains its handlers; this keeps the coordinator from leaking.
private final class WeakMessageHandler: NSObject, WKScriptMessageHandler {
    private weak var target: SchoolWebCoordinator?

    init(_ target: SchoolWebCoordinator) {
        self.target = target
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        MainActor.assumeIsolated {
            target?.userContentController(userContentController, didReceive: message)
        }
    }
}

/// Removes web data on logout. The school web views keep nothing on disk (their stores are
/// non-persistent and cleared when they close); this also empties the default store and the shared
/// cookie storage so that no school cookie can survive anywhere in the app's web state.
final class SchoolWebDataCleaner: NSObject, WebDataCleaner, @unchecked Sendable {
    func clear(completion: @escaping (KotlinBoolean) -> Void) {
        nonisolated(unsafe) let completion = completion
        DispatchQueue.main.async {
            HTTPCookieStorage.shared.removeCookies(since: .distantPast)
            WKWebsiteDataStore.default().removeData(
                ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast
            ) {
                completion(true)
            }
        }
    }
}

/// Login screen: the school page inside Pocket chrome, with the verification state on top.
struct SchoolLoginScreen: View {
    /// True while `PocketBackend.login` verifies the captured cookies.
    let isVerifying: Bool
    /// zh-TW failure text from `PocketError.message`.
    let errorMessage: String?
    let onLogin: @MainActor (_ studentNo: String, _ cookies: [String: String]) -> Void
    let onClose: () -> Void
    let onDismissError: () -> Void

    @State private var page = SchoolWebPageState()
    @State private var reloadToken = 0

    var body: some View {
        VStack(spacing: 0) {
            SchoolWebHeader(page: page, onClose: onClose) { reloadToken += 1 }
            ZStack(alignment: .top) {
                SchoolLoginWebView(page: $page, reloadToken: reloadToken, onLogin: onLogin)
                    .ignoresSafeArea(.container, edges: .bottom)
                if page.loadFailed {
                    SchoolWebLoadFailure { reloadToken += 1 }
                }
                if page.isLoading || isVerifying {
                    ProgressView(value: isVerifying ? nil : page.progress)
                        .progressViewStyle(.linear)
                        .tint(PocketColor.primary)
                }
                if isVerifying {
                    ZStack {
                        Color.black.opacity(0.38).ignoresSafeArea()
                        VStack(spacing: 16) {
                            ProgressView().controlSize(.large)
                            Text("正在驗證登入…")
                                .font(.body)
                                .foregroundStyle(PocketColor.onSurface)
                        }
                        .padding(32)
                        .background(PocketColor.surfaceHigh, in: RoundedRectangle(cornerRadius: PocketMetrics.cardRadius))
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(.isModal)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(PocketColor.background.ignoresSafeArea())
        .overlay(alignment: .bottom) {
            if let errorMessage {
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(errorMessage)
                        .font(.callout)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button("關閉", action: onDismissError)
                        .font(.callout.weight(.semibold))
                }
                .foregroundStyle(PocketColor.onSurface)
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
                .background(PocketColor.surfaceHigh, in: RoundedRectangle(cornerRadius: PocketMetrics.innerRadius))
                .overlay(
                    RoundedRectangle(cornerRadius: PocketMetrics.innerRadius).stroke(PocketColor.negative.opacity(0.6), lineWidth: 1)
                )
                .padding(16)
                .accessibilityIdentifier("login-error")
            }
        }
    }
}

/// Shown over the web view when the school page cannot be reached.
struct SchoolWebLoadFailure: View {
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "wifi.exclamationmark")
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(PocketColor.onSecondaryContainer)
                .frame(width: 72, height: 72)
                .background(PocketColor.secondaryContainer, in: Circle())
                .accessibilityHidden(true)
            Text("無法連線到學校系統")
                .font(PocketFont.sectionTitle)
                .foregroundStyle(PocketColor.onSurface)
            Text("請確認網路連線，或稍後再試。學校系統在維護時也會暫時無法登入。")
                .font(.callout)
                .foregroundStyle(PocketColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
            Button(action: onRetry) {
                Label("重新載入", systemImage: "arrow.clockwise")
                    .font(.headline)
                    .foregroundStyle(PocketColor.onPrimary)
                    .padding(.horizontal, 28)
                    .frame(minHeight: 52)
                    .background(PocketColor.primary, in: Capsule())
            }
            .buttonStyle(.plain)
            .padding(.top, 4)
            .accessibilityIdentifier("login-retry")
        }
        .padding(.horizontal, 32)
        .frame(maxWidth: 420)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PocketColor.background)
    }
}

/// Title, host and controls above a school web view. The host is always visible so the student can
/// see which site receives the password.
struct SchoolWebHeader: View {
    let page: SchoolWebPageState
    let onClose: () -> Void
    let onReload: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.body.weight(.semibold))
                    .frame(width: 44, height: 44)
                    .background(PocketColor.surfaceHigh, in: Circle())
            }
            .accessibilityLabel("關閉")
            VStack(alignment: .leading, spacing: 2) {
                Text(page.title.isEmpty ? (page.isLoading ? "正在載入…" : "學校系統登入") : page.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(PocketColor.onSurface)
                    .lineLimit(1)
                Label(SchoolWebPolicy.host, systemImage: "lock.fill")
                    .font(.caption)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: onReload) {
                Image(systemName: "arrow.clockwise")
                    .font(.body.weight(.semibold))
                    .frame(width: 44, height: 44)
                    .background(PocketColor.surfaceHigh, in: Circle())
            }
            .accessibilityLabel("重新載入")
        }
        .foregroundStyle(PocketColor.onSurface)
        .buttonStyle(.plain)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(PocketColor.surface)
    }
}
