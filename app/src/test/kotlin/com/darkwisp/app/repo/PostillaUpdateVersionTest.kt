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
}
