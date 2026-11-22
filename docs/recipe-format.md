# Recipe format

Both apps store every recipe as one JSON file and sync that JSON to an optional self-hosted server.
Because the format is shared, a recipe saved on an iPhone opens on an Android phone and
the other way around.

- iOS: `Application Support/Pinch/recipes/<id>.json` and `Application Support/Pinch/photos/`
- Android: `files/recipes/<id>.json` and `files/photos/`
- Server: JSON recipe records and JPEG blobs in a persistent SQLite database, scoped to each account

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
| `recipePhotos` | array of strings, optional | Attached recipe photo file names in gallery order. |
| `bannerPhoto` | string, optional | File name from `recipePhotos` used as the banner. Omit to use the patterned cover. |
| `isFavorite` | boolean | |
| `createdAt`, `updatedAt` | string | ISO-8601 UTC with milliseconds. `updatedAt` decides sync conflicts. |
| `lastCookedAt` | string, optional | Set when someone finishes cook mode or taps "Mark as cooked today". |
| `rawText` | string | Everything OCR read (or, for websites, the recipe as the page lists it), kept for search and for fixing mistakes later. |
| `deletedAt` | string, optional | Present on deleted recipes (tombstones), so deletions sync. |

Readers ignore keys they don't know, fall back to defaults for missing keys, and treat an
unknown `palette` or `pattern` as `jade` or `gingham`. That keeps older apps working with files
written by newer ones.

## How sync works

Both apps use the HTTPS API in [`backend/`](../backend/README.md). The server returns only the
signed-in account's recipes, including tombstones. Clients upload scanned pages before their
recipe. The later `updatedAt` wins the entire recipe; equal timestamps keep the server copy.
Clients download missing pages before applying a remote recipe, and re-check local timestamps
so an edit made during a request isn't overwritten.

Deleting a recipe keeps its tombstone so deletion reaches other devices and removes it from
the owner's cookbooks. Cookbook entries refer to recipes in the owner's library; edits there
appear in the cookbook. Saving someone else's cookbook recipe creates an independent personal copy.

Sync runs on foreground, every 30 seconds while active, two seconds after edits, and with **Back up now**. Failed attempts
retry at the next foreground/edit/manual sync. This is sync, not a versioned archive; see
[server backups](../backend/README.md#back-up-and-restore-the-server) for disaster recovery.

Server requests validate schema 1, safe file names, dates and size limits; local readers remain
lenient for older local files. OpenAPI documentation is served at `/docs`.

### Cookbooks

Both apps store cookbook JSON in `recipes/_cookbooks/<id>.json`. Each document has `id`,
`name`, `updatedAt`, optional `deletedAt`, and `entries`. Each entry has `recipeId`, `updatedAt`
and `removed`. Dates use the recipe format's millisecond precision. A recipe may belong to
several cookbooks; removing an entry or deleting a cookbook preserves the personal recipe.

Metadata uses the later timestamp. Entries merge independently, with removal winning ties,
so an offline device cannot erase an editor's new contribution. Cookbook deletion is sticky.
The server returns owner documents through `/v1/cookbooks`; collaborators receive permitted
shared content instead. Adding as an editor copies the recipe into the owner's library and
links it into the cookbook atomically. Copies are deduplicated across that owner's cookbooks.

### Attribution for saved cookbook recipes

`origin` is an optional object with `groupId`, `shareId`, `groupName` and `sharedBy`.
It travels with the private recipe across devices so Pinch can show where a saved copy came
from and reopen it instead of creating duplicates. These field names are retained for compatibility
with older recipes. Cookbook responses strip this private tracking object. Removing a cookbook
or entry does not remove the attribution or the saved personal recipe.
