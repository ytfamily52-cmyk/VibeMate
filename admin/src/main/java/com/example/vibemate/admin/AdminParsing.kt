package com.example.vibemate.admin

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pure Firestore-REST JSON handling for the admin app.
 *
 * Deliberately Android-free: every function takes and returns plain values, so
 * the whole read/merge logic is covered by JVM unit tests
 * (admin/src/test/.../AdminParsingTest.kt) with no device, network or cost.
 *
 * Firestore REST shapes handled
 *   list     GET  .../documents/users     -> {"documents":[doc,...],"nextPageToken":"..."}
 *   query    POST .../documents:runQuery  -> [{"document":doc}, ...]
 *   document {"name":"projects/.../documents/users/<uid>","fields":{...}}
 *   value    {"stringValue":"x"} | {"timestampValue":"..."} | {"booleanValue":true}
 */
object AdminParsing {

    // ------------------------------------------------------------ envelopes ---

    /** Document id = last path segment of a Firestore document `name`. */
    fun documentId(name: String): String = name.substringAfterLast('/')

    /** `{"documents":[...]}` (collection list) → the document array (never null). */
    fun documentsFromList(body: String): JsonArray {
        val root = parseObject(body) ?: return JsonArray()
        // NOTE: `getAsJsonArray` blindly casts, so a body like
        // {"documents":null} would throw ClassCastException — check the type.
        val documents = root.get("documents") ?: return JsonArray()
        return if (documents.isJsonArray) documents.asJsonArray else JsonArray()
    }

    /** `[{"document":{...}}]` (:runQuery) → the document array (never null). */
    fun documentsFromQuery(body: String): JsonArray {
        val out = JsonArray()
        val array = try {
            JsonParser.parseString(body).asJsonArray
        } catch (e: Exception) {
            return out
        }
        for (element in array) {
            if (!element.isJsonObject) continue
            val document = element.asJsonObject.get("document") ?: continue
            if (document.isJsonObject) out.add(document)
        }
        return out
    }

    /** `nextPageToken` of a list response ("" when this was the last page). */
    fun nextPageToken(body: String): String =
        rawString(parseObject(body) ?: JsonObject(), "nextPageToken")

    /** `nextPageToken`, or null — ready for `?pageToken=`. */
    fun nextPageOrNull(body: String): String? = nextPageToken(body).ifEmpty { null }

    // ----------------------------------------------------------------- users ---

    /** `users` collection → one [AdminUser] per profile document. */
    fun parseUserProfiles(body: String): List<AdminUser> {
        val out = ArrayList<AdminUser>()
        for (document in documentsFromList(body)) {
            if (!document.isJsonObject) continue
            val obj = document.asJsonObject
            val uid = documentId(rawString(obj, "name"))
            if (uid.isEmpty()) continue
            out.add(
                AdminUser(
                    uid = uid,
                    displayName = fieldString(obj, "display_name"),
                    createdAt = fieldTimestamp(obj, "created_at"),
                    hasProfile = true,
                )
            )
        }
        return out
    }

    // -------------------------------------------------------------- sessions ---

    private fun parseSessions(documents: JsonArray): List<AdminSession> {
        val out = ArrayList<AdminSession>()
        for (document in documents) {
            if (!document.isJsonObject) continue
            val obj = document.asJsonObject
            val id = documentId(rawString(obj, "name"))
            if (id.isEmpty()) continue
            out.add(
                AdminSession(
                    id = id,
                    ownerId = fieldString(obj, "owner_id"),
                    title = fieldString(obj, "title"),
                    persona = fieldString(obj, "persona"),
                    mateName = fieldString(obj, "mate_name"),
                    privateMode = fieldBoolean(obj, "private_mode"),
                    createdAt = fieldTimestamp(obj, "created_at"),
                    updatedAt = fieldTimestamp(obj, "updated_at"),
                    // Soft-delete markers written by the APP (VibeMateApi
                    // SessionDeletePolicy): the chat is still stored on purpose, so
                    // the admin panel keeps showing it until an admin deletes it.
                    deletedByOwner = fieldBoolean(obj, "deleted_by_owner"),
                    deletedAt = fieldTimestamp(obj, "deleted_at"),
                    clearedAt = fieldTimestamp(obj, "cleared_at"),
                )
            )
        }
        return out
    }

    /** `chat_sessions` list → sessions (used for the per-user chat counts). */
    fun parseSessionsFromList(body: String): List<AdminSession> =
        parseSessions(documentsFromList(body))

    /** `:runQuery` answer for `where owner_id == <uid>` → sessions. */
    fun parseSessionsFromQuery(body: String): List<AdminSession> =
        parseSessions(documentsFromQuery(body))

    // -------------------------------------------------------------- messages ---

