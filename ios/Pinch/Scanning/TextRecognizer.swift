import UIKit
import Vision

/// On-device text recognition with Vision. Nothing leaves the phone.
enum TextRecognizer {
    /// Words Vision's language correction might otherwise "fix" into something else.
    static let cookingWords = [
        "tbsp", "tsp", "oz", "lbs", "ml", "sauté", "julienne", "chiffonade", "preheat", "parchment", "zest",
        "springform", "ramekin", "confectioners", "cornstarch", "buttermilk", "worcestershire", "dijon",
        "balsamic", "shallot", "scallion", "cilantro", "tarragon", "cardamom", "turmeric", "coriander",
        "gochujang", "sriracha", "tahini", "arborio", "pancetta", "prosciutto", "ricotta", "mascarpone",
        "jalapeño", "chipotle", "panko", "furikake", "za'atar", "harissa", "sumac", "ghee", "masa",
    ]

    static func recognize(_ image: UIImage, page: Int) async throws -> [TextLine] {
        guard let cgImage = image.cgImage else { return [] }
        let orientation = CGImagePropertyOrientation(image.imageOrientation)
        return try await Task.detached(priority: .userInitiated) {
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            request.automaticallyDetectsLanguage = true
            request.customWords = cookingWords
            let handler = VNImageRequestHandler(cgImage: cgImage, orientation: orientation, options: [:])
            try handler.perform([request])
            let fragments = (request.results ?? []).compactMap { observation -> OCRFragment? in
                guard let candidate = observation.topCandidates(1).first else { return nil }
                // Vision's origin is bottom-left; the layout engine works top-left.
                let b = observation.boundingBox
                return OCRFragment(text: candidate.string, box: CGRect(x: b.minX, y: 1 - b.maxY, width: b.width, height: b.height))
            }
            return LineLayout.lines(from: fragments, page: page)
        }.value
    }
}

extension CGImagePropertyOrientation {
    init(_ orientation: UIImage.Orientation) {
        switch orientation {
        case .up: self = .up
        case .upMirrored: self = .upMirrored
        case .down: self = .down
        case .downMirrored: self = .downMirrored
        case .left: self = .left
        case .leftMirrored: self = .leftMirrored
        case .right: self = .right
        case .rightMirrored: self = .rightMirrored
        @unknown default: self = .up
        }
    }
}
