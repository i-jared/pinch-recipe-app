# Setting up Google Drive backup

Pinch works fully offline without this. Google Drive is an optional backup and sync target,
and it needs an OAuth client from Google Cloud before the **Connect Google Drive** button
does anything. Until then, Settings says Drive isn't set up for the build.

There's no Pinch server. Each app signs in with Google directly and asks only for the
`drive.file` scope, which lets it see the files it creates (the **Pinch Recipes** folder) and
nothing else in the person's Drive. Google classifies it as a non-sensitive scope, so it doesn't
need Google's security review.

## 1. Create a Google Cloud project

1. Go to <https://console.cloud.google.com> and create a project, for example "Pinch".
2. Open **APIs & Services → Library**, search for **Google Drive API**, and click **Enable**.

Use one project for both apps. `drive.file` access belongs to the project, so the iPhone and
Android apps only see each other's recipes when their OAuth clients live in the same project.

## 2. Configure the consent screen

In **Google Auth Platform** (older consoles call it **OAuth consent screen**):

1. **Branding:** app name "Pinch", a support email, and optionally a logo.
2. **Audience:** choose **External**. While the app is in *Testing*, add the Google accounts
   that may sign in under **Test users**.
3. **Data access:** add the scope `https://www.googleapis.com/auth/drive.file`.

In Testing mode Google expires sign-ins after seven days, so testers reconnect weekly. Click
**Publish app** when you're ready for real users.

## 3. iOS client

1. **Google Auth Platform → Clients → Create client**, type **iOS**.
2. **Bundle ID:** the app's bundle ID. The default is `com.example.pinch`. Set your own in
   `ios/Config/Local.xcconfig` (see below) and use the same value here.
3. Copy the **Client ID**. It looks like `1234567890-abc123.apps.googleusercontent.com`.
4. Create `ios/Config/Local.xcconfig` (it's git-ignored) from the example:

   ```bash
   cp ios/Config/Local.xcconfig.example ios/Config/Local.xcconfig
   ```

   Then set:

   ```
   PINCH_BUNDLE_ID = com.yourname.pinch
   PINCH_DEVELOPMENT_TEAM = ABCDE12345
   GOOGLE_IOS_CLIENT_ID = 1234567890-abc123.apps.googleusercontent.com
   ```

5. Build and run. No URL scheme registration is needed: sign-in runs in the system browser
   sheet (`ASWebAuthenticationSession`) with PKCE, and the refresh token is kept in the Keychain.

## 4. Android client

1. **Google Auth Platform → Clients → Create client**, type **Android**.
2. **Package name:** `com.example.pinch`, or whatever you change `applicationId` to in
   `android/app/build.gradle.kts`.
3. **SHA-1 certificate fingerprint:** for debug builds, print it with:

   ```bash
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
   ```

   For Play Store builds, also add a client with the **app signing key** SHA-1 from Play
   Console → Setup → App signing.
4. That's it. There's nothing to paste into the code: Google Play services recognizes the
   app by package name and signature.

The emulator needs a system image with Google Play and a signed-in Google account.

## What people see

- **Settings → Connect Google Drive** opens Google's sign-in and consent screen once.
- Pinch creates a **Pinch Recipes** folder in the person's Drive, with one `.json` file per
  recipe and a **Photos** folder for scanned pages. See [recipe-format.md](recipe-format.md).
- After that, sync happens on its own: when the app opens, a few seconds after each change,
  and from **Sync now**. Offline, it waits.
- **Disconnect** stops syncing and revokes access. Recipes stay on the phone and in Drive.

## Troubleshooting

| What you see | Likely cause |
| --- | --- |
| iOS: "Not available in this build" | `GOOGLE_IOS_CLIENT_ID` is empty or `Local.xcconfig` isn't being picked up. Clean the build folder and rebuild. |
| iOS: sign-in sheet shows "Error 400: invalid_request" | The bundle ID on the OAuth client doesn't match `PINCH_BUNDLE_ID`. |
| Android: "Google Drive isn't set up for this build of Pinch yet" | Google Play services returned `DEVELOPER_ERROR`: the package name or SHA-1 on the Android client doesn't match the installed build. |
| "Access blocked: app has not completed verification" | The account isn't listed under **Test users** while the app is in Testing. |
| The other phone doesn't see recipes | Both OAuth clients must be in the same Cloud project, and both phones signed in to the same Google account. |