    /** `chat_sessions/{id}/messages` list → messages, oldest first. */
    fun parseMessages(body: String): List<AdminMessage> {
        val out = ArrayList<AdminMessage>()
        for (document in documentsFromList(body)) {
            if (!document.isJsonObject) continue
            val obj = document.asJsonObject
            val id = documentId(rawString(obj, "name"))
            if (id.isEmpty()) continue
            out.add(
                AdminMessage(
                    id = id,
                    role = fieldString(obj, "role"),
                    content = fieldString(obj, "content"),
                    createdAt = fieldTimestamp(obj, "created_at"),
                    type = fieldString(obj, "type"),
                    fileName = fieldString(obj, "file_name"),
                    data = fieldString(obj, "data"),
                )
            )
        }
        return sortMessagesOldestFirst(out)
    }

    /** Document ids of a list OR :runQuery answer (used for call records). */
    fun parseDocumentIds(body: String): List<String> {
        val listed = documentsFromList(body)
        val documents = if (listed.size() > 0) listed else documentsFromQuery(body)
        val out = ArrayList<String>()
        for (document in documents) {
            if (!document.isJsonObject) continue
            val id = documentId(rawString(document.asJsonObject, "name"))
            if (id.isNotEmpty()) out.add(id)
        }
        return out
    }

    // ------------------------------------------------------- merges / sorting ---

    /**
     * Users list = profiles ∪ session owners, with per-user chat counts and the
     * time of each user's latest chat.
     *
     * Order: real users first, the one whose LATEST chat is the newest on top
     * ("latest chat upar"), then by chat count, then name. The owner-less ORPHAN
     * bucket stays last, so leftovers never hide a real account.
     */
    fun mergeUsers(profiles: List<AdminUser>, sessions: List<AdminSession>): List<AdminUser> {
        val counts = HashMap<String, Int>()
        val privateCounts = HashMap<String, Int>()
        // Chats the user deleted from their own app stay in the collection (that
        // is what keeps them visible here), so they are counted as well.
        val deletedCounts = HashMap<String, Int>()
        // Most recent chat per owner — the key the whole list is ordered by.
        val lastChatAt = HashMap<String, String>()
        for (session in sessions) {
            counts[session.ownerId] = (counts[session.ownerId] ?: 0) + 1
            if (session.privateMode) {
                privateCounts[session.ownerId] = (privateCounts[session.ownerId] ?: 0) + 1
            }
            if (session.userDeleted) {
                deletedCounts[session.ownerId] = (deletedCounts[session.ownerId] ?: 0) + 1
            }
            val at = session.updatedAt.ifEmpty { session.createdAt }
            val current = lastChatAt[session.ownerId]
            if (current == null || canonicalTimestamp(at) > canonicalTimestamp(current)) {
                lastChatAt[session.ownerId] = at
            }
        }

        val byUid = LinkedHashMap<String, AdminUser>()
        for (profile in profiles) {
            byUid[profile.uid] = profile.copy(
                sessionCount = counts[profile.uid] ?: 0,
                privateCount = privateCounts[profile.uid] ?: 0,
                deletedCount = deletedCounts[profile.uid] ?: 0,
                lastChatAt = lastChatAt[profile.uid] ?: "",
            )
        }
        for ((ownerId, count) in counts) {
            if (byUid.containsKey(ownerId)) continue
            byUid[ownerId] = AdminUser(
                uid = ownerId,
                sessionCount = count,
                privateCount = privateCounts[ownerId] ?: 0,
                deletedCount = deletedCounts[ownerId] ?: 0,
                lastChatAt = lastChatAt[ownerId] ?: "",
            )
        }

        return byUid.values.sortedWith(
            compareBy<AdminUser> { it.isOrphan }
                .thenByDescending { canonicalTimestamp(it.lastChatAt) }
                .thenByDescending { it.sessionCount }
                .thenBy { it.displayName.ifEmpty { it.uid }.lowercase() }
                .thenBy { it.uid }
        )
    }

    /** How many of these chats the user deleted from their own app. */
    fun userDeletedCount(sessions: List<AdminSession>): Int = sessions.count { it.userDeleted }

    /**
     * Newest chat first.
     *
     * A chat that never got an `updated_at` falls back to its `created_at`, so it
     * lands in the right place instead of dropping to the bottom. Timestamps are
     * compared in [canonicalTimestamp] form — see that function for why plain
     * string comparison is not enough.
     */
    fun sortSessionsNewestFirst(sessions: List<AdminSession>): List<AdminSession> =
        sessions.sortedWith(
            compareByDescending<AdminSession> {
                canonicalTimestamp(it.updatedAt.ifEmpty { it.createdAt })
            }
                .thenByDescending { canonicalTimestamp(it.createdAt) }
                .thenBy { it.id }
        )

    /** Conversation order: oldest first (mirrors VibeMateApi.listMessages). */
    fun sortMessagesOldestFirst(messages: List<AdminMessage>): List<AdminMessage> =
        messages.sortedWith(
            compareBy({ canonicalTimestamp(it.createdAt) }, { it.id })
        )

