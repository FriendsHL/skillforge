import UIKit
import XCTest
@testable import SkillForge

final class AttachmentImageLoaderTests: XCTestCase {
    func testDownsamplesOffscreenImageToConfiguredBoundAndCachesResult() async throws {
        let url = FileManager.default.temporaryDirectory
            .appending(path: "AttachmentImageLoaderTests-\(UUID().uuidString).png")
        defer { try? FileManager.default.removeItem(at: url) }
        let data = UIGraphicsImageRenderer(size: CGSize(width: 1_600, height: 1_000)).pngData { context in
            UIColor.systemBlue.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 1_600, height: 1_000))
        }
        try data.write(to: url)
        let loader = AttachmentImageLoader(cacheLimit: 2)

        let firstResult = await loader.image(at: url, maxPixelSize: 128)
        let secondResult = await loader.image(at: url, maxPixelSize: 128)
        let first = try XCTUnwrap(firstResult)
        let second = try XCTUnwrap(secondResult)

        XCTAssertLessThanOrEqual(max(first.cgImage.width, first.cgImage.height), 128)
        XCTAssertTrue(first.cgImage === second.cgImage)
    }
}
