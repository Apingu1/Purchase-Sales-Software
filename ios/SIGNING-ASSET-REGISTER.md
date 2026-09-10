# iOS signing and update identity register

This file is the permanent, non-secret identity record for the Purchase & Sales iOS app. Complete the Apple-assigned fields when the first distribution certificate/profile is created. Do not change the bundle identifier or Apple Team for later releases.

| Identity field | Permanent value / record |
|---|---|
| App name | Purchase & Sales |
| Bundle identifier | `com.apingu.purchasesales` |
| Apple Team ID | Complete after enrolling/choosing the Apple Developer team |
| App Store Connect Apple ID | Complete when the app record is created |
| Distribution certificate SHA-256 | Complete from the first signed archive report |
| Provisioning profile UUID | Complete from the first distribution profile |
| Provisioning profile name | Complete from the first distribution profile |
| First released version/build | `1.1.0` / assigned by GitHub Actions |
| GitHub repository | `Apingu1/Purchase-Sales-Software` |
| Source branch | `feature/ios-v1` |

## What makes later updates work

Apple recognises later builds as updates when they use the same bundle identifier and the same Apple Developer Team/App Store Connect app record, with a higher build number. Distribution certificates and provisioning profiles can expire and be renewed; the bundle ID and Team ID must remain fixed.

The certificate (`.p12`), certificate password, provisioning profile and App Store Connect API private key are secrets. Store them in Apple Developer/App Store Connect and GitHub Actions secrets, never in this repository. The release workflow produces a `signing-identity-report.txt` artifact for every signed build so the exact Team ID and certificate fingerprint remain auditable.

## Required GitHub Actions secrets

- `IOS_DISTRIBUTION_CERTIFICATE_BASE64`
- `IOS_CERTIFICATE_PASSWORD`
- `IOS_PROVISIONING_PROFILE_BASE64`
- `IOS_KEYCHAIN_PASSWORD`
- `IOS_DEVELOPMENT_TEAM`

