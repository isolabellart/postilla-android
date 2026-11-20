package com.darkwisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NIP-22 root-scope handling. Fixtures are real events fetched from
 * relay.damus.io for thread root
 * `000018e0632cdb4fd89d2508ca256ec88e6c1d0915b04aaa96662581d9ecfad3`:
 * a top-level kind-1111 comment and a nested reply to that comment whose
 * lowercase `e` points at the parent comment and only the uppercase `E`
 * points at the thread root.
 */
class Nip22Test {

    private val rootId = "000018e0632cdb4fd89d2508ca256ec88e6c1d0915b04aaa96662581d9ecfad3"
    private val rootAuthor = "e2ccf7cf20403f3f2a4a55b328f0de3be38558a7d5f33632fdaaefc726c1c8eb"
    private val topCommentId = "bda4cc3aad268a925072375d473e29a5cc7735961565fd148c5393785c0ee973"
    private val topCommentAuthor = "bc28aad5b167f31dd37c66d8c95d400c6411d83275ed12c504f60965d1f9eec6"

    private fun event(kind: Int, tags: List<List<String>>) = NostrEvent(
        id = "test-id",
        pubkey = "test-pubkey",
        created_at = 1789745380L,
        kind = kind,
        tags = tags,
        content = "test",
        sig = "test-sig"
    )

    private val topLevelComment = event(
        kind = Nip22.KIND_COMMENT,
        tags = listOf(
            listOf("E", rootId, "", rootAuthor),
            listOf("K", "1"),
            listOf("P", rootAuthor),
            listOf("e", rootId, "", rootAuthor),
            listOf("k", "1"),
            listOf("p", rootAuthor)
        )
    )

    private val nestedReply = event(
        kind = Nip22.KIND_COMMENT,
        tags = listOf(
            listOf("E", rootId, "", rootAuthor),
            listOf("K", "1"),
            listOf("P", rootAuthor),
            listOf("e", topCommentId, "", topCommentAuthor),
            listOf("k", "1111"),
            listOf("p", topCommentAuthor)
        )
    )

    @Test
    fun rootScopeIdReadsUppercaseE() {
        assertEquals(rootId, Nip22.getRootScopeId(topLevelComment))
        assertEquals(rootId, Nip22.getRootScopeId(nestedReply))
    }

    @Test
    fun referencesRootAcceptsLowercaseEAndUppercaseE() {
        assertTrue(Nip22.referencesRoot(topLevelComment, rootId))
        // Nested reply has NO lowercase e pointing at the root — only E.
        assertTrue(Nip22.referencesRoot(nestedReply, rootId))
        assertFalse(Nip22.referencesRoot(nestedReply, "deadbeef"))
    }

    @Test
    fun replyTargetResolvesToParentCommentForNesting() {
        // rebuildTree nests under the parent when it is already known.
        assertEquals(topCommentId, Nip10.getReplyTarget(nestedReply))
        assertEquals(rootId, Nip10.getReplyTarget(topLevelComment))
    }

    @Test
    fun filterSerializesUppercaseE() {
        val filter = Filter(
            kinds = listOf(Nip22.KIND_COMMENT),
            bigETags = listOf(rootId)
        )
        val json = filter.toJsonObject().toString()
        assertTrue(json.contains("\"#E\""))
        assertTrue(json.contains(rootId))
    }

    @Test
    fun addressableRootCommentsUseNip22Coordinates() {
        val root = NostrEvent(
            id = "listing-event-id",
            pubkey = rootAuthor,
            created_at = 1789745380L,
            kind = Nip99.KIND_LISTING,
            tags = listOf(listOf("d", "vintage-chair")),
            content = "A chair",
            sig = "test-sig"
        )
        val address = "30402:$rootAuthor:vintage-chair"
        val tags = Nip22.buildAddressableRootCommentTags(root)
        assertTrue(tags.contains(listOf("A", address)))
        assertTrue(tags.contains(listOf("K", "30402")))
        assertTrue(tags.contains(listOf("P", rootAuthor)))
        assertTrue(tags.contains(listOf("a", address)))
        assertTrue(tags.contains(listOf("k", "30402")))
        assertTrue(tags.contains(listOf("p", rootAuthor)))
        assertTrue(Nip22.referencesAddressableRoot(event(Nip22.KIND_COMMENT, tags), address))
    }

