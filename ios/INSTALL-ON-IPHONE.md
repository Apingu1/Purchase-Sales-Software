# Install Purchase & Sales on an iPhone

An iPhone only accepts apps signed for an Apple account and device, or distributed through TestFlight/App Store. The automatically generated unsigned IPA is a complete device build, but it cannot be opened directly from Safari or Files.

## Recommended: TestFlight

This provides normal installation, automatic updates and no weekly re-signing.

1. Enrol the permanent owner in the Apple Developer Program.
2. In Apple Developer, register the bundle identifier `com.apingu.purchasesales` under the permanent Apple Team.
3. Create the Purchase & Sales record in App Store Connect using that bundle identifier.
4. Create/download an Apple Distribution certificate and App Store provisioning profile.
5. Add the five secrets listed in `SIGNING-ASSET-REGISTER.md` to the GitHub repository.
6. Run **Actions → iOS Signed IPA → Run workflow**.
7. Upload the resulting IPA to App Store Connect/TestFlight, add the user as an internal tester, then install it from Apple's TestFlight app.

Keep the same bundle identifier, Apple Team and App Store Connect record for every later build. GitHub automatically increases the build number so later releases update the installed app.

## Temporary/free installation: Sideloadly or AltStore

1. Download the `Purchase-Sales-Software-iOS-Unsigned-IPA` artifact from the latest successful **iOS Build** workflow.
2. Extract the GitHub artifact ZIP to obtain `Purchase-Sales-Software-iOS-Unsigned.ipa`.
3. Connect the iPhone to a Mac or Windows PC.
4. Open Sideloadly or AltStore and choose the IPA. Sign it with the same Apple ID each time, then install it to the connected phone.
5. On the iPhone, enable Developer Mode if prompted. Trust the developer profile under **Settings → General → VPN & Device Management** if iOS shows that option.

A free Apple ID installation normally needs regular re-signing and is intended for testing. Always use the same Apple ID and bundle identifier if you want a re-signed build to update the existing installation and retain its app data.

## Direct IPA installation

A raw or unsigned IPA cannot be installed by tapping it on an iPhone. Direct installation requires a signed Ad Hoc profile containing that iPhone's UDID, an eligible enterprise distribution method, or Apple distribution through TestFlight/App Store.
