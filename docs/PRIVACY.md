# Postilla — Privacy & Security

**Last reviewed:** 20 November 2026  
**Project:** [Postilla for Android](https://github.com/isolabellart/postilla-android)  
**Publisher:** IsolabellArt  
**Application ID:** `com.isolabellart.darkwispenhanced`

This document describes the data flows visible in the Postilla Android source code. Postilla is a decentralized Nostr client: it does not operate the public relays, Blossom servers, or third-party services to which a user chooses to connect. Those services have their own operators, logs, retention practices, and privacy terms.

This is a technical transparency notice, not a promise that every third-party service has been independently audited. Network traffic from a signed-in installation and the internal processing of Breez/Spark services have not been independently captured or verified.

## 1. Nostr identity, keys, and messages

- A locally managed Nostr private key is stored using AndroidX `EncryptedSharedPreferences`, backed by Android Keystore. This is local device storage; it is not a Postilla-operated cloud key vault.
- Users can instead use a compatible remote signer, such as Amber (NIP-55), or use a read-only public key. In remote-signing mode, signing requests are handled by the selected signer app.
- Public Nostr events (for example posts, reactions, profile metadata, listings, and public comments) are sent to the relays selected or configured by the user. Public events should be treated as public information. Relay operators may log connection metadata such as IP address and request timing.
- NIP-17 private messages are encrypted before publication using the Nostr gift-wrap design. Relays still receive network and envelope metadata needed to route and store events; end-to-end encryption does not make relay operators blind to all metadata.
- If the user chooses a Nostr Wallet Connect (NIP-47) provider, wallet requests and payment information are sent to that provider as required by the protocol.

## 2. Translation

Translation is initiated by the user; Postilla does not automatically translate conversations.

### On-device translation — Google ML Kit

When **On-device (ML Kit)** is selected, the text is identified and translated on the Android device. Google states that the text submitted for on-device translation is not sent to Google's servers for translation.

ML Kit may download language models when first needed. Google also documents diagnostic and usage data for the ML Kit SDKs, including device/app information, performance metrics, and language information (configured or identified languages). The barcode-scanning ML Kit component is also used for QR scanning. These SDK-level disclosures are separate from the content of the text being translated.

Sources: [ML Kit Translation](https://developers.google.com/ml-kit/language/translation) · [Google ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure).

### Cloud translation — DeepL

When **DeepL API** is selected and the user requests a translation, the selected text is sent over HTTPS to DeepL's API (`api-free.deepl.com` or `api.deepl.com`, depending on the user's API key). DeepL processes the text under its own service terms and privacy policy. The user's DeepL API key is stored locally using Android encrypted preferences.

For private NIP-17 messages, the app asks for a separate confirmation before sending the decrypted message text to DeepL. Declining the prompt keeps the text on the device. The Nostr message itself is never changed by translation.

Source: [DeepL Privacy Policy](https://www.deepl.com/privacy).

## 3. Other external services

Depending on the features used and the user's configuration, Postilla may contact:

- **Nostr relays:** retrieve and publish Nostr events, including public content and encrypted message envelopes.
- **Blossom media servers:** upload and retrieve media selected by the user. The chosen server receives the uploaded file and connection metadata. The user can configure Blossom servers.
- **Google ML Kit:** download translation language models and provide SDK diagnostic/usage data; its barcode-scanning component supports QR scanning.
- **DeepL:** receive text explicitly selected for cloud translation.
- **CoinGecko:** provide Bitcoin exchange-rate data used by the app.
- **Coinbase:** provide currency/exchange-rate data used by marketplace currency display.
- **YouTube:** thumbnail images may be retrieved for YouTube links.
- **Breez SDK / Spark:** provide the integrated Lightning wallet. Wallet connection, synchronization, invoices, and payments require communication with Breez/Spark infrastructure. Their services may process wallet and payment-related data according to their own terms.
- **Nostr Wallet Connect providers:** if configured, process wallet requests and related payment information.

The exact relay and Blossom servers depend on defaults, user settings, and the event data being accessed. Postilla cannot control the retention or onward disclosure practices of these independent operators.

## 4. Local data and diagnostics

Postilla stores account settings, relay preferences, cached content, and wallet-related data on the device. Private keys, DeepL API credentials, and Spark wallet recovery material use Android encrypted preferences where implemented.

A crash report is written to app-internal storage when the app crashes. It includes the timestamp, app version, device manufacturer/model, Android version, and stack trace. It is **not automatically uploaded**. If the user chooses **Send Report**, the report is sent as a NIP-17 encrypted message to the Postilla developer through the app's fixed report-relay list.

Optional diagnostic logging is disabled by default. When enabled by the user, diagnostic logs are stored in app-internal storage. The user may inspect, clear, or share those logs from the app's diagnostic flow. Shared logs may contain technical details and should be reviewed before sending.

No Firebase, Google Play Services analytics, advertising SDK, or third-party crash-reporting SDK was identified in the reviewed Gradle dependency configuration. This is a source review observation, not a guarantee about every transitive dependency or future release.

## 5. Android permissions

The manifest requests:

- Internet access, for Nostr, media, translation, rates, and wallet services.
- Camera, for QR scanning (camera hardware is not mandatory).
- Notifications, for user-visible notifications.
- Vibration.
- Foreground-service permissions for media playback and the embedded Tor service.
- Legacy external-storage write permission only on Android versions where it is applicable (maximum SDK 28).

The reviewed manifest does not request location, microphone, or contacts permissions.

## 6. Retention and user choices

- Public Nostr events may remain available on relays after publication. Deletion requests (NIP-09) are requests to relays, not a guarantee that every relay or recipient will erase a copy.
- Private NIP-17 message envelopes may be retained by relay operators. Encryption protects message content, but does not remove relay metadata or copies already received by participants.
- Files uploaded to Blossom servers are governed by the selected server's policies.
- DeepL, Google, Breez/Spark, Coinbase, CoinGecko, YouTube, and wallet providers have their own retention and privacy terms.
- Local app data can generally be removed by clearing app storage or uninstalling the app. Back up wallet recovery material securely before removing app data.

Users can reduce external data sharing by choosing on-device translation, reviewing relay and Blossom settings, and avoiding optional external integrations they do not need.

## 7. Security contact and reporting

Please report suspected security vulnerabilities privately through GitHub's repository security reporting mechanism, if available, rather than posting exploit details publicly: [Postilla repository](https://github.com/isolabellart/postilla-android).

Postilla is open source under the MIT license. A source-code review or a successful build is not equivalent to an independent security audit. No independent, comprehensive security audit is claimed by this document.

## 8. Change history

- **20 November 2026:** initial privacy and data-flow notice. Clarified the distinction between on-device ML Kit translation and DeepL cloud translation, including explicit per-message consent for private-message translation.
