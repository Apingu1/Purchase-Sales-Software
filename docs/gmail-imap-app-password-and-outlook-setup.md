# Gmail App Password IMAP and Outlook account onboarding

Branch: `feature/gmail-imap-app-password-outlook`, based on `feature/mail-oauth-connection-fix`.

## User-facing connections

### Gmail — no Google Cloud registration required for individual users

1. Sign in at [Google Account 2-Step Verification](https://myaccount.google.com/signinoptions/two-step-verification) and **enable 2-Step Verification (2FA)** for that Gmail account.
2. Open the official [Google App Passwords](https://myaccount.google.com/apppasswords) page.
3. Create an App Password labelled **Purchase Sales Software**.
4. Copy Google's newly generated **16-character App Password**.
5. In Android open **More → Business Details → Connected Email Accounts → Gmail — App Password**, enter the exact personal Gmail address and the 16-character App Password (spaces between groups are optional), then tap **Connect Gmail**.
6. The app verifies the password by connecting directly to **imap.gmail.com:993** over TLS with hostname verification, opens a mail folder in **READ_ONLY** mode, and only saves the encrypted password after a successful connection.
7. Repeat for other accounts. There is no per-account OAuth API key or Google Cloud developer configuration.
8. For explanations and the exact same official links, tap **?** on the Gmail card.

**Security and limitations:** These are full-mailbox IMAP credentials, not provider-restricted read-only OAuth grants; the Android implementation deliberately reads only and doesn't send, alter, mark-read or delete mail. The password is encrypted on-device by an AES-GCM key in Android Keystore, is not in Room, is excluded from Android cloud/device-transfer backups, and is removed on Disconnect. The user can also revoke the App Password from their Google Account. Google normally revokes existing App Passwords on a primary Google password change. Google disallows App Passwords on some accounts (Advanced Protection, security-key-only, some managed Workspace accounts).

**Gmail-only IMAP:** This adapter currently accepts personal `@gmail.com` addresses to avoid misleading users into expecting unsupported provider configuration. It is not a universal IMAP connector. We do not store normal Gmail passwords.

**Search:** Each eligible invoice-less purchase is matched to its exact connected mailbox and order number. The IMAP search uses server-side BODY / SUBJECT terms in the account's **All Mail** folder when available, otherwise Inbox. It checks up to 50 matching messages per purchase and up to 30 supported attachments (PDF, PNG, JPEG, up to 25 MB each). It creates Pending Review candidates; users must approve before a document joins purchases or syncs to Dropbox. It cannot find an invoice if the order number exists only in the PDF/image and not searchable message text. Scans are triggered manually from Invoice Finder.

## Outlook / Hotmail / Microsoft 365

Select **Connect Outlook** to open the official Microsoft login portal via Android AppAuth OAuth2 with PKCE. Sign in to each mailbox individually and authorise delegated read access. Microsoft OAuth does **not** accept a Gmail App Password or your Microsoft account's normal password in this app.

**One-time action by software owner:** Register a Microsoft Entra public client application compatible with Outlook personal and business accounts, with **User.Read** and **Mail.Read** delegated scopes, `offline_access`, and **Mobile and desktop applications → custom redirect URI**:

`com.apingu.purchasesales:/oauth2redirect`

For the normal user-facing **Connect Outlook** button to work without entering any settings on each friend's phone, set the GitHub Actions secret `PURCHASE_SALES_MS_CLIENT_ID` to that application's PUBLIC client ID, then create and distribute the signed APK. If the owner has not done this, the app shows an explanatory message and optional **App-owner configuration** section. There are no per-user developer IDs or app keys. The Microsoft sign-in code is present; connection and provider consent have not been live tested with user accounts.

## Validation checklist

- Connect two different Gmail accounts using separate App Passwords and verify they display as distinct linked mailboxes.
- Wrong password should never persist, and should not create a connected database entry.
- Try 16-character password with/without Google-provided whitespace.
- Run Finder with a purchase for A only; confirm B's mailbox is never accessed and candidate includes A as source mailbox.
- Confirm PDF and JPEG/PNG pending previews, reject, approve, deduplication and Dropbox naming.
- Run Finder when Gmail invoice exists outside Inbox but inside All Mail.
- Confirm Disconnect disables scanning and removes that account's stored App Password; other accounts stay connected.
- Confirm backup excludes `purchase_sales_imap_secrets.xml` and restored device requires Gmail reauthentication.
- Microsoft OAuth code return-to-app, token refresh and multiple personal Outlook/Hotmail accounts require live testing.
- Test real APK installs as *updates*, not uninstall/reinstall, so purchasing data is retained.
