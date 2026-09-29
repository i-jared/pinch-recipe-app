import SwiftUI

/// The main action: a jade capsule with dark text, big enough to hit with a floury thumb.
struct PrimaryButtonStyle: ButtonStyle {
    var fill: Color = Theme.jade
    var foreground: Color = Theme.onJade

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.rounded(.headline, .bold))
            .foregroundStyle(foreground)
            .padding(.horizontal, 22)
            .frame(minHeight: 54)
            .background(Capsule().fill(fill))
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

/// A quieter capsule on the sunken surface color.
struct SoftButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.rounded(.subheadline, .semibold))
            .foregroundStyle(Theme.ink)
            .padding(.horizontal, 16)
            .frame(minHeight: 44)
            .background(Capsule().fill(Theme.sunken))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

/// A small icon + text capsule for times and servings.
struct MetaPill: View {
    let systemImage: String
    let text: String
    var tint: Color = Theme.ink

    var body: some View {
        Label {
            Text(text)
        } icon: {
            Image(systemName: systemImage)
        }
        .font(.rounded(.subheadline, .semibold))
        .foregroundStyle(tint)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Capsule().fill(Theme.sunken))
    }
}

/// Filter chip, shaped like the tab on a recipe box divider.
struct FilterChip: View {
    let title: String
    var systemImage: String?
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title)
            }
            .font(.rounded(.subheadline, .semibold))
            .foregroundStyle(isSelected ? Theme.onJade : Theme.ink)
            .padding(.horizontal, 14)
            .frame(minHeight: 36)
            .background(
                UnevenRoundedRectangle(topLeadingRadius: 14, bottomLeadingRadius: 6, bottomTrailingRadius: 6, topTrailingRadius: 14, style: .continuous)
                    .fill(isSelected ? Theme.jade : Theme.sunken)
            )
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

struct SectionHeading: View {
    let title: String
    var trailing: AnyView?

    init(_ title: String, trailing: AnyView? = nil) {
        self.title = title
        self.trailing = trailing
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title)
                .font(.rounded(.title2, .bold))
                .foregroundStyle(Theme.ink)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            if let trailing { trailing }
        }
    }
}

/// Wraps children onto new lines, for tags and chips.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8
    var lineSpacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, lineHeight: CGFloat = 0, widest: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > maxWidth {
                y += lineHeight + lineSpacing
                x = 0
                lineHeight = 0
            }
            x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
            widest = max(widest, x - spacing)
        }
        return CGSize(width: min(widest, maxWidth), height: y + lineHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, lineHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX {
                y += lineHeight + lineSpacing
                x = bounds.minX
                lineHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
    }
}

/// Running timers, shown as a stack of pills above the bottom of the screen.
struct TimerDock: View {
    @Environment(TimerCenter.self) private var timers
    var recipeID: String?

    private var visible: [KitchenTimer] {
        guard let recipeID else { return timers.timers }
        let mine = timers.timers(for: recipeID)
        return mine.isEmpty ? timers.timers : mine
    }

    var body: some View {
        if !visible.isEmpty {
            VStack(spacing: 8) {
                ForEach(visible) { timer in
                    TimerPill(timer: timer)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.spring(response: 0.35, dampingFraction: 0.8), value: visible)
        }
    }
}

struct TimerPill: View {
    @Environment(TimerCenter.self) private var timers
    let timer: KitchenTimer

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: timer.isFinished ? "bell.and.waves.left.and.right.fill" : "timer")
                .font(.rounded(.title3, .bold))
                .symbolEffect(.bounce, options: .repeating, value: timer.isFinished)
            VStack(alignment: .leading, spacing: 1) {
                if timer.isFinished {
                    Text("Time's up")
                        .font(.rounded(.headline, .bold))
                } else {
                    Text(timerInterval: Date.now...max(timer.endDate, Date.now), countsDown: true)
                        .font(.rounded(.title3, .bold).monospacedDigit())
                }
                Text(timer.label)
                    .font(.rounded(.caption, .semibold))
                    .lineLimit(1)
                    .opacity(0.85)
            }
            Spacer(minLength: 4)
            Button {
                timers.addMinute(timer.id)
            } label: {
                Text("+1 min")
                    .font(.rounded(.caption, .bold))
                    .padding(.horizontal, 10)
                    .frame(minHeight: 32)
                    .background(Capsule().fill(.white.opacity(0.22)))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Add one minute")
            Button {
                timers.cancel(timer.id)
            } label: {
                Image(systemName: timer.isFinished ? "checkmark" : "xmark")
                    .font(.rounded(.subheadline, .bold))
                    .frame(width: 32, height: 32)
                    .background(Circle().fill(.white.opacity(0.22)))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(timer.isFinished ? "Dismiss timer" : "Cancel timer")
        }
        .foregroundStyle(timer.isFinished ? .white : Theme.ink)
        .padding(.leading, 16)
        .padding(.trailing, 10)
        .padding(.vertical, 10)
        .background(
            Capsule()
                .fill(timer.isFinished ? Theme.cherry : Theme.butter)
                .shadow(color: .black.opacity(0.15), radius: 10, y: 4)
        )
        .accessibilityElement(children: .contain)
    }
}

/// Fades content out behind floating bottom buttons so text never sits under them.
struct BottomFade: View {
    var body: some View {
        LinearGradient(
            stops: [
                .init(color: Theme.canvas.opacity(0), location: 0),
                .init(color: Theme.canvas.opacity(0.92), location: 0.45),
                .init(color: Theme.canvas, location: 1),
            ],
            startPoint: .top,
            endPoint: .bottom
        )
        .padding(.top, -28)
        .ignoresSafeArea(edges: .bottom)
        .allowsHitTesting(false)
    }
}
