# Mail account connection troubleshooting — October 2026

Branch: `feature/mail-oauth-connection-fix` — follows Invoice Finder Phase 3.

## Why Gmail says "authorisation cancelled"

Previously the Android screen displayed "Google account authorisation was cancelled" for **any** `Activity.RESULT_CANCELED`. Google can return an unsuccessful authorisation result for reasons other than a deliberate user cancellation, including OAuth application setup errors. The updated app parses the returned intent when available, displays Google API status codes, and keeps a useful diagnostic visible on the email account settings page. A code 10 generally indicates an Android OAuth client/signature/configuration problem rather than an incorrect mailbox password.

**App code cannot create Google Cloud OAuth registration or grant Gmail access for you.**

### Google setup

1. Go to https://console.cloud.google.com/ and select/create the project intended for this application.
2. Enable the **Gmail API** under APIs & Services → Library.
3. Configure **Google Auth Platform** (consent/branding/audience/data access). Request `https://www.googleapis.com/auth/gmail.readonly`. This is a *restricted* scope; Google testing, verification and security-assessment requirements may apply to wider distribution.
4. Create an **OAuth client ID** for application type **Android**, with the APK's exact package and signing certificate:
   - Package name: `com.apingu.purchasesales`
   - SHA-1 of the retained CI signing certificate: `7E:BC:B7:C9:7C:F2:A2:28:86:AE:52:27:61:14:5B:2A:F0:B4:68:63`
5. If your consent app is in **Testing**, add every purchasing Google mailbox used in your tests as a test user. For Workspace-managed mailboxes, an administrator may need to allow Gmail access.
6. Install an APK signed using that same certificate. The updated app shows **Installed APK certificate SHA-1**, so verify its value exactly before trying to connect.
7. Open More → Business Details → Connected Email Accounts → Connect Gmail, choose the intended account, and approve read-only access.

**Don't paste a Google client secret into the Android app.** Native Android Google Identity authorisation uses the package/signing certificate and Google-managed permissions.

## Why Outlook / Hotmail / Microsoft 365 is grey

The earlier APK disabled the Microsoft connection button unless `PURCHASE_SALES_MS_CLIENT_ID` was embedded at build time through a GitHub Actions secret.

The revised app lets you **enter and save the Microsoft Application (client) ID under Connected Email Accounts**, then connect immediately; a rebuild solely to provide a public client ID is no longer required. An Application (client) ID is public configuration; never paste a Microsoft *client secret* into the app.

### Microsoft setup

1. Open https://entra.microsoft.com/ → Identity → Applications → App registrations → New registration.
2. For a combination of personal Outlook/Hotmail and organisation Microsoft 365 addresses, use **Accounts in any organisational directory and personal Microsoft accounts**.
3. Under Authentication, add **Mobile and desktop applications**, using custom redirect URI:
   `com.apingu.purchasesales:/oauth2redirect`
4. Configure a **public client**, and grant the relevant Microsoft Graph **delegated** permissions `User.Read`, `Mail.Read` and `offline_access`. Organisation policies may require administrator approval.
5. Copy **Application (client) ID** (a GUID such as `12345678-1234-1234-1234-123456789abc`) from the registration Overview.
6. In the updated APK go to Business Details → Connected Email Accounts → Microsoft / Outlook OAuth setup, paste the **client ID**, tap **Save client ID**, then tap **Connect Outlook / Microsoft 365**.
7. Verify the exact mailbox appears as CONNECTED. Multiple accounts can use the same app registration, provided the required user consent is granted individually.

Microsoft client ID can alternatively still be embedded by `PURCHASE_SALES_MS_CLIENT_ID` at build time. The saved device setting takes precedence.

## Still required before production rollout

- Perform live consent/sign-in tests for Google and Microsoft on the target phone; passing Kotlin compilation is not equivalent to successful OAuth.
- Verify the exact connected mailbox matches each purchase. No cross-mailbox searching should take place.
- Verify the scan finds attachments matching the purchase order number, and manual review precedes approval.
- Verify app upgrades retain existing local records and access to approved PDFs.
- Verify Dropbox uploads of multiple approved documents, including deletion and retry after a network outage.
