package com.darkwisp.app.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostillaUpdateVersionTest {
    @Test fun newerPatchVersionIsDetected() {
        assertTrue(isNewerVersion("1.0.47", "1.0.46"))
    }

    @Test fun newerMinorAndMajorVersionsAreDetected() {
        assertTrue(isNewerVersion("1.1.0", "1.0.99"))
        assertTrue(isNewerVersion("2.0.0", "1.99.99"))
    }

    @Test fun sameOrOlderVersionsAreIgnored() {
        assertFalse(isNewerVersion("1.0.46", "1.0.46"))
        assertFalse(isNewerVersion("1.0.45", "1.0.46"))
    }

    @Test fun malformedAndPrereleaseVersionsAreRejected() {
        assertFalse(isNewerVersion("v1.0", "1.0.46"))
        assertFalse(isNewerVersion("1.0.47-beta", "1.0.46"))
        assertFalse(isNewerVersion("latest", "1.0.46"))
    }
    @Test fun canonicalVersionedReleaseAssetIsAccepted() {
        assertTrue(isPostillaReleaseApkAsset("Postilla-v1.0.63.apk", "", "1.0.63"))
    }

    @Test fun legacyReleaseAssetIsAccepted() {
        assertTrue(isPostillaReleaseApkAsset("app-release.apk", "", "1.0.63"))
        assertTrue(isPostillaReleaseApkAsset("app-release.apk", "Postilla-v1.0.63.apk", "1.0.63"))
    }

    @Test fun unrelatedOrMismatchedAssetsAreRejected() {
        assertFalse(isPostillaReleaseApkAsset("Postilla-v1.0.62.apk", "", "1.0.63"))
        assertFalse(isPostillaReleaseApkAsset("other-app.apk", "other-app.apk", "1.0.63"))
        assertFalse(isPostillaReleaseApkAsset("app-release.apk", "Postilla-v1.0.62.apk", "1.0.63"))
    }

}