    /**
     * A fixed-width form of an ISO-8601 UTC timestamp, so newest/oldest ordering
     * is EXACTLY right.
     *
     * The app writes `updated_at` / `created_at` from `Instant.now().toString()`,
     * whose fractional part is VARIABLE width (".5", ".834", ".834745", or none at
     * all). Comparing those raw strings breaks inside the same second: "…:00Z"
     * sorts ABOVE "…:00.5Z" ('Z' > '.') and "…:00.5Z" ABOVE "…:00.55Z" — so two
     * chats touched in the same second could appear in the wrong order and the
     * truly latest chat was not always first. Padding the fraction to 9 digits
     * (Firestore's maximum precision) makes the comparison correct again.
     *
     * Anything that is not the expected `…[.fraction]Z` shape is returned as-is.
     */
    internal fun canonicalTimestamp(timestamp: String): String {
        val t = timestamp.trim()
        if (t.isEmpty()) return ""
        val dot = t.indexOf('.')
        val z = t.lastIndexOf('Z')
        if (dot < 0) {
            // No fraction at all: add a zero one, so it compares like ".000000000".
            return if (z == t.length - 1) t.substring(0, z) + ".000000000Z" else t
        }
        if (z < dot) return t
        val fraction = t.substring(dot + 1, z).padEnd(9, '0').take(9)
        return t.substring(0, dot) + "." + fraction + "Z"
    }

    /**
     * Every chat of ONE owner, newest first — NO per-user cap.
     *
     * [sessions] is expected to be the fully-paged `chat_sessions` scan, so this
     * simply keeps the rows whose `owner_id` matches. An empty [ownerId] is the
     * ORPHAN bucket: sessions whose `owner_id` field is missing or blank (left
     * over from the old replace-style PATCH bug), shown as "(no owner)".
     */
    fun sessionsOfOwner(sessions: List<AdminSession>, ownerId: String): List<AdminSession> =
        sortSessionsNewestFirst(sessions.filter { it.ownerId == ownerId })

    // ----------------------------------------------------------- query build ---

    /**
     * `:runQuery` payload for `where <fieldPath> == <value>`, paged with
     * [offset] / [limit] → `{"structuredQuery":{...}}`.
     *
     * Android-free on purpose: the exact JSON (including the pagination `offset`
     * that makes every page of a long list load) is covered by
     * `AdminPaginationTest`. `offset` is left OUT when 0, so the first request
     * stays byte-identical to the pre-pagination one.
     */
    fun equalityQuery(
        collectionId: String,
        fieldPath: String,
        value: String,
        offset: Int,
        limit: Int,
    ): JsonObject {
        val query = JsonObject()
        query.add("from", JsonArray().apply {
            add(JsonObject().apply { addProperty("collectionId", collectionId) })
        })
        query.add("where", JsonObject().apply {
            add("fieldFilter", JsonObject().apply {
                add("field", JsonObject().apply { addProperty("fieldPath", fieldPath) })
                addProperty("op", "EQUAL")
                add("value", JsonObject().apply { addProperty("stringValue", value) })
            })
        })
        query.addProperty("limit", limit)
        if (offset > 0) query.addProperty("offset", offset)
        return JsonObject().apply { add("structuredQuery", query) }
    }

    /**
     * Should the paging loop fetch one more page?
     *
     * A page SHORTER than [pageSize] is the last one; [maxPages] is the safety cap
     * that keeps a huge database from looping forever.
     */
    fun hasAnotherPage(returnedThisPage: Int, pageSize: Int, pagesFetched: Int, maxPages: Int): Boolean =
        returnedThisPage >= pageSize && pagesFetched < maxPages

    // --------------------------------------------------------- field helpers ---

    /** A Firestore `"fields"` string value ("" when missing). */
    fun fieldString(document: JsonObject, name: String): String {
        val field = field(document, name) ?: return ""
        return rawString(field, "stringValue")
    }

    /** A Firestore `"fields"` timestamp value ("" when missing). */
    fun fieldTimestamp(document: JsonObject, name: String): String {
        val field = field(document, name) ?: return ""
        return rawString(field, "timestampValue")
    }

    /** A Firestore `"fields"` boolean value (false when missing). */
    fun fieldBoolean(document: JsonObject, name: String): Boolean {
        val field = field(document, name) ?: return false
        val value = field.get("booleanValue") ?: return false
        return !value.isJsonNull && value.isJsonPrimitive && value.asBoolean
    }

    private fun field(document: JsonObject, name: String): JsonObject? {
        val fields = document.get("fields") ?: return null
        if (!fields.isJsonObject) return null
        val value = fields.asJsonObject.get(name) ?: return null
        if (!value.isJsonObject) return null
        return value.asJsonObject
    }

    /** Safe primitive read: "" for a missing, null or non-primitive value. */
    private fun rawString(obj: JsonObject, name: String): String {
        val element = obj.get(name) ?: return ""
        if (element.isJsonNull || !element.isJsonPrimitive) return ""
        return element.asString
    }

    private fun parseObject(body: String): JsonObject? {
        return try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            null
        }
    }
}