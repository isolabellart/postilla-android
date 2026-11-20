package com.darkwisp.app.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpamModerationTest {
    @Test
    fun highScoreUnfollowedAuthorIsHiddenWhenFilterIsEnabled() {
        assertTrue(shouldHideSpamAuthor("stranger", "me", false, false, true, 0.9f))
    }

    @Test
    fun followedSafelistedOwnAndLowScoreAuthorsRemainVisible() {
        assertFalse(shouldHideSpamAuthor("stranger", "me", true, false, true, 0.9f))
        assertFalse(shouldHideSpamAuthor("stranger", "me", false, true, true, 0.9f))
        assertFalse(shouldHideSpamAuthor("me", "me", false, false, true, 0.9f))
        assertFalse(shouldHideSpamAuthor("stranger", "me", false, false, true, 0.69f))
        assertFalse(shouldHideSpamAuthor("stranger", "me", false, false, false, 0.99f))
        assertFalse(shouldHideSpamAuthor("stranger", "me", false, false, true, null))
    }
}
