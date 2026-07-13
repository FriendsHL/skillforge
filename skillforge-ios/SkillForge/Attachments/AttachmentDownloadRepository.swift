import CryptoKit
import Foundation

enum AttachmentDownloadError: Error, Equatable, LocalizedError {
    case unauthorized
    case unavailable
    case invalidResponse
    case invalidIdentifier
    case httpStatus(Int)

    var errorDescription: String? {
        switch self {
        case .unauthorized: "This device is no longer authorized."
        case .unavailable: "This attachment is unavailable."
        case .invalidResponse: "The server returned an invalid download response."
        case .invalidIdentifier: "The attachment identifier is invalid."
        case let .httpStatus(status): "Download failed with HTTP \(status)."
        }
    }
}

protocol AttachmentDownloading: Sendable {
    func download(sessionID: String, attachment: ChatAttachment) async throws -> URL
    func clearCache() async throws
}

actor AttachmentDownloadRepository: AttachmentDownloading {
    private struct Metadata: Codable {
        let etag: String
    }

    private let endpoint: URL
    private let deviceToken: String
    private let session: URLSession
    private let fileManager: FileManager
    private let namespaceURL: URL

    init(
        endpoint: URL,
        deviceID: String,
        deviceToken: String,
        session: URLSession = .shared,
        cacheRoot: URL? = nil,
        fileManager: FileManager = .default
    ) {
        self.endpoint = endpoint
        self.deviceToken = deviceToken
        self.session = session
        self.fileManager = fileManager
        let root = cacheRoot ?? fileManager.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appending(path: "SkillForgeAttachments", directoryHint: .isDirectory)
        let namespace = Self.digest("\(endpoint.absoluteString)|\(deviceID)")
        namespaceURL = root.appending(path: namespace, directoryHint: .isDirectory)
    }

    func download(sessionID: String, attachment: ChatAttachment) async throws -> URL {
        try Task.checkCancellation()
        guard Self.isValidPathComponent(sessionID), Self.isValidPathComponent(attachment.id) else {
            throw AttachmentDownloadError.invalidIdentifier
        }
        try createNamespaceIfNeeded()

        let fileURL = cachedFileURL(for: attachment)
        let metadataURL = fileURL.appendingPathExtension("metadata")
        let etag = cachedETag(at: metadataURL, fileExists: fileManager.fileExists(atPath: fileURL.path))
        var request = URLRequest(url: downloadURL(sessionID: sessionID, attachmentID: attachment.id))
        request.httpMethod = "GET"
        request.setValue("*/*", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(deviceToken)", forHTTPHeaderField: "Authorization")
        if let etag {
            request.setValue(etag, forHTTPHeaderField: "If-None-Match")
        }

        let temporaryURL: URL
        let response: URLResponse
        do {
            (temporaryURL, response) = try await session.download(for: request)
        } catch {
            if Task.isCancelled {
                throw CancellationError()
            }
            throw error
        }
        try Task.checkCancellation()
        guard let http = response as? HTTPURLResponse else {
            throw AttachmentDownloadError.invalidResponse
        }

        switch http.statusCode {
        case 200..<300:
            try replaceCachedFile(at: fileURL, with: temporaryURL)
            if let responseETag = http.value(forHTTPHeaderField: "ETag"), !responseETag.isEmpty {
                try writeMetadata(Metadata(etag: responseETag), to: metadataURL)
            } else {
                try? fileManager.removeItem(at: metadataURL)
            }
            return fileURL
        case 304 where fileManager.fileExists(atPath: fileURL.path):
            return fileURL
        case 401:
            try? await clearCache()
            throw AttachmentDownloadError.unauthorized
        case 404:
            throw AttachmentDownloadError.unavailable
        default:
            throw AttachmentDownloadError.httpStatus(http.statusCode)
        }
    }

    func clearCache() async throws {
        guard fileManager.fileExists(atPath: namespaceURL.path) else { return }
        try fileManager.removeItem(at: namespaceURL)
    }

    private func downloadURL(sessionID: String, attachmentID: String) -> URL {
        endpoint
            .appending(path: "api")
            .appending(path: "mobile")
            .appending(path: "client")
            .appending(path: "sessions")
            .appending(path: sessionID)
            .appending(path: "attachments")
            .appending(path: attachmentID)
            .appending(path: "data")
    }

    private func createNamespaceIfNeeded() throws {
        try fileManager.createDirectory(
            at: namespaceURL,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        try excludeFromBackup(namespaceURL)
    }

    private func cachedFileURL(for attachment: ChatAttachment) -> URL {
        let basename = Self.digest(attachment.id)
        let rawExtension = (attachment.filename as NSString).pathExtension.lowercased()
        let allowed = rawExtension.unicodeScalars.allSatisfy {
            CharacterSet.alphanumerics.contains($0)
        }
        let fileExtension = allowed && !rawExtension.isEmpty && rawExtension.count <= 12 ? ".\(rawExtension)" : ""
        return namespaceURL.appending(path: basename + fileExtension)
    }

    private func cachedETag(at metadataURL: URL, fileExists: Bool) -> String? {
        guard fileExists,
              let data = try? Data(contentsOf: metadataURL),
              let metadata = try? JSONDecoder().decode(Metadata.self, from: data)
        else { return nil }
        return metadata.etag
    }

    private func replaceCachedFile(at destination: URL, with temporaryURL: URL) throws {
        if fileManager.fileExists(atPath: destination.path) {
            try fileManager.removeItem(at: destination)
        }
        try fileManager.moveItem(at: temporaryURL, to: destination)
        try protect(destination)
    }

    private func writeMetadata(_ metadata: Metadata, to url: URL) throws {
        try JSONEncoder().encode(metadata).write(to: url, options: .atomic)
        try protect(url)
    }

    private func protect(_ url: URL) throws {
        try fileManager.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: url.path
        )
        try excludeFromBackup(url)
    }

    private func excludeFromBackup(_ url: URL) throws {
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableURL = url
        try mutableURL.setResourceValues(values)
    }

    private static func digest(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private static func isValidPathComponent(_ value: String) -> Bool {
        guard !value.isEmpty, value != ".", value != "..", value.utf8.count <= 128 else { return false }
        return value.unicodeScalars.allSatisfy {
            CharacterSet.alphanumerics.contains($0) || "-_.".unicodeScalars.contains($0)
        }
    }
}
