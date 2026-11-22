# Pinch Server

The backend for the native iOS and Android apps. Recipes still live on
phones and work offline. The server adds private backup/restore across devices and invite-only
shared cookbooks. It uses FastAPI, SQLite, one container and one persistent data volume.
Self-hosting works without an OpenRouter key on the server.

The optional [ChatGPT/Codex plugin](../docs/plugin/README.md) uses this same server
and private library. Set `PINCH_PUBLIC_URL` to enable its OAuth endpoints and
`/mcp` transport. Leave it empty to run only mobile backup and sharing.

## Run with Docker

1. Copy `.env.example` to `.env` in this directory. Generate a registration key:

   ```sh
   python3 -c 'import secrets; print(secrets.token_urlsafe(32))'
   ```

   Put the result in `PINCH_REGISTRATION_KEY`. Give this key only to people allowed to create
   accounts. It is separate from account passwords and cookbook invites. An empty key disables
   new registrations; existing accounts can still sign in.

2. For automatic HTTPS, set `PINCH_DOMAIN` to a DNS name pointing at this machine and make
   ports 80 and 443 reachable. Then, from this directory:

   ```sh
   docker compose --profile https up -d --build
   ```

   Caddy obtains and renews a certificate. If you already have an HTTPS reverse proxy, use
   `docker compose up -d --build` and proxy your domain to `http://127.0.0.1:8787` instead.
   The API port is bound to loopback by default. The mobile apps require HTTPS with a
   certificate trusted by the device; they never disable certificate validation or follow
   server redirects. A LAN installation can use a trusted private CA or a DNS-validated
   certificate through your existing proxy.

3. In Pinch, open **Settings → Account → Use your own server**. Enter `https://recipes.example.com`, a username
   (3–64 letters, numbers, `.`, `_`, `-`), and a password (10–256 characters). Turn on
   **Create an account** and enter the registration key. Tap **Create account**.
   Use the same account on another phone to restore the library. Each person should have
   their own account; cookbook sharing does not require sharing passwords.

4. Switch to **Cookbooks** at the top of the library and create a cookbook. It is private until
   you invite someone. Tap **Invite**, choose **Viewer** or **Editor**, and share the HTTPS link.
   It opens an invitation page with an **Open in Pinch** button; it can also be pasted into
   **Cookbooks → Join a cookbook**. Invited people can sign in or create an account without the host's
   registration key. An invitation therefore also authorizes account creation on this server.
   Links keep working until the owner revokes invitations, changes a member's access, or deletes the cookbook.

5. Open any personal recipe and tap **Add to cookbook**, or use **Add recipes** in a cookbook
   to add several at once. Private organization also works offline. Viewers can read, cook and
   save independent copies. Editors can add and remove cookbook entries. Their additions are
   copied into the owner's library, with scanned pages, in the same transaction. Adding the
   same contribution to another of that owner's cookbooks reuses the existing library recipe.

Check `docker compose ps`, `docker compose logs pinch`, or `GET /health` for health. OpenAPI
is available at `/docs` and `/openapi.json`. Request access logging is off. If you enable proxy
logs, keep credentials and request bodies out of them.

### Use a URL path

Both mobile apps accept a server address such as `https://server.example.com/recipes`.
Set `PINCH_ROOT_PATH=/recipes` and configure your proxy to strip that prefix before
forwarding requests to the backend. For Caddy, use `handle_path /recipes/*` with
`reverse_proxy 127.0.0.1:8787` inside your HTTPS site. The API docs will then work at
`/recipes/docs`. The optional OAuth plugin needs its own origin with an empty
`PINCH_ROOT_PATH`; leave `PINCH_PUBLIC_URL` empty for a mobile-only path deployment.

## Managed hosting

The distributed Pinch apps use `https://recipes.lmbrt.net` for managed accounts. To use a
different managed host in your own app build, change the managed origin in both mobile
`ServerSync` implementations. Users of any build can also enter a self-hosted server address
in Settings.

Set `PINCH_MANAGED_URL` to the server's public HTTPS origin and
`PINCH_MANAGED_OPENROUTER_KEY` to the server's OpenRouter key in the local `.env`. This enables
managed account creation, password and browser sign-in, and server-side AI. The original
self-hosted registration endpoint continues to require its registration key.

