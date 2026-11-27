package com.darkwisp.app.concord

/**
 * A Community List write may use a partial fragment set only when at least one
 * kind-33302 fragment was actually observed. Quartz then scopes the update to
 * held fragments. An empty partial read is ambiguous and must never be treated
 * as proof that the account has no existing memberships.
 */
internal object ConcordCommunityListWritePolicy {
    fun mayWrite(
        allReadRelaysResponded: Boolean,
        hasObservedFragment: Boolean,
    ): Boolean = allReadRelaysResponded || hasObservedFragment
}
