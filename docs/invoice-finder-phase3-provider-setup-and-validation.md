# Invoice Finder — Phase 3 provider setup, workflow and validation

Development branch: `feature/invoice-finder-email-dropbox`
Base: `feature/invoice-finder-ui`

## Current implementation

The Android app supports multiple linked purchasing Gmail and Outlook/Microsoft 365 accounts, a foreground user-initiated Run Invoice Finder action, staged invoice PDF/JPEG/PNG previews, explicit approval, and Dropbox upload of every approved order-level invoice.

Each purchase order can be searched **only if**:
- it has no approved invoice in `purchase_invoices`,
- its order number is nonempty,
- its `accountUsername` contains a valid email address,
- **that exact email address** is present, enabled, and marked CONNECTED in `email_accounts`.

An exact mailbox identity is checked by the provider when connecting. Gmail token reuse is re-verified against `/gmail/v1/users/me/profile`. No scanning across multiple mailboxes for a purchase is implemented.

The scan searches messages matching the order number (and Gmail attachment presence). It downloads only eligible PDF/PNG/JPEG attachments to an **app-private temporary cache** (max. 25 MB), where Pending Review can preview them without external document downloads. **Every candidate needs an explicit user approval**, which saves the permanent invoice document; suggestions can be rejected. Existing approved purchases are skipped even if the supplier later sends another invoice; additional documents can still be added manually from the purchase.

## Google Gmail / Workspace setup

1. Create a Google Cloud project for Purchase & Sales Software, enable the **Gmail API**, and configure the OAuth consent screen.
2. Create an **Android OAuth client** for package name `com.apingu.purchasesales` and the SHA-1 of the certificate used to sign the APK users actually install.
3. GitHub Actions currently signs using the repository's retained Android update signing key. Its certificate SHA-256 in the workflow is useful for APK verification; Google OAuth Android registration normally requires **SHA-1**, so extract that digest from the same certificate using keytool / apksigner.
4. Add each purchasing Google account as a test user when the OAuth app is in testing mode; request only `https://www.googleapis.com/auth/gmail.readonly`.
5. On an Android device with Google Play Services installed, go to **More → Business Details → Connected Email Accounts → Connect Gmail**. You can specify the exact email address when connecting several Google accounts.
6. When permission is requested, grant Gmail read-only access and verify that the resulting account appears as CONNECTED.

This app uses **Google Identity Services AuthorizationClient** on the device. It does not store Google refresh tokens in SQLite. For new Gmail API authorisation the Google Cloud configuration and consent/verification requirements are not optional. The Gmail read scope is **restricted**; public distribution may require Google verification and security compliance. If the Google token cannot be obtained silently, reconnect the account interactively in Business Details.

## Outlook / Hotmail / Microsoft 365 setup

1. In Microsoft Entra's application registrations, create an app. For mixed personal and organisational accounts, select a supported-account type that includes personal Microsoft accounts and work/school accounts.
2. Under **Authentication**, register a **Mobile and desktop applications** *custom* redirect URI `com.apingu.purchasesales:/oauth2redirect`. This app uses a standards-based AppAuth browser flow, not the broker-dependent MSAL redirect convention.
3. Enable the app as a public client. Under delegated Microsoft Graph permissions grant **User.Read** and **Mail.Read** (plus normal offline access). A client secret is neither required nor appropriate inside a public Android APK.
4. Set the GitHub Actions repository secret `PURCHASE_SALES_MS_CLIENT_ID` to the Entra **Application (client) ID**. CI embeds this public (not confidential) client ID into the signed APK at build time. A client ID alone is not a credential.
5. Rebuild/download the new signed Android APK. **If that secret is missing, the Microsoft connect button is deliberately disabled.**
6. From Connected Email Accounts, select Outlook/Microsoft 365 and grant read-only mail access.