`PINCH_MANAGED_MODEL` selects the model. `PINCH_MANAGED_AI_PER_ACCOUNT` and
`PINCH_MANAGED_AI_PER_DAY` set the per-account and total daily request caps. The defaults are
30 requests per account and 300 total per UTC day. The mobile apps store a Pinch session
token for managed accounts. The OpenRouter key is held by the server.

## What sync does

- Connecting explicitly merges the device's entire library into that account. Disconnecting
  keeps the local library. Connecting to a different account will therefore also upload those
  local recipes; use a separate device/app profile for separate libraries.
- Sync runs on foreground, every 30 seconds while active, two seconds after edits, and with **Back up now**. Failed attempts
  show an error and retry on the next foreground/edit/manual attempt. Mobile OS background
  execution is not required or promised.
- The recipe with the later `updatedAt` wins as a whole; equal timestamps keep the server copy.
  There is no field merge or version history. Keep device clocks accurate. Server writes are
  transactional, stale requests return the winning version, and clients preserve newer edits
  made during requests. Timestamps are compared at millisecond precision.
- Every scanned page must upload successfully before the server commits its recipe. Photos
  are immutable under a filename; retried uploads are idempotent. Downloads finish before
  applying a restored recipe. An incomplete upload never appears as a completed backup.
- Deletions sync as permanent tombstones so an old phone cannot resurrect a deleted recipe.
  Private photos removed from a recipe are deleted when no other private recipe references them.
- Cookbooks reference recipes in the owner's library. Owner edits appear in every shared
  cookbook containing that recipe. Favorites, last-cooked dates, raw OCR and private attribution
  are excluded from shared responses. Saving a viewer's copy creates fresh recipe/photo IDs;
  subsequent edits to that saved copy stay private.
- Cookbook entries merge independently by timestamp, preserving new contributions when an
  offline device syncs older entries. Equal timestamps favor removal. Cookbook deletion is permanent.
- Owners can rename cookbooks, invite viewers/editors, change roles, remove people, revoke
  invitations and delete cookbooks. Changing roles or removing a person expires outstanding
  invitations. Editors can add or remove entries; viewers cannot. Leaving, removing a person,
  removing an entry or deleting a cookbook preserves all personal library recipes.
- Invitation secrets are stored as hashes. HTTPS links put the secret in a URL fragment, so
  opening the landing page does not send it in a URL request or referrer. The landing page
  opens the registered `pinch://join` app link; custom server domains need no app association file.
  The new mobile clients require this backend version. Startup migrates old groups once,
  retaining shared snapshots, people and invitations; existing members become editors.
  Later private edits are not exposed by migration. Legacy requests for migrated groups return
  an update-required message, so old endpoints cannot bypass revoked invitations or cookbook roles.
- **Delete server account** requires the password and removes that user's backup, sessions,
  shares and owned cookbooks. Other people's saved copies and device copies remain. Operator backups may retain deleted data
  until the operator's retention period expires.

## Security and operating limits

The host can read the database: this is not end-to-end encryption. Use a host you trust.
Passwords are salted and hashed with scrypt; only hashes of random bearer tokens and cookbook
invite codes are stored. Sessions expire after 90 days, and disconnect revokes the session
when the server is reachable. Credentials stay in iOS Keychain / Android Keystore-encrypted
storage and are excluded from recipe backups. An offline disconnect forgets the device's
session; the server token remains valid until expiry or an operator password reset.

Registration/login and invite guessing are rate limited with persistent counters. Authentication
allows 20 requests per client IP per 15 minutes. After 10 failed logins, only that IP/username
pair is blocked for the remainder of its window; successful logins clear that pair's failures.
There is no username-only lockout spanning all client IPs. People behind the same NAT still
share the general IP budget.

