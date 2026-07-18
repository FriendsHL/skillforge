import CryptoKit
import Foundation

enum AttachmentDownloadError: Error, Equatable, LocalizedError {
    case unauthorized
    case forbidden
    case unavailable
    case invalidResponse
    case invalidIdentifier
    case httpStatus(Int)

    var errorDescription: String? {
        switch self {
        case .unauthorized: "This device is no longer authorized."
        case .forbidden: "This attachment is no longer permitted."
        case .unavailable: "This attachment is unavailable."
        case .invalidResponse: "The server returned an invalid download response."
        case .invalidIdentifier: "The attachment identifier is invalid."
        case let .httpStatus(status): "Download failed with HTTP \(status)."
        }
    }
}

protocol AttachmentDownloading: Sendable {
    func download(sessionID: String, attachment: ChatAttachment) async throws -> URL
    func prepareInteractiveArtifact(sessionID: String, attachment: ChatAttachment) async throws -> URL
    func interactiveManifest(sessionID: String, attachmentID: String) async throws -> InteractiveArtifactManifest
    func downloadedPersonalAppIDs() async throws -> Set<String>
    func localPersonalApps() async throws -> [PersonalAppLocalRecord]
    func recordPersonalAppMetadata(_ app: MobilePersonalApp) async throws
    func recordPersonalAppLocalMetadata(_ record: PersonalAppLocalRecord) async throws
    func clearInteractiveArtifact(sessionID: String, attachment: ChatAttachment) async throws
    func clearCache() async throws
}

extension AttachmentDownloading {
    func prepareInteractiveArtifact(sessionID: String, attachment: ChatAttachment) async throws -> URL {
        try await download(sessionID: sessionID, attachment: attachment)
    }

    func interactiveManifest(sessionID: String, attachmentID: String) async throws -> InteractiveArtifactManifest {
        throw AttachmentDownloadError.unavailable
    }

    func downloadedPersonalAppIDs() async throws -> Set<String> { [] }

    func localPersonalApps() async throws -> [PersonalAppLocalRecord] { [] }

    func recordPersonalAppMetadata(_ app: MobilePersonalApp) async throws {}

    func recordPersonalAppLocalMetadata(_ record: PersonalAppLocalRecord) async throws {}

    func clearInteractiveArtifact(sessionID: String, attachment: ChatAttachment) async throws {}
}

struct InteractiveArtifactManifest: Decodable, Equatable, Sendable {
    let schemaVersion: Int
    let title: String
    let fallback: String
    let permissions: [String]
    let network: [String]
    let initialData: [String: MobileJSONValue]
    let stateSchema: [String: MobileJSONValue]
}

