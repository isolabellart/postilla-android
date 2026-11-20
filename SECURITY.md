# Security Policy

## Supported versions

Security fixes are developed against the current maintained Postilla branch and the latest published release. Older releases may not receive fixes.

## Reporting a vulnerability

Please do not publish exploit details in a public issue before a fix is available.

Use GitHub's **Report a vulnerability** feature in the repository Security tab if private vulnerability reporting is enabled. If it is unavailable, contact the maintainer through the repository's private contact channels before disclosing technical details publicly.

Include:
- affected app version and Android version;
- the relevant feature or protocol flow;
- clear reproduction steps;
- expected and observed behavior;
- proof-of-concept material only when safe to share.

Do not include private keys, seed phrases, wallet recovery material, DeepL API keys, or other credentials in a report.

## Scope and limitations

Postilla is an independently maintained open-source Android Nostr client. The repository includes a scheduled GitHub CodeQL workflow for Kotlin and Java. Its results must be reviewed and triaged; a clean CodeQL run, successful CI build, or source review is not a comprehensive independent security audit. Wallet and third-party service behavior may require separate review by their respective providers.