Before exposing the API through a reverse proxy, set `PINCH_TRUSTED_PROXY_IPS` to the exact
proxy address(es) seen by Uvicorn. Empty trusts no forwarded headers; wildcards and subnets
are refused at startup. Caddy must overwrite `X-Forwarded-For` with `{remote_host}`. With host
Caddy forwarding to a Docker loopback port, the trusted address is the Pinch Docker network's
gateway. For the optional container Caddy profile, use that Caddy container's IP. If those
addresses change, update the setting before allowing public traffic. An additional CDN/proxy
needs an explicit reviewed trust configuration; do not accept client-supplied IP headers.

Photo uploads authenticate before the application reads any body bytes. At most two uploads
are active per worker (`PINCH_MAX_UPLOADS`, default 2), including database processing; excess
uploads get `503` with `Retry-After`. Actual bytes are bounded to 12 MiB regardless of headers,
with an inactivity timeout of 15 seconds and a total body-read timeout of 180 seconds. Configure
`PINCH_UPLOAD_IDLE_SECONDS` / `PINCH_UPLOAD_TOTAL_SECONDS` as needed. Failed or disconnected
uploads release their slots and do not store partial photos. Existing phones retry failed
syncs on the next foreground/edit/manual backup. Run one worker with the documented memory
limits; upload admission is per process.

Default quota: 1 GiB per user, including private photos, recipes, received contributions and legacy group copies.
Configure `PINCH_USER_QUOTA_MB` to change it. JPEG pages are limited to 12 MiB, JSON requests
to 1 MiB, recipes to 100 pages, and users to 100 owned cookbooks and 100 joined cookbooks. Incomplete uploads count toward
quota. SQLite keeps freed pages for reuse; deleting content does not immediately shrink the
file. This release targets small family/community servers, uses full recipe listings, and
runs one API worker on local disk. Do not scale multiple replicas against a network-mounted
SQLite volume. No password email/reset service is required; the host can reset passwords:

```sh
docker compose exec pinch python admin.py reset-password alice
```

This invalidates all that account's sessions. Password input is hidden. The built-in docs
show API schemas, not account data; all recipe, photo and cookbook operations require a session.

## Back up and restore the server

Device sync propagates deletions, so keep independent server snapshots. The complete database
includes photos, accounts and cookbooks. Use the SQLite backup API rather than copying a live
`.sqlite3` file without its WAL:

```sh
docker compose exec pinch python admin.py backup /data/snapshot.sqlite3
docker compose cp pinch:/data/snapshot.sqlite3 ./snapshot.sqlite3
```

Choose a fresh filename each time; the command refuses to overwrite a backup. Move snapshots
to another machine/disk and protect them as private data. To restore a snapshot:

```sh
docker compose stop pinch
docker compose run --rm --no-deps --user 0 -v "$PWD/snapshot.sqlite3:/restore.sqlite3:ro" pinch \
  sh -c 'rm -f /data/pinch.sqlite3-wal /data/pinch.sqlite3-shm && cp /restore.sqlite3 /data/pinch.sqlite3 && chown 10001:10001 /data/pinch.sqlite3'
docker compose up -d pinch
```

Restoring replaces all server data with the snapshot. Keep the current database separately
first. Older snapshots may restore previously valid sessions; reset affected account
passwords if tokens might have been compromised. Phones can re-upload newer local versions
on their next sync. Do not use `docker compose down -v` unless you intend to erase volumes.

## Run and test without Docker

Requires Python 3.12+ and [uv](https://docs.astral.sh/uv/).

```sh
uv sync --frozen
export PINCH_REGISTRATION_KEY='your-long-random-registration-key'
uv run uvicorn server:app --host 127.0.0.1 --port 8787 --no-access-log
```

Put a trusted HTTPS proxy in front of this for phone use. Tests use isolated temporary
SQLite databases; no running server or real account is needed:

```sh
uv run pytest
```

Dependencies are locked in `uv.lock`. Docker installs the matching hash-checked
`requirements.txt`. When upgrading, regenerate it with:

```sh
uv lock
uv export --frozen --no-dev --no-emit-project -o requirements.txt
```

The API uses FastAPI's [lifespan support](https://fastapi.tiangolo.com/advanced/events/) to
initialize its database and Python's [scrypt implementation](https://docs.python.org/3/library/hashlib.html#hashlib.scrypt)
for password hashing.