The Microsoft OAuth authorization-code flow uses PKCE. Its AuthState including a possible refresh token is stored encrypted using an Android Keystore AES/GCM key. Disconnecting clears the app's stored state. A production rollout should evaluate a proper account revocation process, full backup policy and credential-security testing.

**Important:** Provider login has not yet been tested with real Gmail and Microsoft accounts. If the Microsoft registration rejects the custom redirect URI, use the supported Microsoft Android registration / redirect strategy instead of weakening the OAuth flow.

## Dropbox configuration and naming

Keep the existing **More → Business Details → Dropbox** token/key/refresh token settings and enable automatic sync. No extra Dropbox login is introduced.

Approved invoices are uploaded under the already configured accounting period:
`/<Dropbox root>/Accounting Periods/<period>/Purchases/Invoices/`

Names are stable, with the purchase sequence assigned per supplier and purchase date:
- `14 Oct 2026 - Costco #1.pdf`
- `14 Oct 2026 - Costco #2.pdf`
- `14 Oct 2026 - Costco #2 - Invoice 2.pdf`

File extensions are retained for JPEG and PNG. An approved purchase with a supplier refund **still retains its original invoice** in Dropbox for financial traceability. All approved purchase invoices are included in the bulk Documents export. Removed invoices and old names caused by supplier/date edits are queued for cloud deletion and processed on the next successful Dropbox sync. Historical per-item `PUR_<line>_<item>` Dropbox copies are removed only after matching new files upload successfully.

The local invoice file remains the operational copy; Dropbox syncing is asynchronous through WorkManager and requires network access and a working Dropbox token. Dropbox sync errors are retried.

## End-to-end test matrix — required before deploying broadly

1. **Database upgrade:** existing Room 6→7 database and old single invoice migrate without data loss. Verify sales/credit notes and stock unaffected.
2. **Gmail account matching:** connect Gmail A and Gmail B; make a purchase for A. Only A is searched. Invalid email and missing order number are skipped.
3. **Microsoft matching:** connect Outlook C, search a purchase for C, and confirm Gmail A/B are not accessed.
4. **Credentials:** revoke a mailbox or remove a Google account; search must fail safely without scanning another account.
5. **Candidates:** find a correctly numbered PDF; confirm Pending Review can open its file and the purchase remains missing until explicit approval.
6. **Wrong match:** reject candidate; no permanent attachment or Dropbox upload should occur.
7. **Duplicate scan:** rerun finder; the same message/attachment should not create duplicate review rows.
8. **Approved invoice:** rerun finder; that purchase is excluded from search.
9. **Split purchase:** add a second invoice manually, preview both, confirm separate filenames/Dropbox files and only the selected one is removed on deletion.
10. **Dropbox changes:** check name sequence for two Costco purchases on the same day, next day reset, different suppliers, supplier/date edits, deletion and retry offline.
11. **Old Dropbox backups:** old `PUR_...` file is only deleted after its replacement has uploaded.
12. **Search coverage:** orders where the order number is found *only inside PDF content* may require a later PDF-text indexing/fallback search enhancement. Gmail and Microsoft provider keyword search behaviour differs; do not claim exhaustive detection.
13. **Performance:** large attachments, very large mailboxes, intermittent connectivity, mailbox rate limiting and repeated scans.
14. **Security:** verify OAuth consent, signed APK certificate, Keystore handling and token revocation.

## Current limitations

- The scanner runs when the user taps **Run Invoice Finder**; recurring/background scans have not been enabled.
- It processes a bounded set of provider keyword results (50 messages/purchase, 30 attachment candidates/purchase) to avoid uncontrolled mailbox access.
- Scanned image-only PDFs are not OCRed; order numbers visible solely inside a scanned invoice might not be discoverable.
- Only Gmail/Google Workspace and Microsoft/Outlook/Hotmail mailboxes are connected. Other IMAP providers are not yet integrated.
- The app is still offline-first; email and Dropbox scanning need internet access.
- Connecting external providers requires **the user's own provider registration/authorisation**; the repository cannot supply external OAuth access on its own.
