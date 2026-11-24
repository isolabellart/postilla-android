package com.darkwisp.app.nostr

object Nip29 {
    const val KIND_CHAT_MESSAGE = 9
    const val KIND_PUT_USER = 9000
    const val KIND_EDIT_METADATA = 9002
    const val KIND_CREATE_GROUP = 9007
    const val KIND_DELETE_GROUP = 9008
    const val KIND_CREATE_INVITE = 9009
    const val KIND_JOIN_REQUEST = 9021
    const val KIND_LEAVE_REQUEST = 9022
    const val KIND_GROUP_METADATA = 39000
    const val KIND_GROUP_ADMINS = 39001
    const val KIND_GROUP_MEMBERS = 39002

    data class GroupMetadata(
        val groupId: String,
        val name: String?,
        val picture: String?,
        val about: String?,
        val isPrivate: Boolean,
        val isClosed: Boolean,
        val isRestricted: Boolean = false,
        val isHidden: Boolean = false
    )

    /** One admin's pubkey and the role strings attached to it on a kind 39001 event. */
    data class AdminEntry(val pubkey: String, val roles: List<String>)

    /**
     * Parse either a canonical NIP-29 naddr or the legacy `relay'groupId` identifier.
     * Canonical addresses must reference kind 39000, include the relay's NIP-11 self key,
     * and contain a relay hint so the group can be routed to its hosting relay.
     */
    fun parseGroupIdentifier(identifier: String): Pair<String, String>? {
        val address = identifier.trim().removePrefix("nostr:").substringBefore('?').trim()
        if (address.startsWith("naddr1", ignoreCase = true)) {
            val decoded = runCatching { Nip19.naddrDecode(address) }.getOrNull() ?: return null
            if (decoded.kind != KIND_GROUP_METADATA ||
                decoded.author?.matches(Regex("^[0-9a-fA-F]{64}$")) != true ||
                decoded.dTag.isBlank()) return null
            val relay = decoded.relays.firstNotNullOfOrNull { normalizeRelayUrl(it) } ?: return null
            return relay to decoded.dTag
        }

        val idx = address.indexOf('\'')
        if (idx < 0) return null
        val relay = normalizeRelayUrl(address.substring(0, idx)) ?: return null
        val groupId = address.substring(idx + 1).ifEmpty { "_" }
        return relay to groupId
    }

