# Hivescope audit remediation tracker

**Reference report:** [Postilla fact sheet](https://scrutiny.hivescope.xyz/postilla.html)  
**Audit date:** 20 November 2026  
**Work branch:** `staging/postilla-audit-remediation-20261120`

| Audit observation | Remediation | Current status |
|---|---|---|
| DM translation always used DeepL | DM translation now respects the selected engine; DeepL requires a per-message confirmation | Implemented in staging; CI and manual device tests pending |
| No privacy policy | Added `docs/PRIVACY.md`, with external services, local storage, permissions, diagnostics, Nostr and wallet disclosures | Drafted from source review; requires final README link and a legal/privacy review before treating as definitive |
| README/Zapstore mention DeepL but omit ML Kit | Zapstore metadata now names on-device ML Kit and optional DeepL; README update intentionally deferred | Zapstore metadata updated in staging; README awaiting staging verification |
| No NIP-89 kind 31990 announcement found | Added a publication checklist and draft procedure | Not published; official publisher pubkey and Android handler URI must be confirmed and signed |
| No reproducible builds | Added a manual workflow that performs two clean signed staging builds and compares APK bytes; staging also emits build provenance | Workflow added but not yet executed; full independent reproducibility is not claimed |
| No independent security audit found | Added CodeQL static-analysis workflow and a responsible disclosure policy | Automated scan pending; this is not a substitute for an independent security audit |
| Breez/Spark service visibility could not be checked | Privacy notice explicitly identifies the unknowns and third-party responsibility | Documented limitation; requires network-level testing and provider review |
| ML Kit traffic from this installation could not be checked | Privacy notice separates on-device text processing from SDK diagnostics/model downloads | Documented from Google's disclosure; live traffic capture remains outstanding |

## Guardrails

- No change to `main`, official releases, application ID, or signing identity has been made by this remediation branch.
- No release has been published.
- `README.md` is intentionally unchanged until the staging APK has passed CI and the user has had an opportunity to test it.
- A green CI build is not manual device validation.
- The NIP-89 finding remains open until the signed event is published and retrieved from relays.
- The independent security-audit finding remains open until an external reviewer completes a scoped assessment.
