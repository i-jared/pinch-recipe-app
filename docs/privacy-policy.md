# Pinch privacy policy

_Effective October 2, 2026_

Pinch is a recipe box app for iPhone, iPad and Android, published by Whatado, LLC ("we").
This policy explains what happens to your information when you use it.
It also covers the optional Pinch plugin for ChatGPT and Codex when connected to
a Pinch server.

## The short version

- Pinch works without an account and has no ads, analytics or tracking. You can sign in to
  Pinch or connect to a self-hosted server for backup and shared cookbooks.
- Your recipes and photos are saved on your device. Signing in also sends them to the server
  that hosts your account.
- Managed accounts use AI for recipe reading and Cook Mode ingredient matching. Self-hosting
  users can enable those features with their own OpenRouter key.

## What stays on your device

Recipes, the photos you scan or choose, your settings and your cooking timers are saved on
your device. Recipes work offline. We do not operate the optional self-hosted servers.
Uninstalling the app does not delete data already backed up or shared on your chosen server.

## AI reading

When you sign in to a managed Pinch account, the Pinch server forwards recipe-reading and Cook Mode
requests to OpenRouter using the service's key. If you use your own server, you can enable AI under
**Self-hosting → Reading recipes**. In that case your phone connects to OpenRouter directly.

- The photos of recipe pages you add, text you paste, and the web pages you import are sent
  over an encrypted connection to [OpenRouter](https://openrouter.ai).
  OpenRouter passes them to an AI model provider (currently for the DeepSeek V4.1 Flash model),
  which turns them into a recipe and sends it back. Text read from the photos on your device, and
  the address of an imported page, are sent along to help.
- When AI ingredient matching is active, opening Cook Mode can send the saved
  recipe's ingredient list and directions, including section headings, to OpenRouter and the same
  model provider. They return which ingredients go with each step.
- OpenRouter receives the content needed for the recipe being imported or cooked. That excludes
  your other recipes, contacts, and location.
- The managed server processes AI requests in memory. It does not log or separately save the
  input sent to AI. The saved recipe is part of your account's library backup. OpenRouter and
  the model provider handle requests under their own policies. See OpenRouter's
  [privacy policy](https://openrouter.ai/privacy).
- If you use your own OpenRouter key, it is kept on your device, in the iOS Keychain or encrypted with the Android
  Keystore. It is sent only to OpenRouter and is never included in backups.

Signing out of a managed account stops managed AI requests. Self-hosting users can turn off
ingredient matching or remove their AI key under **Self-hosting → Reading recipes**. Pinch has
on-device reading and ingredient matching for offline use.

## Managed subscriptions

When you subscribe, Stripe collects and processes your payment details. Pinch stores a Stripe
customer reference and subscription access status with your account. Use the web billing portal
to update your payment method or cancel your subscription. [Stripe privacy policy](https://stripe.com/privacy)

## Adding recipes from websites

When you add a recipe from a link, your device loads that web page directly from the website,
just as a web browser would. The website sees an ordinary visit from your device. Pinch keeps the
recipe and the page's address and doesn't keep cookies from the site.

## Server backup and shared cookbooks (optional)

If you connect a server in Settings, your recipes (including notes, OCR text, favorites and
cooking history), scanned pages, and attached recipe photos are sent over HTTPS to the server
you choose. The server
stores these under your account. Its operator can access them; backups are not end-to-end
encrypted. Your username, password hash and session-token hash are stored on that server.
Your password is used to authenticate and is not saved in the app. The device's session token
is kept in iOS Keychain or Android Keystore-encrypted storage.

Cookbooks are private until you invite people. Members see the recipes added to that cookbook,
including notes, scanned pages, and attached recipe photos. Favorites, last-cooked dates, and raw
OCR are private. Viewers can
read, cook and save independent copies. Editors can add or remove cookbook entries. An editor's
addition is also copied into the owner's personal library. Owner edits appear in shared cookbooks.
Removing an entry, leaving a cookbook, removing a member or deleting a cookbook does not delete
personal library copies. Deleting an account cannot remove copies already saved by other people.

Disconnecting stops sync and keeps both local and server data. **Delete server account** in
Settings removes your server backup, shares, account and cookbooks you own, after password
confirmation. Local device copies remain. The server operator controls independent backup
retention and can help with account access or deletion requests.

Managed users can also [delete their Pinch account on the web](https://recipes.lmbrt.net/account/delete)
using their username and password. This cancels their subscription. Stripe may retain payment records.

## ChatGPT and Codex plugin (optional)

When you connect the Pinch plugin, you sign in directly on the configured Pinch
server and consent to access to your synced recipes. Your password is sent only
to that server, not to ChatGPT or Codex. The server issues separate OAuth tokens
for the connection and stores token hashes, the linked account, client metadata,
permissions and expiry times. The host holds the credentials used for requests.

Search results and requested recipes are sent from that server to ChatGPT or
Codex to answer your request. These may include titles, ingredients, directions,
notes, tags, source information and favorites. The plugin does not return scanned
photo files, raw OCR text or cooking history. Your use of ChatGPT or Codex and its
handling of those results are governed by your OpenAI account settings and
[OpenAI's privacy policy](https://openai.com/policies/privacy-policy/).

If you grant write permission, the plugin can save new recipes and change
favorites when requested. These changes are stored on the same server and sync
to connected phones. The server retains request identifiers and content hashes
to prevent duplicate saves. The plugin cannot delete recipes or accounts, share
recipes to cookbooks, fetch recipe websites or access an OpenRouter key.

Disconnect the plugin in ChatGPT or Codex to revoke its connection. The server
also supports revocation; the operator can reset your password to invalidate all
connections. Disconnecting does not delete saved recipes. Deleting the server
account removes its OAuth grants and save-request records along with the data
described above. The server operator controls independent backup retention.

## Device permissions

- **Camera:** to scan recipe pages. Photos stay on your device unless you use AI reading, server backup or sharing.
- **Photos:** to choose recipe photos, and on iPhone to save recipe cards you share.
- **Notifications and alarms:** to ring when a cooking timer finishes.

## Sharing recipe cards

When you share a recipe as a picture, Pinch creates the image on your device and hands it to the
app you pick. What happens next is up to you and that app.

## Children

Pinch isn't directed at children under 13, and we don't knowingly collect personal information
from children under 13.

## Changes

If this policy changes, we'll update this page and its effective date. Material changes will be
noted in the app's release notes.

## Contact

For privacy questions or requests, contact Whatado, LLC at [jaredclambert@gmail.com](mailto:jaredclambert@gmail.com).
