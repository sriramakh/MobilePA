# MobilePA

MobilePA is a native Android personal assistant for Android 8.0 and later. Its three features are SMS-to-email rules, quick memories with reminders, and collections of shared links, images, and PDFs. Typed commands use a small on-device parser. Your notes, attachments, rules, and delivery queue live in app-private storage. Uninstalling the app removes its local data.

## Install and open

Copy the debug APK from `app/build/outputs/apk/debug/app-debug.apk` to your phone and open it, or install it on a connected Android device with `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Open **MobilePA** from the launcher. The Memory and Saved tabs work immediately.

## SMS forwarding

1. Open **Rules → Connect sending account**. The included APK supports **SMTP with app password** directly. Enter your sending email address, your provider’s implicit-TLS SMTP host and port (usually 465), and a provider-issued app password. The provider must accept the email address as the SMTP username. MobilePA encrypts these settings with an Android Keystore key. Use the test-email dialog to check delivery to your own address.
2. Tap **New rule**. Enter the sender ID exactly as shown on the SMS, one or more comma-separated phrases, and your destination email. Leave the sender blank to match any sender. Matches ignore capitalization. Common OTP and verification-code phrases are excluded.
3. Use **Test a sample** to check a pasted SMS. **Preview recent inbox matches** requests separate access to read historical SMS and shows up to 30 matches from the last 30 days. To email older matches, select the specific messages and confirm the destination.
4. Save the rule, tap **Enable**, and grant incoming SMS access. The rule starts with future messages. You can pause or edit it from **Rules**.
5. Open **Delivery activity** to see the sender, matched phrase, original text, destination, and delivery status. **Sent** means the provider accepted the message. Without internet, matches stay queued. If delivery was interrupted after submission, **Review** lets you check Sent mail before retrying.

Gmail accounts that allow app passwords can use `smtp.gmail.com` with implicit TLS on port 465. App-password availability depends on the account. [Google app-password setup](https://support.google.com/mail/answer/185833)

**Gmail with Google sign-in** is a separate send-only OAuth option. For a build you distribute, enable the Gmail API in your Google Cloud project, configure the consent screen and test users, then create an Android OAuth client for package `dev.mobilepa` and the signing certificate’s SHA-1 from `gradlew signingReport`. Choose that option on a phone with Google Play services. MobilePA requests `gmail.send`. The SMTP path works independently of this registration. [Android authorization](https://developer.android.com/identity/authorization) · [Gmail scopes](https://developers.google.com/workspace/gmail/api/auth/scopes)

Android restricts SMS permissions. The installation method and device policy must permit them. A Play Store release needs an eligible use and review. If access is unavailable, the affected rule stays paused. The app handles carrier SMS. [Android permissions](https://developer.android.com/reference/android/Manifest.permission#RECEIVE_SMS) · [Play SMS policy](https://support.google.com/googleplay/android-developer/answer/10208820)

## Quick memories and reminders

- On **Desk**, type *Remember I parked on B2, pillar 17*. Review the note, optionally choose a photo, and tap **Save**. Later type *Where did I park?* to find it.
- Type *Remind me tomorrow at 6 PM to take the parking pass*. Confirm the displayed local date and time. If the time needs clarification, use the date and time pickers.
- Allow notifications when you save your first reminder. Reminders are scheduled locally and restored after a reboot. Android can defer an ordinary reminder while conserving power. You can open, edit, and delete reminders in **Memory**.

## Saved collections

From another app, select **Share → MobilePA** for a link, an image, or a PDF. Choose an existing collection or type a name such as *Jaipur*. You can also add an item from **Saved**. Type *Show my Jaipur plans* on Desk to find the collection. Open an item to view its contents or delete the local copy.

**Available offline** means the text or file has been saved locally. Image and PDF imports have a 100 MB limit per file. **Link only** stores a bookmark. Opening the remote destination or importing a cloud-only file can require internet. The system picker grants access to the chosen file.

## Build and verify

Use JDK 17 or 21, Android SDK platform 36, and Build Tools 36.0.0. On Windows, from the project directory, run:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

With a dedicated test emulator connected, `gradlew :app:connectedDebugAndroidTest` also runs the authenticated TLS mail test against a loopback server using synthetic messages and credentials. On macOS or Linux use `./gradlew`.
