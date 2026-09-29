package com.example.pinch.model

import com.example.pinch.parsing.TextKit

/**
 * Picks a cover emoji and color, and suggests tags, from a recipe's words.
 * The keyword table is shared with iOS so both apps choose the same covers.
 */
object CoverSuggester {
    data class Suggestion(val emoji: String, val palette: CoverPalette)

    private data class Entry(val keywords: List<String>, val emoji: String, val palette: CoverPalette)

    /** Ordered: earlier, more specific keywords win ("chocolate cake" is cake, not chocolate). */
    private val table = listOf(
        Entry(listOf("cupcake", "muffin"), "🧁", CoverPalette.PLUM),
        Entry(listOf("cookie", "snickerdoodle", "biscotti", "shortbread"), "🍪", CoverPalette.COCOA),
        Entry(listOf("brownie", "fudge", "chocolate", "cocoa", "truffle"), "🍫", CoverPalette.COCOA),
        Entry(listOf("cheesecake", "cake", "torte", "gateau"), "🍰", CoverPalette.BEET),
        Entry(listOf("pie", "tart", "galette", "cobbler", "crumble", "crisp"), "🥧", CoverPalette.SAFFRON),
        Entry(listOf("pancake", "waffle", "crepe", "french toast"), "🥞", CoverPalette.BUTTER),
        Entry(listOf("ice cream", "gelato", "sorbet", "popsicle", "semifreddo"), "🍨", CoverPalette.SKY),
        Entry(listOf("donut", "doughnut"), "🍩", CoverPalette.BEET),
        Entry(listOf("bread", "loaf", "focaccia", "sourdough", "baguette", "brioche", "challah", "rolls", "buns", "biscuits", "scones"), "🍞", CoverPalette.BUTTER),
        Entry(listOf("pizza", "calzone"), "🍕", CoverPalette.TOMATO),
        Entry(listOf("lasagna", "lasagne", "spaghetti", "pasta", "linguine", "fettuccine", "penne", "rigatoni", "macaroni", "gnocchi", "ravioli", "orzo", "bolognese", "carbonara"), "🍝", CoverPalette.TOMATO),
        Entry(listOf("ramen", "noodle", "pho", "udon", "soba", "lo mein", "pad thai"), "🍜", CoverPalette.SAFFRON),
        Entry(listOf("dumpling", "potsticker", "gyoza", "wonton", "pierogi"), "🥟", CoverPalette.SAGE),
        Entry(listOf("sushi", "maki", "onigiri"), "🍣", CoverPalette.SKY),
        Entry(listOf("curry", "masala", "dal", "dahl", "korma", "vindaloo", "tikka"), "🍛", CoverPalette.SAFFRON),
        Entry(listOf("taco", "burrito", "enchilada", "quesadilla", "fajita", "tostada", "nachos"), "🌮", CoverPalette.SAFFRON),
        Entry(listOf("burger", "sliders"), "🍔", CoverPalette.COCOA),
        Entry(listOf("sandwich", "panini", "sub", "blt", "wrap"), "🥪", CoverPalette.BUTTER),
        Entry(listOf("soup", "stew", "chili", "chowder", "bisque", "broth", "gumbo", "minestrone", "stock"), "🍲", CoverPalette.TOMATO),
        Entry(listOf("salad", "slaw", "caesar"), "🥗", CoverPalette.JADE),
        Entry(listOf("risotto", "rice", "paella", "pilaf", "fried rice", "biryani", "jambalaya"), "🍚", CoverPalette.SAGE),
        Entry(listOf("shrimp", "prawn", "scampi"), "🍤", CoverPalette.TOMATO),
        Entry(listOf("salmon", "tuna", "cod", "fish", "halibut", "tilapia", "trout", "crab", "lobster", "scallop", "clam", "mussel", "oyster"), "🐟", CoverPalette.SKY),
        Entry(listOf("chicken", "turkey", "wings", "drumstick", "poultry"), "🍗", CoverPalette.SAFFRON),
        Entry(listOf("steak", "beef", "brisket", "short rib", "roast", "meatball", "meatloaf", "pot roast"), "🥩", CoverPalette.BEET),
        Entry(listOf("pork", "ribs", "bacon", "ham", "sausage", "carnitas", "chop"), "🍖", CoverPalette.COCOA),
        Entry(listOf("egg", "omelet", "omelette", "frittata", "quiche", "shakshuka", "scramble"), "🍳", CoverPalette.BUTTER),
        Entry(listOf("oatmeal", "porridge", "granola", "overnight oats"), "🥣", CoverPalette.SAGE),
        Entry(listOf("smoothie", "shake", "lassi"), "🥤", CoverPalette.PLUM),
        Entry(listOf("cocktail", "margarita", "sangria", "punch", "mojito", "spritz"), "🍹", CoverPalette.SKY),
        Entry(listOf("coffee", "latte", "espresso", "mocha"), "☕️", CoverPalette.COCOA),
        Entry(listOf("tea", "chai", "matcha"), "🍵", CoverPalette.JADE),
        Entry(listOf("lemonade", "lemon"), "🍋", CoverPalette.BUTTER),
        Entry(listOf("apple"), "🍎", CoverPalette.TOMATO),
        Entry(listOf("banana"), "🍌", CoverPalette.BUTTER),
        Entry(listOf("strawberry", "raspberry"), "🍓", CoverPalette.BEET),
        Entry(listOf("blueberry", "berry", "berries"), "🫐", CoverPalette.BLUEBERRY),
        Entry(listOf("peach", "apricot"), "🍑", CoverPalette.SAFFRON),
        Entry(listOf("pumpkin", "squash"), "🎃", CoverPalette.SAFFRON),
        Entry(listOf("corn", "polenta", "grits", "cornbread"), "🌽", CoverPalette.BUTTER),
        Entry(listOf("potato", "fries", "hash brown", "mash"), "🥔", CoverPalette.COCOA),
        Entry(listOf("avocado", "guacamole"), "🥑", CoverPalette.JADE),
        Entry(listOf("mushroom"), "🍄", CoverPalette.COCOA),
        Entry(listOf("carrot"), "🥕", CoverPalette.SAFFRON),
        Entry(listOf("broccoli", "kale", "spinach", "greens", "vegetable", "veggie"), "🥦", CoverPalette.JADE),
        Entry(listOf("tofu", "tempeh", "edamame", "miso"), "🥢", CoverPalette.SAGE),
        Entry(listOf("cheese", "fondue", "mac and cheese"), "🧀", CoverPalette.BUTTER),
        Entry(listOf("dip", "hummus", "salsa", "sauce", "dressing", "pesto", "jam", "preserve", "pickle"), "🫙", CoverPalette.JADE),
    )

