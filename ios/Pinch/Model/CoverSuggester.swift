import Foundation

/// Picks a cover emoji, color and pattern, and suggests tags, from a recipe's words.
/// The keyword table is shared with Android so both apps choose the same covers.
enum CoverSuggester {
    struct Suggestion: Equatable {
        let emoji: String
        let palette: CoverPalette
    }

    /// Ordered: earlier, more specific keywords win ("chocolate cake" is cake, not chocolate).
    static let table: [(keywords: [String], emoji: String, palette: CoverPalette)] = [
        (["cupcake", "muffin"], "🧁", .plum),
        (["cookie", "snickerdoodle", "biscotti", "shortbread"], "🍪", .cocoa),
        (["brownie", "fudge", "chocolate", "cocoa", "truffle"], "🍫", .cocoa),
        (["cheesecake", "cake", "torte", "gateau"], "🍰", .beet),
        (["pie", "tart", "galette", "cobbler", "crumble", "crisp"], "🥧", .saffron),
        (["pancake", "waffle", "crepe", "french toast"], "🥞", .butter),
        (["ice cream", "gelato", "sorbet", "popsicle", "semifreddo"], "🍨", .sky),
        (["donut", "doughnut"], "🍩", .beet),
        (["bread", "loaf", "focaccia", "sourdough", "baguette", "brioche", "challah", "rolls", "buns", "biscuits", "scones"], "🍞", .butter),
        (["pizza", "calzone"], "🍕", .tomato),
        (["lasagna", "lasagne", "spaghetti", "pasta", "linguine", "fettuccine", "penne", "rigatoni", "macaroni", "gnocchi", "ravioli", "orzo", "bolognese", "carbonara"], "🍝", .tomato),
        (["ramen", "noodle", "pho", "udon", "soba", "lo mein", "pad thai"], "🍜", .saffron),
        (["dumpling", "potsticker", "gyoza", "wonton", "pierogi"], "🥟", .sage),
        (["sushi", "maki", "onigiri"], "🍣", .sky),
        (["curry", "masala", "dal", "dahl", "korma", "vindaloo", "tikka"], "🍛", .saffron),
        (["taco", "burrito", "enchilada", "quesadilla", "fajita", "tostada", "nachos"], "🌮", .saffron),
        (["burger", "sliders"], "🍔", .cocoa),
        (["sandwich", "panini", "sub", "blt", "wrap"], "🥪", .butter),
        (["soup", "stew", "chili", "chowder", "bisque", "broth", "gumbo", "minestrone", "stock"], "🍲", .tomato),
        (["salad", "slaw", "caesar"], "🥗", .jade),
        (["risotto", "rice", "paella", "pilaf", "fried rice", "biryani", "jambalaya"], "🍚", .sage),
        (["shrimp", "prawn", "scampi"], "🍤", .tomato),
        (["salmon", "tuna", "cod", "fish", "halibut", "tilapia", "trout", "crab", "lobster", "scallop", "clam", "mussel", "oyster"], "🐟", .sky),
        (["chicken", "turkey", "wings", "drumstick", "poultry"], "🍗", .saffron),
        (["steak", "beef", "brisket", "short rib", "roast", "meatball", "meatloaf", "pot roast"], "🥩", .beet),
        (["pork", "ribs", "bacon", "ham", "sausage", "carnitas", "chop"], "🍖", .cocoa),
        (["egg", "omelet", "omelette", "frittata", "quiche", "shakshuka", "scramble"], "🍳", .butter),
        (["oatmeal", "porridge", "granola", "overnight oats"], "🥣", .sage),
        (["smoothie", "shake", "lassi"], "🥤", .plum),
        (["cocktail", "margarita", "sangria", "punch", "mojito", "spritz"], "🍹", .sky),
        (["coffee", "latte", "espresso", "mocha"], "☕️", .cocoa),
        (["tea", "chai", "matcha"], "🍵", .jade),
        (["lemonade", "lemon"], "🍋", .butter),
        (["apple"], "🍎", .tomato),
        (["banana"], "🍌", .butter),
        (["strawberry", "raspberry"], "🍓", .beet),
        (["blueberry", "berry", "berries"], "🫐", .blueberry),
        (["peach", "apricot"], "🍑", .saffron),
        (["pumpkin", "squash"], "🎃", .saffron),
        (["corn", "polenta", "grits", "cornbread"], "🌽", .butter),
        (["potato", "fries", "hash brown", "mash"], "🥔", .cocoa),
        (["avocado", "guacamole"], "🥑", .jade),
        (["mushroom"], "🍄", .cocoa),
        (["carrot"], "🥕", .saffron),
        (["broccoli", "kale", "spinach", "greens", "vegetable", "veggie"], "🥦", .jade),
        (["tofu", "tempeh", "edamame", "miso"], "🥢", .sage),
        (["cheese", "fondue", "mac and cheese"], "🧀", .butter),
        (["dip", "hummus", "salsa", "sauce", "dressing", "pesto", "jam", "preserve", "pickle"], "🫙", .jade),
    ]