actor AttachmentDownloadRepository: AttachmentDownloading {
    private struct Metadata: Codable {
        let etag: String
    }

    private struct PersonalAppIndex: Codable {
        let version: Int
        var entries: [String: PersonalAppCacheEntry]

        static let empty = PersonalAppIndex(version: 1, entries: [:])
    }

    private struct PersonalAppCacheEntry: Codable {
        let artifactId: String
        let sessionId: String
        let htmlFileName: String
        let htmlSHA256: String
        let manifestFileName: String
        let manifestSHA256: String
        var metadata: PersonalAppLocalRecord?
    }

    private struct VerifiedBundle {
        let entry: PersonalAppCacheEntry
        let htmlURL: URL
        let manifest: InteractiveArtifactManifest
    }

    private struct PreparedHTML {
        let finalURL: URL
        let temporaryURL: URL?
        let sha256: String
        let etag: String
    }

    private struct InteractiveOperationEpoch {
        let namespace: UInt64
        let artifact: UInt64
    }

    private let endpoint: URL
    private let deviceToken: String
    private let session: URLSession
    private let fileManager: FileManager
    private let namespaceURL: URL
    private var namespaceEpoch: UInt64 = 0
    private var artifactEpochs: [String: UInt64] = [:]

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
        let namespace = Self.digest(Data("\(endpoint.absoluteString)|\(deviceID)".utf8))
        namespaceURL = root.appending(path: namespace, directoryHint: .isDirectory)
    }

    func download(sessionID: String, attachment: ChatAttachment) async throws -> URL {
        if attachment.kind == .interactive {
            return try await prepareInteractiveArtifact(sessionID: sessionID, attachment: attachment)
        }
        try Task.checkCancellation()
        try validateIdentifiers(sessionID: sessionID, attachmentID: attachment.id)
        try createNamespaceIfNeeded()

        let fileURL = cachedFileURL(for: attachment)
        let metadataURL = fileURL.appendingPathExtension("metadata")
        let etag = cachedETag(at: metadataURL, fileExists: fileManager.fileExists(atPath: fileURL.path))
        var request = authenticatedRequest(
            url: downloadURL(sessionID: sessionID, attachmentID: attachment.id),
            accept: "*/*"
        )
        if let etag {
            request.setValue(etag, forHTTPHeaderField: "If-None-Match")
        }

        let temporaryURL: URL
        let response: URLResponse
        do {
            (temporaryURL, response) = try await session.download(for: request)
        } catch {
            if Task.isCancelled { throw CancellationError() }
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
        case 403:
            throw AttachmentDownloadError.forbidden
        case 404:
            throw AttachmentDownloadError.unavailable
        default:
            throw AttachmentDownloadError.httpStatus(http.statusCode)
        }
    }

    func prepareInteractiveArtifact(
        sessionID: String,
        attachment: ChatAttachment
    ) async throws -> URL {
        try Task.checkCancellation()
        guard attachment.kind == .interactive else {
            throw AttachmentDownloadError.invalidResponse
        }
        try validateIdentifiers(sessionID: sessionID, attachmentID: attachment.id)
        try createNamespaceIfNeeded()
        let operationEpoch = beginInteractiveOperationEpoch(artifactID: attachment.id)

        let cached = try verifiedBundle(artifactID: attachment.id, sessionID: sessionID)
        let preparedHTML: PreparedHTML
        do {
            preparedHTML = try await fetchInteractiveHTML(
                sessionID: sessionID,
                attachment: attachment,
                cached: cached
            )
            try validateInteractiveOperationEpoch(
                operationEpoch,
                artifactID: attachment.id,
                temporaryURL: preparedHTML.temporaryURL
            )
        } catch let error as AttachmentDownloadError {
            if isExplicitRevoke(error) {
                try removeInteractiveArtifact(artifactID: attachment.id, attachment: attachment)
                throw error
            }
            if isTransient(error), let cached {
                try validateInteractiveOperationEpoch(operationEpoch, artifactID: attachment.id)
                return cached.htmlURL
            }
            throw error
        } catch {
            if Task.isCancelled { throw CancellationError() }
            if Self.isTransportFailure(error), let cached {
                try validateInteractiveOperationEpoch(operationEpoch, artifactID: attachment.id)
                return cached.htmlURL
            }
            throw error
        }

        let manifestData: Data
        let manifest: InteractiveArtifactManifest
        do {
            (manifestData, manifest) = try await fetchInteractiveManifest(
                sessionID: sessionID,
                attachmentID: attachment.id
            )
            try validateInteractiveOperationEpoch(
                operationEpoch,
                artifactID: attachment.id,
                temporaryURL: preparedHTML.temporaryURL
            )
        } catch let error as AttachmentDownloadError {
            if let temporaryURL = preparedHTML.temporaryURL {
                try? fileManager.removeItem(at: temporaryURL)
            }
            if isExplicitRevoke(error) {
                try removeInteractiveArtifact(artifactID: attachment.id, attachment: attachment)
                throw error
            }
            if isTransient(error), let cached {
                try validateInteractiveOperationEpoch(operationEpoch, artifactID: attachment.id)
                return cached.htmlURL
            }
            throw error
        } catch {
            if let temporaryURL = preparedHTML.temporaryURL {
                try? fileManager.removeItem(at: temporaryURL)
            }
            if Task.isCancelled { throw CancellationError() }
            if Self.isTransportFailure(error), let cached {
                try validateInteractiveOperationEpoch(operationEpoch, artifactID: attachment.id)
                return cached.htmlURL
            }
            throw error
        }

        try Task.checkCancellation()
        try validateInteractiveOperationEpoch(
            operationEpoch,
            artifactID: attachment.id,
            temporaryURL: preparedHTML.temporaryURL
        )
        if let temporaryURL = preparedHTML.temporaryURL {
            try replaceCachedFile(at: preparedHTML.finalURL, with: temporaryURL)
        }
        let htmlMetadataURL = preparedHTML.finalURL.appendingPathExtension("metadata")
        try writeMetadata(Metadata(etag: preparedHTML.etag), to: htmlMetadataURL)

        let manifestURL = cachedManifestURL(artifactID: attachment.id)
        try manifestData.write(to: manifestURL, options: .atomic)
        try protect(manifestURL)

        var index = loadIndex()
        let existingMetadata = index.entries[attachment.id]?.metadata
        index.entries[attachment.id] = PersonalAppCacheEntry(
            artifactId: attachment.id,
            sessionId: sessionID,
            htmlFileName: preparedHTML.finalURL.lastPathComponent,
            htmlSHA256: preparedHTML.sha256,
            manifestFileName: manifestURL.lastPathComponent,
            manifestSHA256: Self.digest(manifestData),
            metadata: existingMetadata
        )
        try writeIndex(index)

        _ = manifest
        return preparedHTML.finalURL
    }

    func interactiveManifest(
        sessionID: String,
        attachmentID: String
    ) async throws -> InteractiveArtifactManifest {
        try validateIdentifiers(sessionID: sessionID, attachmentID: attachmentID)
        if let cached = try verifiedBundle(artifactID: attachmentID, sessionID: sessionID) {
            return cached.manifest
        }
        do {
            return try await fetchInteractiveManifest(
                sessionID: sessionID,
                attachmentID: attachmentID
            ).manifest
        } catch let error as AttachmentDownloadError {
            if error == .unauthorized {
                try? await clearCache()
            }
            throw error
        }
    }

    func downloadedPersonalAppIDs() async throws -> Set<String> {
        var index = loadIndex()
        var ids = Set<String>()
        for (artifactID, entry) in index.entries {
            if artifactID == entry.artifactId, try verifiedBundle(entry: entry) != nil {
                ids.insert(artifactID)
            } else {
                removeFiles(for: entry)
                index.entries[artifactID] = nil
            }
        }
        try writeIndex(index)
        return ids
    }

    func localPersonalApps() async throws -> [PersonalAppLocalRecord] {
        var index = loadIndex()
        var records: [PersonalAppLocalRecord] = []
        for (artifactID, entry) in index.entries {
            guard artifactID == entry.artifactId, try verifiedBundle(entry: entry) != nil else {
                removeFiles(for: entry)
                index.entries[artifactID] = nil
                continue
            }
            if let metadata = entry.metadata {
                records.append(metadata)
            }
        }
        try writeIndex(index)
        return records
    }

    func recordPersonalAppMetadata(_ app: MobilePersonalApp) async throws {
        try await recordPersonalAppLocalMetadata(PersonalAppLocalRecord(app: app))
    }

    func recordPersonalAppLocalMetadata(_ record: PersonalAppLocalRecord) async throws {
        var index = loadIndex()
        guard var entry = index.entries[record.artifactId],
              try verifiedBundle(entry: entry) != nil else { return }
        entry.metadata = record
        index.entries[record.artifactId] = entry
        try writeIndex(index)
    }

    func clearInteractiveArtifact(
        sessionID: String,
        attachment: ChatAttachment
    ) async throws {
        try validateIdentifiers(sessionID: sessionID, attachmentID: attachment.id)
        try removeInteractiveArtifact(artifactID: attachment.id, attachment: attachment)
    }

    func clearCache() async throws {
        namespaceEpoch &+= 1
        artifactEpochs.removeAll()
        guard fileManager.fileExists(atPath: namespaceURL.path) else { return }
        try fileManager.removeItem(at: namespaceURL)
    }

    private func fetchInteractiveHTML(
        sessionID: String,
        attachment: ChatAttachment,
        cached: VerifiedBundle?
    ) async throws -> PreparedHTML {
        let finalURL = cachedFileURL(for: attachment)
        let metadataURL = finalURL.appendingPathExtension("metadata")
        var request = authenticatedRequest(
            url: downloadURL(sessionID: sessionID, attachmentID: attachment.id),
            accept: "text/html"
        )
        if cached != nil, let etag = cachedETag(at: metadataURL, fileExists: true) {
            request.setValue(etag, forHTTPHeaderField: "If-None-Match")
        }

        let (temporaryURL, response) = try await session.download(for: request)
        try Task.checkCancellation()
        guard let http = response as? HTTPURLResponse else {
            try? fileManager.removeItem(at: temporaryURL)
            throw AttachmentDownloadError.invalidResponse
        }
        switch http.statusCode {
        case 200..<300:
            guard let etag = http.value(forHTTPHeaderField: "ETag"),
                  let expectedSHA = Self.sha256FromQuotedETag(etag),
                  let actualSHA = try? Self.digest(contentsOf: temporaryURL),
                  actualSHA == expectedSHA else {
                try? fileManager.removeItem(at: temporaryURL)
                throw AttachmentDownloadError.invalidResponse
            }
            return PreparedHTML(
                finalURL: finalURL,
                temporaryURL: temporaryURL,
                sha256: expectedSHA,
                etag: etag
            )
        case 304:
            try? fileManager.removeItem(at: temporaryURL)
            guard let cached else { throw AttachmentDownloadError.invalidResponse }
            return PreparedHTML(
                finalURL: cached.htmlURL,
                temporaryURL: nil,
                sha256: cached.entry.htmlSHA256,
                etag: "\"\(cached.entry.htmlSHA256)\""
            )
        case 401:
            try? fileManager.removeItem(at: temporaryURL)
            try? await clearCache()
            throw AttachmentDownloadError.unauthorized
        case 403:
            try? fileManager.removeItem(at: temporaryURL)
            throw AttachmentDownloadError.forbidden
        case 404:
            try? fileManager.removeItem(at: temporaryURL)
            throw AttachmentDownloadError.unavailable
        default:
            try? fileManager.removeItem(at: temporaryURL)
            throw AttachmentDownloadError.httpStatus(http.statusCode)
        }
    }

    private func fetchInteractiveManifest(
        sessionID: String,
        attachmentID: String
    ) async throws -> (data: Data, manifest: InteractiveArtifactManifest) {
        let request = authenticatedRequest(
            url: manifestURL(sessionID: sessionID, attachmentID: attachmentID),
            accept: "application/json"
        )
        let (data, response) = try await session.data(for: request)
        try Task.checkCancellation()
        guard let http = response as? HTTPURLResponse else {
            throw AttachmentDownloadError.invalidResponse
        }
        switch http.statusCode {
        case 200..<300:
            guard let manifest = try? JSONDecoder().decode(InteractiveArtifactManifest.self, from: data) else {
                throw AttachmentDownloadError.invalidResponse
            }
            try validate(manifest: manifest)
            return (data, manifest)
        case 401:
            try? await clearCache()
            throw AttachmentDownloadError.unauthorized
        case 403:
            throw AttachmentDownloadError.forbidden
        case 404:
            throw AttachmentDownloadError.unavailable
        default:
            throw AttachmentDownloadError.httpStatus(http.statusCode)
        }
    }

    private func verifiedBundle(
        artifactID: String,
        sessionID: String
    ) throws -> VerifiedBundle? {
        let index = loadIndex()
        guard let entry = index.entries[artifactID],
              entry.artifactId == artifactID,
              entry.sessionId == sessionID else { return nil }
        return try verifiedBundle(entry: entry)
    }

    private func verifiedBundle(entry: PersonalAppCacheEntry) throws -> VerifiedBundle? {
        guard Self.isValidPathComponent(entry.artifactId),
              Self.isValidPathComponent(entry.sessionId),
              Self.isSHA256(entry.htmlSHA256),
              Self.isSHA256(entry.manifestSHA256),
              entry.manifestFileName == cachedManifestURL(artifactID: entry.artifactId).lastPathComponent,
              (entry.htmlFileName as NSString).deletingPathExtension == Self.digest(Data(entry.artifactId.utf8))
        else { return nil }
        if let metadata = entry.metadata,
           (metadata.artifactId != entry.artifactId || metadata.sessionId != entry.sessionId) {
            return nil
        }
        guard let htmlURL = safeCacheURL(fileName: entry.htmlFileName),
              let manifestURL = safeCacheURL(fileName: entry.manifestFileName) else {
            return nil
        }
        guard fileManager.fileExists(atPath: htmlURL.path),
              fileManager.fileExists(atPath: manifestURL.path),
              (try? Self.digest(contentsOf: htmlURL)) == entry.htmlSHA256,
              let manifestData = try? Data(contentsOf: manifestURL),
              Self.digest(manifestData) == entry.manifestSHA256,
              let manifest = try? JSONDecoder().decode(InteractiveArtifactManifest.self, from: manifestData),
              (try? validate(manifest: manifest)) != nil else {
            return nil
        }
        return VerifiedBundle(entry: entry, htmlURL: htmlURL, manifest: manifest)
    }

    private func validate(manifest: InteractiveArtifactManifest) throws {
        guard manifest.schemaVersion == 1,
              manifest.permissions.isEmpty,
              manifest.network.isEmpty else {
            throw AttachmentDownloadError.invalidResponse
        }
    }

    private func loadIndex() -> PersonalAppIndex {
        let url = indexURL
        guard let data = try? Data(contentsOf: url),
              let index = try? JSONDecoder().decode(PersonalAppIndex.self, from: data),
              index.version == 1 else {
            return .empty
        }
        return index
    }

    private func writeIndex(_ index: PersonalAppIndex) throws {
        try createNamespaceIfNeeded()
        if index.entries.isEmpty {
            try? fileManager.removeItem(at: indexURL)
            return
        }
        try JSONEncoder().encode(index).write(to: indexURL, options: .atomic)
        try protect(indexURL)
    }

    private func removeInteractiveArtifact(
        artifactID: String,
        attachment: ChatAttachment
    ) throws {
        invalidateInteractiveOperations(artifactID: artifactID)
        var index = loadIndex()
        if let entry = index.entries.removeValue(forKey: artifactID) {
            removeFiles(for: entry)
        }
        let htmlURL = cachedFileURL(for: attachment)
        try? fileManager.removeItem(at: htmlURL)
        try? fileManager.removeItem(at: htmlURL.appendingPathExtension("metadata"))
        try? fileManager.removeItem(at: cachedManifestURL(artifactID: artifactID))
        try writeIndex(index)
    }

    private func beginInteractiveOperationEpoch(artifactID: String) -> InteractiveOperationEpoch {
        artifactEpochs[artifactID, default: 0] &+= 1
        return InteractiveOperationEpoch(
            namespace: namespaceEpoch,
            artifact: artifactEpochs[artifactID, default: 0]
        )
    }

    private func validateInteractiveOperationEpoch(
        _ epoch: InteractiveOperationEpoch,
        artifactID: String,
        temporaryURL: URL? = nil
    ) throws {
        guard epoch.namespace == namespaceEpoch,
              epoch.artifact == artifactEpochs[artifactID, default: 0] else {
            if let temporaryURL {
                try? fileManager.removeItem(at: temporaryURL)
            }
            throw CancellationError()
        }
    }

    private func invalidateInteractiveOperations(artifactID: String) {
        artifactEpochs[artifactID, default: 0] &+= 1
    }

    private func removeFiles(for entry: PersonalAppCacheEntry) {
        if let htmlURL = safeCacheURL(fileName: entry.htmlFileName) {
            try? fileManager.removeItem(at: htmlURL)
            try? fileManager.removeItem(at: htmlURL.appendingPathExtension("metadata"))
        }
        if let manifestURL = safeCacheURL(fileName: entry.manifestFileName) {
            try? fileManager.removeItem(at: manifestURL)
        }
    }

    private func safeCacheURL(fileName: String) -> URL? {
        guard !fileName.isEmpty,
              fileName != ".",
              fileName != "..",
              fileName.utf8.count <= 255,
              fileName == (fileName as NSString).lastPathComponent,
              !fileName.contains("/"),
              !fileName.contains("\\") else { return nil }
        let root = namespaceURL.standardizedFileURL
        let candidate = root.appendingPathComponent(fileName, isDirectory: false).standardizedFileURL
        let rootPrefix = root.path.hasSuffix("/") ? root.path : root.path + "/"
        guard candidate.path.hasPrefix(rootPrefix) else { return nil }
        return candidate
    }

    private func isExplicitRevoke(_ error: AttachmentDownloadError) -> Bool {
        error == .unauthorized || error == .forbidden || error == .unavailable
    }

    private func isTransient(_ error: AttachmentDownloadError) -> Bool {
        guard case let .httpStatus(status) = error else { return false }
        return status == 408 || status == 429 || status >= 500
    }

    private func validateIdentifiers(sessionID: String, attachmentID: String) throws {
        guard Self.isValidPathComponent(sessionID), Self.isValidPathComponent(attachmentID) else {
            throw AttachmentDownloadError.invalidIdentifier
        }
    }

    private func authenticatedRequest(url: URL, accept: String) -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue(accept, forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(deviceToken)", forHTTPHeaderField: "Authorization")
        return request
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

    private func manifestURL(sessionID: String, attachmentID: String) -> URL {
        downloadURL(sessionID: sessionID, attachmentID: attachmentID)
            .deletingLastPathComponent()
            .appending(path: "manifest")
    }

    private var indexURL: URL {
        namespaceURL.appending(path: "personal-app-index.json")
    }

    private func cachedManifestURL(artifactID: String) -> URL {
        namespaceURL.appending(path: "\(Self.digest(Data(artifactID.utf8))).manifest.json")
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
        let basename = Self.digest(Data(attachment.id.utf8))
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
              let metadata = try? JSONDecoder().decode(Metadata.self, from: data) else { return nil }
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

    private static func digest(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private static func digest(contentsOf url: URL) throws -> String {
        try digest(Data(contentsOf: url, options: .mappedIfSafe))
    }

    private static func sha256FromQuotedETag(_ value: String) -> String? {
        guard value.count == 66, value.first == "\"", value.last == "\"" else { return nil }
        let sha = String(value.dropFirst().dropLast()).lowercased()
        return isSHA256(sha) ? sha : nil
    }

    private static func isSHA256(_ value: String) -> Bool {
        value.count == 64 && value.unicodeScalars.allSatisfy {
            CharacterSet(charactersIn: "0123456789abcdef").contains($0)
        }
    }

    private static func isValidPathComponent(_ value: String) -> Bool {
        guard !value.isEmpty, value != ".", value != "..", value.utf8.count <= 128 else { return false }
        return value.unicodeScalars.allSatisfy {
            CharacterSet.alphanumerics.contains($0) || "-_.".unicodeScalars.contains($0)
        }
    }

    private static func isTransportFailure(_ error: Error) -> Bool {
        guard let error = error as? URLError else { return false }
        return error.code != .cancelled
    }
}