    /** The cover color that goes with an emoji from the table, ignoring emoji variation selectors. */
    fun palette(forEmoji: String): CoverPalette? {
        val bare = forEmoji.replace("\uFE0F", "")
        return table.firstOrNull { it.emoji.replace("\uFE0F", "") == bare }?.palette
    }

    fun suggest(title: String, ingredients: List<String> = emptyList()): Suggestion {
        val foldedTitle = " " + TextKit.words(title).joinToString(" ") + " "
        firstHit(foldedTitle)?.let { return it }
        val foldedIngredients = " " + ingredients.flatMap { TextKit.words(it) }.joinToString(" ") + " "
        firstHit(foldedIngredients)?.let { return it }
        val fallback = listOf(
            CoverPalette.JADE, CoverPalette.TOMATO, CoverPalette.BUTTER, CoverPalette.BLUEBERRY,
            CoverPalette.PLUM, CoverPalette.SAFFRON, CoverPalette.SKY, CoverPalette.SAGE,
        )
        return Suggestion("🍽️", fallback[stableIndex(title, fallback.size)])
    }

    private fun firstHit(text: String): Suggestion? {
        for (entry in table) {
            for (keyword in entry.keywords) {
                if (text.contains(" $keyword ") || text.contains(" ${keyword}s ") || text.contains(" ${keyword}es ")) {
                    return Suggestion(entry.emoji, entry.palette)
                }
            }
        }
        return null
    }

    fun pattern(id: String): CoverPattern {
        val all = CoverPattern.entries
        return all[stableIndex(id, all.size)]
    }

    /** FNV-1a over UTF-8, identical on both platforms. */
    fun stableIndex(s: String, count: Int): Int {
        var hash = 2166136261u
        for (byte in s.toByteArray(Charsets.UTF_8)) {
            hash = hash xor byte.toUByte().toUInt()
            hash *= 16777619u
        }
        return (hash % maxOf(count, 1).toUInt()).toInt()
    }

    private val tagKeywords = listOf(
        "Breakfast" to listOf("breakfast", "pancake", "waffle", "omelet", "omelette", "frittata", "granola", "oatmeal", "porridge", "french toast", "scramble", "brunch", "muffin"),
        "Dessert" to listOf("cake", "cookie", "brownie", "pie", "tart", "pudding", "ice cream", "cheesecake", "fudge", "cupcake", "cobbler", "crumble", "dessert", "frosting", "truffle", "sorbet"),
        "Baking" to listOf("bread", "loaf", "dough", "yeast", "bake", "scone", "biscuit", "muffin", "focaccia", "sourdough", "brioche", "rolls"),
        "Soup" to listOf("soup", "stew", "chili", "chowder", "bisque", "broth", "gumbo"),
        "Salad" to listOf("salad", "slaw"),
        "Pasta" to listOf("pasta", "spaghetti", "lasagna", "noodle", "penne", "macaroni", "gnocchi", "ravioli", "linguine", "fettuccine"),
        "Chicken" to listOf("chicken"),
        "Seafood" to listOf("fish", "salmon", "shrimp", "tuna", "cod", "crab", "scallop", "prawn", "halibut"),
        "Vegetarian" to listOf("vegetarian", "veggie", "tofu", "lentil", "chickpea", "bean"),
        "Drinks" to listOf("cocktail", "smoothie", "lemonade", "punch", "latte", "tea", "sangria", "margarita"),
        "Snack" to listOf("dip", "hummus", "salsa", "snack", "popcorn", "nachos", "guacamole"),
    )

    fun suggestTags(title: String, ingredients: List<String>): List<String> {
        val titleText = " " + TextKit.words(title).joinToString(" ") + " "
        val allText = titleText + TextKit.words(ingredients.joinToString(" ")).joinToString(" ") + " "
        val tags = ArrayList<String>()
        for ((tag, keywords) in tagKeywords) {
            // Course-like tags come from the title only; ingredient mentions are too noisy.
            val haystack = if (tag in setOf("Chicken", "Seafood", "Vegetarian")) allText else titleText
            if (keywords.any { haystack.contains(" $it ") || haystack.contains(" ${it}s ") }) tags.add(tag)
        }
        return tags.take(3)
    }
}