    static func suggest(title: String, ingredients: [String] = []) -> Suggestion {
        let foldedTitle = " " + TextKit.words(title).joined(separator: " ") + " "
        if let hit = firstHit(in: foldedTitle) { return hit }
        let foldedIngredients = " " + ingredients.flatMap { TextKit.words($0) }.joined(separator: " ") + " "
        if let hit = firstHit(in: foldedIngredients) { return hit }
        let fallback: [CoverPalette] = [.jade, .tomato, .butter, .blueberry, .plum, .saffron, .sky, .sage]
        return Suggestion(emoji: "🍽️", palette: fallback[stableIndex(title, count: fallback.count)])
    }

    private static func firstHit(in text: String) -> Suggestion? {
        for entry in table {
            for keyword in entry.keywords {
                // Match whole words and simple plurals: " cookie " and " cookies ".
                if text.contains(" \(keyword) ") || text.contains(" \(keyword)s ") || text.contains(" \(keyword)es ") {
                    return Suggestion(emoji: entry.emoji, palette: entry.palette)
                }
            }
        }
        return nil
    }

    static func pattern(for id: String) -> CoverPattern {
        let all = CoverPattern.allCases
        return all[stableIndex(id, count: all.count)]
    }

    /// FNV-1a over UTF-8, identical on both platforms.
    static func stableIndex(_ s: String, count: Int) -> Int {
        var hash: UInt32 = 2_166_136_261
        for byte in s.utf8 {
            hash ^= UInt32(byte)
            hash = hash &* 16_777_619
        }
        return Int(hash % UInt32(max(count, 1)))
    }

    // MARK: Tags

    static let tagKeywords: [(tag: String, keywords: [String])] = [
        ("Breakfast", ["breakfast", "pancake", "waffle", "omelet", "omelette", "frittata", "granola", "oatmeal", "porridge", "french toast", "scramble", "brunch", "muffin"]),
        ("Dessert", ["cake", "cookie", "brownie", "pie", "tart", "pudding", "ice cream", "cheesecake", "fudge", "cupcake", "cobbler", "crumble", "dessert", "frosting", "truffle", "sorbet"]),
        ("Baking", ["bread", "loaf", "dough", "yeast", "bake", "scone", "biscuit", "muffin", "focaccia", "sourdough", "brioche", "rolls"]),
        ("Soup", ["soup", "stew", "chili", "chowder", "bisque", "broth", "gumbo"]),
        ("Salad", ["salad", "slaw"]),
        ("Pasta", ["pasta", "spaghetti", "lasagna", "noodle", "penne", "macaroni", "gnocchi", "ravioli", "linguine", "fettuccine"]),
        ("Chicken", ["chicken"]),
        ("Seafood", ["fish", "salmon", "shrimp", "tuna", "cod", "crab", "scallop", "prawn", "halibut"]),
        ("Vegetarian", ["vegetarian", "veggie", "tofu", "lentil", "chickpea", "bean"]),
        ("Drinks", ["cocktail", "smoothie", "lemonade", "punch", "latte", "tea", "sangria", "margarita"]),
        ("Snack", ["dip", "hummus", "salsa", "snack", "popcorn", "nachos", "guacamole"]),
    ]

    static func suggestTags(title: String, ingredients: [String]) -> [String] {
        let titleText = " " + TextKit.words(title).joined(separator: " ") + " "
        let allText = titleText + TextKit.words(ingredients.joined(separator: " ")).joined(separator: " ") + " "
        var tags: [String] = []
        for (tag, keywords) in tagKeywords {
            // Course-like tags come from the title only; ingredient mentions are too noisy.
            let haystack = ["Chicken", "Seafood", "Vegetarian"].contains(tag) ? allText : titleText
            if keywords.contains(where: { haystack.contains(" \($0) ") || haystack.contains(" \($0)s ") }) {
                tags.append(tag)
            }
        }
        return Array(tags.prefix(3))
    }
}
