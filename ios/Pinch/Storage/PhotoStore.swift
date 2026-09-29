import ImageIO
import UIKit

enum AppPaths {
    static var root: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Pinch", isDirectory: true)
    }
    static var recipes: URL { root.appendingPathComponent("recipes", isDirectory: true) }
    static var photos: URL { root.appendingPathComponent("photos", isDirectory: true) }
    static var shareCards: URL { FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true) }
}

/// Scanned pages, stored as JPEG files named by UUID so they sync without collisions.
final class PhotoStore: @unchecked Sendable {
    static let shared = PhotoStore()

    let directory: URL
    private let images = NSCache<NSString, UIImage>()
    private let thumbnails = NSCache<NSString, UIImage>()

    init(directory: URL = AppPaths.photos) {
        self.directory = directory
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        images.countLimit = 12
        thumbnails.countLimit = 120
    }

    func url(for name: String) -> URL {
        directory.appendingPathComponent(name)
    }

    func exists(_ name: String) -> Bool {
        FileManager.default.fileExists(atPath: url(for: name).path)
    }

    /// Downscales, orients upright and saves the image, returning its file name.
    func save(_ image: UIImage) throws -> String {
        let name = UUID().uuidString.lowercased() + ".jpg"
        let normalized = ImageTools.normalized(image, maxDimension: 2400)
        guard let data = normalized.jpegData(compressionQuality: 0.82) else {
            throw CocoaError(.fileWriteUnknown)
        }
        try data.write(to: url(for: name), options: .atomic)
        images.setObject(normalized, forKey: name as NSString)
        return name
    }

    func save(data: Data, name: String) throws {
        try data.write(to: url(for: name), options: .atomic)
        images.removeObject(forKey: name as NSString)
        thumbnails.removeObject(forKey: name as NSString)
    }

    func data(for name: String) -> Data? {
        try? Data(contentsOf: url(for: name))
    }

    func image(named name: String) -> UIImage? {
        if let cached = images.object(forKey: name as NSString) { return cached }
        guard let image = UIImage(contentsOfFile: url(for: name).path) else { return nil }
        images.setObject(image, forKey: name as NSString)
        return image
    }

    func thumbnail(named name: String, maxPixelSize: CGFloat = 360) -> UIImage? {
        let key = "\(name)@\(Int(maxPixelSize))" as NSString
        if let cached = thumbnails.object(forKey: key) { return cached }
        guard let source = CGImageSourceCreateWithURL(url(for: name) as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let image = UIImage(cgImage: cg)
        thumbnails.setObject(image, forKey: key)
        return image
    }

    func delete(_ name: String) {
        try? FileManager.default.removeItem(at: url(for: name))
        images.removeObject(forKey: name as NSString)
    }
}

enum ImageTools {
    /// Redraws the image upright at no more than `maxDimension` pixels on its long edge.
    static func normalized(_ image: UIImage, maxDimension: CGFloat) -> UIImage {
        let pixelWidth = image.size.width * image.scale
        let pixelHeight = image.size.height * image.scale
        let longest = max(pixelWidth, pixelHeight)
        let ratio = longest > maxDimension ? maxDimension / longest : 1
        let target = CGSize(width: (pixelWidth * ratio).rounded(), height: (pixelHeight * ratio).rounded())
        if ratio == 1 && image.imageOrientation == .up && image.scale == 1 { return image }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }
}
