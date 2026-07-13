import Foundation
import ImageIO

struct AttachmentDecodedImage: @unchecked Sendable {
    let cgImage: CGImage
}

actor AttachmentImageLoader {
    static let shared = AttachmentImageLoader()

    private struct CacheKey: Hashable {
        let url: URL
        let maxPixelSize: Int
        let fileSize: Int?
        let modificationDate: Date?

        var cacheIdentifier: String {
            [
                url.absoluteString,
                String(maxPixelSize),
                fileSize.map(String.init) ?? "-",
                modificationDate.map { String($0.timeIntervalSince1970) } ?? "-"
            ].joined(separator: "|")
        }
    }

    private let cache = NSCache<NSString, CGImage>()

    init(cacheLimit: Int = 24, memoryLimitBytes: Int = 96 * 1_024 * 1_024) {
        cache.countLimit = max(1, cacheLimit)
        cache.totalCostLimit = max(1, memoryLimitBytes)
    }

    func image(at url: URL, maxPixelSize: Int) -> AttachmentDecodedImage? {
        guard maxPixelSize > 0 else { return nil }
        let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
        let key = CacheKey(
            url: url,
            maxPixelSize: maxPixelSize,
            fileSize: values?.fileSize,
            modificationDate: values?.contentModificationDate
        )
        let cacheKey = key.cacheIdentifier as NSString
        if let cached = cache.object(forKey: cacheKey) {
            return AttachmentDecodedImage(cgImage: cached)
        }

        let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithURL(url as CFURL, sourceOptions) else { return nil }
        let thumbnailOptions = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
            kCGImageSourceShouldCacheImmediately: true
        ] as CFDictionary
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions) else {
            return nil
        }

        let cost = max(1, cgImage.bytesPerRow * cgImage.height)
        cache.setObject(cgImage, forKey: cacheKey, cost: cost)
        return AttachmentDecodedImage(cgImage: cgImage)
    }
}
