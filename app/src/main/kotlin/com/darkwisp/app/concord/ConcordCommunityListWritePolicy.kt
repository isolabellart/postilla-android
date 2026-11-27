package com.darkwisp.app.concord

import com.vitorpamplona.quartz.concord.cord02Community.ConcordListFragmentSet
import com.vitorpamplona.quartz.concord.cord02Community.ConcordListFragments
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.content

/**
 * Relay quorum and safe write planning for the private CORD-02 Community List.
 *
 * A partial read must never trigger a full repack: an unavailable relay may hold another
 * fragment or a newer copy. Scoped writes touch only the fragment(s) containing changed
 * memberships and preserve the declared fragment count.
 */
internal object ConcordCommunityListWritePolicy {
    /**
     * Wait for a useful read quorum without making every configured relay a prerequisite.
     * Two thirds gives a meaningful sample for larger relay sets while remaining bounded.
     */
    fun requiredReadRelayResponses(configuredReadRelays: Int): Int {
        require(configuredReadRelays > 0) { "At least one read relay is required" }
        val twoThirds = (configuredReadRelays * 2 + 2) / 3
        return minOf(configuredReadRelays, maxOf(2, twoThirds))
    }

    /**
     * A partial snapshot can be written only if it contains at least one readable fragment
     * that can receive a scoped update. An empty or entirely unreadable partial snapshot is
     * not evidence that the user's list is empty.
     */
    fun mayWrite(
        allReadRelaysResponded: Boolean,
        readQuorumReached: Boolean,
        hasWritableFragment: Boolean,
    ): Boolean = allReadRelaysResponded || (readQuorumReached && hasWritableFragment)

    /**
     * CORD-02 scoped update for a partial relay read. Unlike planWrites() on a complete
     * fragment set, this never repacks the whole list based on a potentially stale snapshot.
     * The fragment count and every untouched fragment remain unchanged.
     */
    fun planScopedWrites(
        fragments: ConcordListFragmentSet,
        nextDocument: JsonObject,
        now: Long,
    ): List<ConcordListFragmentSet.Write> {
        val before = fragments.doc
        val merged = nextDocument
        val changed = changedIds(before, merged)
        if (changed.isEmpty()) return emptyList()

        val available = fragments.held.keys
            .filter { it < fragments.declared && it !in fragments.unreadable }
            .sorted()
        check(available.isNotEmpty()) {
            "No readable Community List fragment is available for a scoped update"
        }

        val targets = HashMap<Int, MutableSet<String>>()
        for (id in changed) {
            val holders = available.filter { id in ConcordListFragments.idsIn(fragments.held.getValue(it).fragment.doc) }
            if (holders.isNotEmpty()) {
                holders.forEach { index -> targets.getOrPut(index) { HashSet() }.add(id) }
                continue
            }

            // New membership: place it in the first readable fragment with enough room.
            val target = available.firstOrNull { index ->
                val ids = targets[index].orEmpty() + id
                val plaintext = ConcordListFragments.rewriteFragment(
                    fragments.held.getValue(index).fragment.doc,
                    merged,
                    ids,
                    fragments.declared,
                )
                ConcordListFragments.projectedEventBytes(plaintext.encodeToByteArray().size) <=
                    ConcordListFragments.EVENT_CEILING_BYTES
            } ?: available.first()
            targets.getOrPut(target) { HashSet() }.add(id)
        }

        return targets.entries.sortedBy { it.key }.mapNotNull { (index, ids) ->
            val held = fragments.held.getValue(index)
            val plaintext = ConcordListFragments.rewriteFragment(
                held.fragment.doc,
                merged,
                ids,
                fragments.declared,
            )
            val projectedBytes = ConcordListFragments.projectedEventBytes(plaintext.encodeToByteArray().size)
            check(projectedBytes <= ConcordListFragments.EVENT_CEILING_BYTES) {
                "Community List fragment $index would exceed the CORD-02 event size limit; a complete read is required to split it"
            }
            if (held.plaintext == plaintext) {
                null
            } else {
                ConcordListFragmentSet.Write(
                    index = index,
                    plaintext = plaintext,
                    createdAt = maxOf(now, held.createdAt + 1),
                )
            }
        }
    }

    private fun changedIds(before: JsonObject, after: JsonObject): Set<String> {
        fun byId(document: JsonObject, key: String): Map<String, JsonObject> =
            (document[key] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .associateBy { (it["community_id"] as? JsonPrimitive)?.content.orEmpty() }

        val changed = HashSet<String>()
        for (key in listOf("entries", "tombstones")) {
            val old = byId(before, key)
            val new = byId(after, key)
            for (id in old.keys + new.keys) {
                if (id.isBlank()) continue
                val oldValue = old[id]?.let { ConcordListFragments.canonicalString(it) }
                val newValue = new[id]?.let { ConcordListFragments.canonicalString(it) }
                if (oldValue != newValue) changed += id
            }
        }
        return changed
    }
}