    /**
     * Accept current NIP-29 naddr invites using ?invite= and legacy relay'groupId links
     * using ?code=. The returned third field is always the code expected in kind 9021.
     */
    fun parseInviteLink(input: String): Triple<String, String, String?>? {
        val (relay, groupId) = parseGroupIdentifier(input) ?: return null
        val query = input.substringAfter('?', missingDelimiterValue = "")
        val codeValue = query.split('&')
            .firstOrNull { it.startsWith("invite=") || it.startsWith("code=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) }.getOrDefault(it) }
        return Triple(relay, groupId, codeValue)
    }

    /** Normalize a relay URL and reject non-WebSocket or malformed endpoints. */
    fun normalizeRelayUrl(input: String): String? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        val candidate = when {
            raw.startsWith("wss://", ignoreCase = true) -> raw
            raw.startsWith("ws://", ignoreCase = true) -> raw
            "://" in raw -> return null
            else -> "wss://$raw"
        }
        val uri = runCatching { java.net.URI(candidate) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("ws", "wss") ||
            uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawFragment != null) return null
        val scheme = uri.scheme.lowercase()
        val authority = uri.rawAuthority ?: return null
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://$authority$path$query"
    }

    fun parseGroupMetadata(event: NostrEvent): GroupMetadata? {
        if (event.kind != KIND_GROUP_METADATA) return null
        val groupId = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return null
        val name = event.tags.firstOrNull { it.size >= 2 && it[0] == "name" }?.get(1)
        val picture = event.tags.firstOrNull { it.size >= 2 && it[0] == "picture" }?.get(1)
        val about = event.tags.firstOrNull { it.size >= 2 && it[0] == "about" }?.get(1)
        val isPrivate = event.tags.any { it.isNotEmpty() && it[0] == "private" }
        val isClosed = event.tags.any { it.isNotEmpty() && it[0] == "closed" }
        val isRestricted = event.tags.any { it.isNotEmpty() && it[0] == "restricted" }
        val isHidden = event.tags.any { it.isNotEmpty() && it[0] == "hidden" }
        return GroupMetadata(groupId, name, picture, about, isPrivate, isClosed, isRestricted, isHidden)
    }

    fun buildChatMessage(
        privkey: ByteArray,
        pubkey: ByteArray,
        groupId: String,
        content: String
    ): NostrEvent = NostrEvent.create(
        privkey = privkey,
        pubkey = pubkey,
        kind = KIND_CHAT_MESSAGE,
        content = content,
        tags = listOf(listOf("h", groupId))
    )

    fun buildJoinRequest(
        privkey: ByteArray,
        pubkey: ByteArray,
        groupId: String,
        inviteCode: String? = null
    ): NostrEvent {
        val tags = mutableListOf(listOf("h", groupId))
        inviteCode?.takeIf { it.isNotEmpty() }?.let { tags.add(listOf("code", it)) }
        return NostrEvent.create(
            privkey = privkey,
            pubkey = pubkey,
            kind = KIND_JOIN_REQUEST,
            content = "",
            tags = tags
        )
    }

    fun buildCreateGroup(
        privkey: ByteArray,
        pubkey: ByteArray,
        groupId: String
    ): NostrEvent = NostrEvent.create(
        privkey = privkey,
        pubkey = pubkey,
        kind = KIND_CREATE_GROUP,
        content = "",
        tags = listOf(listOf("h", groupId))
    )

    /**
     * Build standard NIP-29 kind-9002 tags. Restrictive flags are represented only
     * when enabled; public/open/unrestricted/visible are the defaults and have no
     * inverse tags in the NIP-29 event format.
     */
    fun buildEditMetadataTags(
        groupId: String,
        name: String? = null,
        about: String? = null,
        picture: String? = null,
        isPrivate: Boolean? = null,
        isClosed: Boolean? = null,
        isRestricted: Boolean? = null,
        isHidden: Boolean? = null
    ): List<List<String>> {
        val tags = mutableListOf(listOf("h", groupId))
        name?.takeIf { it.isNotBlank() }?.let { tags.add(listOf("name", it.trim())) }
        about?.takeIf { it.isNotBlank() }?.let { tags.add(listOf("about", it.trim())) }
        picture?.takeIf { it.isNotBlank() }?.let { tags.add(listOf("picture", it.trim())) }
        if (isPrivate == true) tags.add(listOf("private"))
        if (isClosed == true) tags.add(listOf("closed"))
        if (isRestricted == true) tags.add(listOf("restricted"))
        if (isHidden == true) tags.add(listOf("hidden"))
        return tags
    }

    fun buildEditMetadata(
        privkey: ByteArray,
        pubkey: ByteArray,
        groupId: String,
        name: String? = null,
        about: String? = null,
        picture: String? = null,
        isPrivate: Boolean? = null,
        isClosed: Boolean? = null,
        isRestricted: Boolean? = null,
        isHidden: Boolean? = null
    ): NostrEvent = NostrEvent.create(
        privkey = privkey,
        pubkey = pubkey,
        kind = KIND_EDIT_METADATA,
        content = "",
        tags = buildEditMetadataTags(
            groupId, name, about, picture, isPrivate, isClosed, isRestricted, isHidden
        )
    )

    /** Build a kind 9000 event that assigns the given roles to `targetPubkey` in the group. */
    fun buildPutUser(
        privkey: ByteArray,
        pubkey: ByteArray,
        groupId: String,
        targetPubkey: String,
        roles: List<String>
    ): NostrEvent {
        val pTag = mutableListOf("p", targetPubkey).apply { addAll(roles.filter { it.isNotBlank() }) }
        return NostrEvent.create(
            privkey = privkey,
            pubkey = pubkey,
            kind = KIND_PUT_USER,
            content = "",
            tags = listOf(listOf("h", groupId), pTag)
        )
    }

    /** Build a kind 9009 create-invite event with the given code. */
    fun buildCreateInvite(
        privkey: ByteArray,
        pubkey: ByteArray,
        groupId: String,
        code: String
    ): NostrEvent = NostrEvent.create(
        privkey = privkey,
        pubkey = pubkey,
        kind = KIND_CREATE_INVITE,
        content = "",
        tags = listOf(listOf("h", groupId), listOf("code", code))
    )

    /** Generates a random NIP-29 compliant group ID (lowercase alphanumeric, 12 chars). */
    fun generateGroupId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..12).map { chars.random() }.joinToString("")
    }

    /** Generates a random 16-char alphanumeric invite code. */
    fun generateInviteCode(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..16).map { chars.random() }.joinToString("")
    }

    /** Returns admin entries (pubkey + role strings) from a kind 39001 event. */
    fun parseGroupAdmins(event: NostrEvent): List<AdminEntry> {
        if (event.kind != KIND_GROUP_ADMINS) return emptyList()
        return event.tags
            .filter { it.size >= 2 && it[0] == "p" }
            .map { AdminEntry(pubkey = it[1], roles = it.drop(2)) }
    }

    /** Convenience shim: just the pubkeys from kind 39001. */
    fun parseGroupAdminPubkeys(event: NostrEvent): List<String> =
        parseGroupAdmins(event).map { it.pubkey }

    fun parseGroupMembers(event: NostrEvent): List<String> {
        if (event.kind != KIND_GROUP_MEMBERS) return emptyList()
        return event.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] }
    }

    fun buildRemoveUser(
        privkey: ByteArray,
        pubkey: ByteArray,
        groupId: String,
        targetPubkey: String
    ): NostrEvent = NostrEvent.create(
        privkey = privkey,
        pubkey = pubkey,
        kind = 9001,
        content = "",
        tags = listOf(listOf("h", groupId), listOf("p", targetPubkey))
    )

    /** Legacy-compatible identifier used only when the relay does not expose NIP-11 self. */
    fun inviteLink(relayUrl: String, groupId: String): String = "${relayUrl.lowercase()}'$groupId"

    /** Legacy invite code link retained for clients and relays that predate NIP-29 naddr invites. */
    fun inviteLink(relayUrl: String, groupId: String, code: String): String =
        "${inviteLink(relayUrl, groupId)}?code=$code"

    /** Canonical NIP-29 group coordinate, when the hosting relay's NIP-11 self key is known. */
    fun groupAddress(relayUrl: String, groupId: String, relaySelf: String?): String? {
        val normalizedRelay = normalizeRelayUrl(relayUrl) ?: return null
        val self = relaySelf?.trim()?.lowercase()
            ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) } ?: return null
        if (groupId.isBlank()) return null
        return runCatching {
            Nip19.naddrEncode(groupId, self, KIND_GROUP_METADATA, listOf(normalizedRelay))
        }.getOrNull()
    }

    /** Prefer current ?invite= NIP-29 syntax, falling back to the old ?code= form. */
    fun codedInviteLink(relayUrl: String, groupId: String, code: String, relaySelf: String?): String {
        val address = groupAddress(relayUrl, groupId, relaySelf)
        val value = runCatching {
            java.net.URLEncoder.encode(code, Charsets.UTF_8.name())
        }.getOrDefault(code)
        return if (address != null) "$address?invite=$value"
        else "${inviteLink(relayUrl, groupId)}?code=$value"
    }

    const val DEFAULT_COMMUNITY_RELAY = "wss://nostr.isolabellart.it.com"

    // Populated only after the official group is created and its signed 39000 event is verified.
    const val DEFAULT_COMMUNITY_GROUP_ID = ""
    const val DEFAULT_COMMUNITY_INVITE = ""

    val hasConfiguredDefaultCommunityGroup: Boolean
        get() = DEFAULT_COMMUNITY_GROUP_ID.isNotBlank()

    /** A private or write-restricted group requires an explicit kind-9021 admission request. */
    fun requiresJoinRequest(metadata: GroupMetadata): Boolean =
        metadata.isPrivate || metadata.isRestricted

    /** Build a NIP-29 previous tag from real event IDs already seen in this room. */
    fun buildPreviousTag(previousEventIds: List<String>, maxReferences: Int = 3): List<String>? {
        if (maxReferences <= 0) return null
        val references = previousEventIds
            .map { it.take(8).lowercase() }
            .filter { it.matches(Regex("^[0-9a-f]{8}$")) }
            .distinct()
            .takeLast(maxReferences)
        return if (references.isEmpty()) null else listOf("previous") + references
    }

    val DEFAULT_GROUP_RELAYS = listOf(DEFAULT_COMMUNITY_RELAY)
}