    @Test
    fun addressableCommentRepliesKeepTheListingRootAndPointToTheParentComment() {
        val root = NostrEvent(
            id = "listing-event-id",
            pubkey = rootAuthor,
            created_at = 1789745380L,
            kind = Nip99.KIND_LISTING,
            tags = listOf(listOf("d", "vintage-chair")),
            content = "A chair",
            sig = "test-sig"
        )
        val parent = event(Nip22.KIND_COMMENT, emptyList()).copy(
            id = topCommentId,
            pubkey = topCommentAuthor
        )
        val tags = Nip22.buildAddressableCommentReplyTags(root, parent)
        val address = "30402:$rootAuthor:vintage-chair"

        assertTrue(tags.contains(listOf("A", address)))
        assertTrue(tags.contains(listOf("K", "30402")))
        assertTrue(tags.contains(listOf("P", rootAuthor)))
        assertTrue(tags.contains(listOf("e", topCommentId, "", topCommentAuthor)))
        assertTrue(tags.contains(listOf("k", "1111")))
        assertTrue(tags.contains(listOf("p", topCommentAuthor)))
        assertFalse(tags.any { it.firstOrNull() == "a" })
        assertEquals(topCommentId, Nip22.getParentCommentId(event(Nip22.KIND_COMMENT, tags)))
    }

    @Test
    fun filterSerializesUppercaseAForAddressableComments() {
        val address = "30402:$rootAuthor:vintage-chair"
        val json = Filter(
            kinds = listOf(Nip22.KIND_COMMENT),
            bigATags = listOf(address)
        ).toJsonObject().toString()
        assertTrue(json.contains("\"#A\""))
        assertTrue(json.contains(address))
    }

    @Test
    fun filterSerializesLowercaseAForAddressableListingComments() {
        val address = "30402:$rootAuthor:vintage-chair"
        val json = Filter(
            kinds = listOf(Nip22.KIND_COMMENT),
            aTags = listOf(address)
        ).toJsonObject().toString()
        assertTrue(json.contains("\"#a\""))
        assertTrue(json.contains(address))
    }

    @Test
    fun kind1WithoutETagHasNoRootScope() {
        val note = event(
            kind = 1,
            tags = listOf(listOf("e", rootId, "", "reply"))
        )
        assertEquals(null, Nip22.getRootScopeId(note))
        assertTrue(Nip22.referencesRoot(note, rootId))
    }
}


class Nip22LongFormArticleTest {
    private val author = "e2ccf7cf20403f3f2a4a55b328f0de3be38558a7d5f33632fdaaefc726c1c8eb"


    @Test
    fun addressableRootMatchingAcceptsUppercaseAAndLegacyLowercaseA() {
        val address = "30023:$author:essay-1"
        val upper = NostrEvent(
            id = "upper-a", pubkey = author, created_at = 1L, kind = Nip22.KIND_COMMENT,
            tags = listOf(listOf("A", address)), content = "upper", sig = "test"
        )
        val lower = upper.copy(id = "lower-a", tags = listOf(listOf("a", address)))
        assertTrue(Nip22.referencesAddressableRoot(upper, address))
        assertTrue(Nip22.referencesAddressableRoot(lower, address))
        assertFalse(Nip22.referencesAddressableRoot(lower, "30023:$author:other"))
    }

    @Test
    fun addressableRootCommentFiltersAreIndependentAlternatives() {
        val address = "30023:$author:essay-1"
        val articleEventId = "article-event-id"
        val filters = Nip22.addressableRootCommentFilters(address, articleEventId)

        assertEquals(3, filters.size)
        assertEquals(listOf(address), filters[0].bigATags)
        assertEquals(null, filters[0].aTags)
        assertEquals(listOf(address), filters[1].aTags)
        assertEquals(null, filters[1].bigATags)
        assertEquals(listOf(articleEventId), filters[2].eTags)
    }

    @Test
    fun articleRootCommentUsesLongFormAddress() {
        val article = NostrEvent(
            id = "article-event-id", pubkey = author, created_at = 1_800_000_000L,
            kind = Nip23.KIND_LONG_FORM, tags = listOf(listOf("d", "essay-1")),
            content = "# Essay", sig = "test"
        )
        val tags = Nip22.buildAddressableRootCommentTags(article)
        val coordinate = "30023:$author:essay-1"
        assertTrue(tags.contains(listOf("A", coordinate)))
        assertTrue(tags.contains(listOf("K", "30023")))
        assertTrue(tags.contains(listOf("P", author)))
    }

    @Test
    fun nestedLongFormCommentKeepsArticleRootAndTargetsParent() {
        val coordinate = "30023:$author:essay-1"
        val parent = NostrEvent(
            id = "parent-comment-id",
            pubkey = "bc28aad5b167f31dd37c66d8c95d400c6411d83275ed12c504f60965d1f9eec6",
            created_at = 1_800_000_001L,
            kind = Nip22.KIND_COMMENT,
            tags = listOf(listOf("A", coordinate), listOf("K", "30023"), listOf("P", author)),
            content = "First comment", sig = "test"
        )
        val tags = Nip22.buildAddressableCommentReplyTags(parent)
        assertTrue(tags.contains(listOf("A", coordinate)))
        assertTrue(tags.contains(listOf("K", "30023")))
        assertTrue(tags.contains(listOf("P", author)))
        assertTrue(tags.contains(listOf("e", parent.id, "", parent.pubkey)))
        assertTrue(tags.contains(listOf("k", "1111")))
        assertTrue(tags.contains(listOf("p", parent.pubkey)))
    }
}
