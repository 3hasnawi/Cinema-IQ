import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Ephemeral, bounded catalogue requests. No cookies, credential storage, cache or URL logging.
public struct IPTVURLSessionTransport: IPTVTransport {
    public init() {}
    public func data(from url: URL) async throws -> Data {
        let request = IPTVRequest()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in request.start(url, continuation: continuation) }
        } onCancel: { request.cancel() }
    }
}

/// Delegate state is accessed only under the lock; completion/cancellation can arrive on different queues.
private final class IPTVRequest: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Data, Error>?
    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var data = Data()
    private var finished = false
    private var cancelled = false
    private let maximum = 20 * 1024 * 1024

    func start(_ url: URL, continuation: CheckedContinuation<Data, Error>) {
        lock.lock()
        if cancelled { lock.unlock(); continuation.resume(throwing: CancellationError()); return }
        self.continuation = continuation
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20; config.timeoutIntervalForResource = 45
        config.urlCache = nil; config.urlCredentialStorage = nil; config.httpCookieStorage = nil
        config.httpShouldSetCookies = false; config.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        self.session = session
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        request.setValue("okhttp/4.12.0", forHTTPHeaderField: "User-Agent")
        request.setValue("application/json, application/x-mpegURL, audio/x-mpegurl, text/plain, */*", forHTTPHeaderField: "Accept")
        let task = session.dataTask(with: request); self.task = task
        lock.unlock(); task.resume()
    }
    func cancel() {
        lock.lock(); cancelled = true; let pending = continuation != nil; lock.unlock()
        if pending { finish(.failure(CancellationError())) }
    }
    private func finish(_ result: Result<Data, Error>) {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        finished = true
        let continuation = continuation, session = session, task = task
        self.continuation = nil; self.session = nil; self.task = nil
        data = Data()
        lock.unlock()
        task?.cancel(); session?.invalidateAndCancel()
        continuation?.resume(with: result)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse else {
            completionHandler(.cancel); finish(.failure(IPTVError.invalidResponse)); return
        }
        guard (200..<300).contains(http.statusCode) else {
            completionHandler(.cancel); finish(.failure(IPTVError.http(http.statusCode))); return
        }
        guard response.expectedContentLength <= Int64(maximum) else {
            completionHandler(.cancel); finish(.failure(IPTVError.tooLarge)); return
        }
        completionHandler(.allow)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive chunk: Data) {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        if data.count + chunk.count > maximum { lock.unlock(); finish(.failure(IPTVError.tooLarge)); return }
        data.append(chunk); lock.unlock()
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error = error as? URLError {
            if error.code == .cancelled { finish(.failure(CancellationError())) }
            else { finish(.failure(IPTVError.network(error.code.rawValue))) }
        } else if error != nil { finish(.failure(IPTVError.invalidResponse)) }
        else {
            lock.lock(); let result = data; lock.unlock()
            finish(.success(result))
        }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        guard let from = response.url, let to = request.url, IPTVURLPolicy.sameOrigin(from, to),
              (try? IPTVURLPolicy.validate(to, allowHTTP: from.scheme == "http")) != nil else {
            completionHandler(nil); finish(.failure(IPTVError.redirected)); return
        }
        completionHandler(request)
    }
}
