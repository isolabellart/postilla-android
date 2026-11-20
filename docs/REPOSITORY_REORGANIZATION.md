# Repository integration record — 20 November 2026

## Source references

The controlled mainline integration was assembled from three existing histories:

- Previous `main`: `d350e4157f3d564a600b968bca59eb704f12927f`
- Postilla 1.0.42 release commit: `b35e582318010cfd5c59749d15b5027483e64cde`
- Later Postilla documentation/rebrand tip: `2b3f48937584e7f860d72c9e126de31bc2433dbf`

Integration commit: `428987490c11f17589bbdfe779c2aea0aa1fc1e4`.

It has three Git parents, preserving all three histories. Its application/build tree comes from the release 1.0.42 line; the later Postilla README, brand documentation, Zapstore workflows/manifest and five current PNG screenshots are overlaid from the newer documentation tip. The old main-only README/manifest were superseded by the Postilla versions; the shared `.gitignore` was identical and the tracked `.env` had been removed in both lines.

## Verified version identity

The release source branch `release/postilla-v1.0.42` and tag `v1.0.42` both point to `b35e582318010cfd5c59749d15b5027483e64cde`. The Gradle source on that branch reports version 1.0.42 / version code 42.

The GitHub Actions run `44712898088` for that exact release commit completed successfully on 20 November 2026. This is a recorded prior run, not a new build initiated by this reorganization.

## Recovery references

- `backup/pre-reorg-main-tip-20261120` → `d350e4157f3d564a600b968bca59eb704f12927f`
- `backup/pre-reorg-feature-tip-20261120` → `2b3f48937584e7f860d72c9e126de31bc2433dbf`
- `backup/pre-postilla-merge-integration-20261120` → `d350e4157f3d564a600b968bca59eb704f12927f`
- `backup/postilla-merged-mainline-pre-workflows-20261120` → `428987490c11f17589bbdfe779c2aea0aa1fc1e4`
- `backup/postilla-mainline-pre-final-release-guard-20261120` → `09f552c7121113145ed8dbdad9dc6268979d3905`

No tag, published release, signing key, application ID or historical release branch was modified by this integration commit. The former feature tip is retained as a source of the later documentation and rebrand assets, not treated as the current application code base.


## Automated validation of the integration branch

On commit `09f552c7121113145ed8dbdad9dc6268979d3905`:
- PR CI run `44772029609`: passed.
- Push CI run `44772017278`: passed.
- Staging run `44772017235`: passed unit tests, signed staging build, package/version/certificate verification and artifact upload.
- Staging artifact: `postilla-staging-1.0.42`, artifact ID `14646974593`.

These are validations of the pre-final-release-guard commit. The release-guard changes are validated by the subsequent CI and staging runs before merging the integration PR. No production APK, GitHub Release or Zapstore publication was initiated during this reorganization.
