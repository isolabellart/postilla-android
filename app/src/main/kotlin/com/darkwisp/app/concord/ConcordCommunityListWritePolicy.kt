package com.darkwisp.app.concord

/**
 * A partial relay read may authorize a Community List write only when the observed
 * kind-33302 set is explicitly incomplete. Quartz then scopes the update to held
 * fragments. A set that merely appears complete on one responding relay is not
 * proof that slower relays have no newer fragments.
 */
internal object ConcordCommunityListWritePolicy {
    fun mayWrite(
        allReadRelaysResponded: Boolean,
        hasObservedFragment: Boolean,
        observedSetIsComplete: Boolean,
    ): Boolean = allReadRelaysResponded || (hasObservedFragment && !observedSetIsComplete)
}
