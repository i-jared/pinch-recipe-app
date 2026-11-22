import SwiftUI

/// Tablecloth and recipe-tin patterns drawn in translucent white over the cover color.
struct CoverPatternView: View {
    let pattern: CoverPattern
    var cell: CGFloat = 22
    var inkColor: Color = .white
    var strength: Double = 1

    /// Each pattern covers a different share of the surface, so each gets its own opacity.
    private var ink: Color {
        let alpha: Double
        switch pattern {
        case .gingham, .stripes: alpha = 0.27
        case .dots: alpha = 0.34
        case .waves: alpha = 0.36
        case .tiles: alpha = 0.34
        }
        return inkColor.opacity(alpha * strength)
    }

    var body: some View {
        Canvas(rendersAsynchronously: false) { context, size in
            switch pattern {
            case .gingham:
                var x: CGFloat = 0
                while x < size.width {
                    context.fill(Path(CGRect(x: x, y: 0, width: cell / 2, height: size.height)), with: .color(ink))
                    x += cell
                }
                var y: CGFloat = 0
                while y < size.height {
                    context.fill(Path(CGRect(x: 0, y: y, width: size.width, height: cell / 2)), with: .color(ink))
                    y += cell
                }

            case .stripes:
                var path = Path()
                var offset: CGFloat = -size.height
                while offset < size.width + size.height {
                    path.move(to: CGPoint(x: offset, y: 0))
                    path.addLine(to: CGPoint(x: offset + cell * 0.42, y: 0))
                    path.addLine(to: CGPoint(x: offset + cell * 0.42 - size.height, y: size.height))
                    path.addLine(to: CGPoint(x: offset - size.height, y: size.height))
                    path.closeSubpath()
                    offset += cell
                }
                context.fill(path, with: .color(ink))

            case .dots:
                let radius = cell * 0.17
                var path = Path()
                var row = 0
                var y: CGFloat = cell / 2
                while y < size.height + cell {
                    var x: CGFloat = row.isMultiple(of: 2) ? cell / 2 : 0
                    while x < size.width + cell {
                        path.addEllipse(in: CGRect(x: x - radius, y: y - radius, width: radius * 2, height: radius * 2))
                        x += cell
                    }
                    y += cell * 0.8
                    row += 1
                }
                context.fill(path, with: .color(ink))

            case .waves:
                var path = Path()
                let amplitude = cell * 0.16
                let wavelength = cell * 1.3
                var y: CGFloat = cell * 0.4
                while y < size.height + cell {
                    path.move(to: CGPoint(x: 0, y: y))
                    var x: CGFloat = 0
                    while x <= size.width {
                        path.addLine(to: CGPoint(x: x, y: y + sin(x / wavelength * .pi * 2) * amplitude))
                        x += 2
                    }
                    y += cell * 0.62
                }
                context.stroke(path, with: .color(ink), style: StrokeStyle(lineWidth: cell * 0.11, lineCap: .round))

            case .tiles:
                // Offset subway tiles, like a kitchen backsplash.
                var path = Path()
                let tileWidth = cell * 1.4
                let tileHeight = cell * 0.7
                var row = 0
                var y: CGFloat = 0
                while y < size.height {
                    path.move(to: CGPoint(x: 0, y: y))
                    path.addLine(to: CGPoint(x: size.width, y: y))
                    var x: CGFloat = row.isMultiple(of: 2) ? 0 : tileWidth / 2
                    while x < size.width {
                        path.move(to: CGPoint(x: x, y: y))
                        path.addLine(to: CGPoint(x: x, y: y + tileHeight))
                        x += tileWidth
                    }
                    y += tileHeight
                    row += 1
                }
                context.stroke(path, with: .color(ink), lineWidth: max(1.5, cell * 0.08))
            }
        }
    }
}

/// The recipe's cover: its color, its pattern, and optionally its emoji sticker.
struct CoverArt: View {
    let palette: CoverPalette
    let pattern: CoverPattern
    var cell: CGFloat = 22

    var body: some View {
        ZStack {
            palette.base
            CoverPatternView(pattern: pattern, cell: cell)
        }
    }
}

struct RecipeCover: View {
    let recipe: Recipe
    let photos: PhotoStore?
    var cell: CGFloat = 22
    var maxPixelSize: CGFloat = 480

    var body: some View {
        CoverArt(palette: recipe.palette, pattern: recipe.pattern, cell: cell)
            .overlay {
                if let name = recipe.bannerPhoto, let image = photos?.thumbnail(named: name, maxPixelSize: maxPixelSize) {
                    GeometryReader { geo in
                        Image(uiImage: image).resizable().scaledToFill()
                            .frame(width: geo.size.width, height: geo.size.height)
                            .clipped()
                    }
                    .accessibilityHidden(true)
                }
            }
    }
}

/// A round white sticker with the recipe's emoji, slightly tilted like it was stuck on by hand.
struct EmojiSticker: View {
    let emoji: String
    var size: CGFloat = 56
    var tilt: Double = -8

    var body: some View {
        Text(emoji)
            .font(.system(size: size * 0.54))
            .frame(width: size, height: size)
            .background {
                Circle()
                    .fill(.white)
                    .shadow(color: .black.opacity(0.16), radius: size * 0.06, y: size * 0.04)
            }
            .rotationEffect(.degrees(tilt))
            .accessibilityHidden(true)
    }
}
