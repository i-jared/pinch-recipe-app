# Recipe format

Both apps store every recipe as one JSON file and sync those same files to Google Drive.
Because the format is shared, a recipe saved on an iPhone opens on an Android phone and
the other way around.

- iOS: `Application Support/Pinch/recipes/<id>.json` and `Application Support/Pinch/photos/`
- Android: `files/recipes/<id>.json` and `files/photos/`
- Google Drive: a **Pinch Recipes** folder holding one `<Title>.json` per recipe, plus a
  **Photos** subfolder for scanned pages

The Swift model is [`Recipe.swift`](../ios/Pinch/Model/Recipe.swift) and the Kotlin model is
[`Recipe.kt`](../android/app/src/main/java/com/example/pinch/model/Recipe.kt). Tests on both
sides decode the other platform's output (`RecipeCodingTests`, `RecipeCodingTest`).

## Example

```json
{
  "schema": 1,
  "id": "0b6f1c1e-3c4f-4b53-9a53-2f3a8e7b1a90",
  "title": "Nana's Buttermilk Biscuits",
  "summary": "Tall, flaky and ready in about half an hour.",
  "source": "Nana's recipe card",
  "sourceUrl": "",
  "servings": "12 biscuits",
  "prepTime": "15 min",
  "cookTime": "14 min",
  "totalTime": "",
  "ingredients": [
    { "id": "a1b2c3d4e5", "text": "For the dough", "isHeader": true },
    { "id": "f6g7h8i9j0", "text": "2 cups all-purpose flour", "isHeader": false }
  ],
  "steps": [
    { "id": "k1l2m3n4o5", "text": "Heat the oven to 450°F.", "isHeader": false }
  ],
  "notes": "Freeze cut biscuits on a tray, then bag them.",
  "tags": ["Baking", "Breakfast"],
  "emoji": "🍞",
  "palette": "butter",
  "pattern": "gingham",
  "photos": ["5d9c0f5e-8a7b-4c1d-9e2f-3a4b5c6d7e8f.jpg"],
  "isFavorite": false,
  "createdAt": "2026-09-27T18:25:43.511Z",
  "updatedAt": "2026-09-27T18:30:02.004Z",
  "lastCookedAt": "2026-09-28T01:10:00.000Z",
  "rawText": "Nana's Buttermilk Biscuits\nTall, flaky and ready…",
  "deletedAt": null
}
```

## Fields

| Field | Type | Notes |
| --- | --- | --- |
| `schema` | number | Format version, currently `1`. |
| `id` | string | Lowercase UUID. Also the local file name. |
| `title`, `summary`, `source`, `notes` | string | Free text. Empty when unknown. |
| `sourceUrl` | string, optional | The web page a recipe was imported from. Missing or empty for scanned and typed recipes. |
| `servings` | string | As written: `"4"`, `"4-6"`, `"24 cookies"`. The leading number drives scaling. |
| `prepTime`, `cookTime`, `totalTime` | string | Normalized when possible (`"1 hr 15 min"`), otherwise as written. |
| `ingredients`, `steps` | array | Items with `id` (short random string), `text`, and `isHeader` for group headings like "For the sauce". |
| `tags` | array of strings | Library filters. |
| `emoji` | string | The cover sticker. |
| `palette` | string | `tomato`, `jade`, `butter`, `blueberry`, `plum`, `saffron`, `beet`, `sky`, `sage` or `cocoa`. |
| `pattern` | string | `gingham`, `stripes`, `dots`, `waves` or `tiles`. |
| `photos` | array of strings | Scanned page file names, in page order. |
| `isFavorite` | boolean | |
| `createdAt`, `updatedAt` | string | ISO-8601 UTC with milliseconds. `updatedAt` decides sync conflicts. |
| `lastCookedAt` | string, optional | Set when someone finishes cook mode or taps "Mark as cooked today". |
| `rawText` | string | Everything OCR read (or, for websites, the recipe as the page lists it), kept for search and for fixing mistakes later. |
| `deletedAt` | string, optional | Present on deleted recipes (tombstones), so deletions sync. |

Readers ignore keys they don't know, fall back to defaults for missing keys, and treat an
unknown `palette` or `pattern` as `jade` or `gingham`. That keeps older apps working with files
written by newer ones.

## How sync works

There is no server. Each app talks to Google Drive directly with the `drive.file` scope, which
only lets Pinch see files Pinch created.

1. Find (or create) the **Pinch Recipes** folder and its **Photos** subfolder. Folders are
   marked with `appProperties` (`pinchRoot`, `pinchPhotos`) so they're found even if renamed.
2. List recipe files. Each carries `appProperties.recipeId` and `appProperties.updatedAt`
   (milliseconds since 1970).
3. For every recipe, the side with the newer `updatedAt` wins: upload the local file, or
   download the remote one. Photos are uploaded once and downloaded when missing.
4. Deleting a recipe keeps a small tombstone (`deletedAt` set, content cleared) that syncs like
   any other edit, so the recipe disappears everywhere. Its photos are removed from Drive.

Sync runs when the app opens, a few seconds after each edit, and from **Sync now** in Settings.
With no connection it simply waits for the next chance.
