package de.aidadventure.server.vectorstore

import org.apache.lucene.search.Sort
import org.apache.lucene.search.SortField

internal const val ID_FIELD_NAME = "id"
internal const val TEXT_FIELD_NAME = "text"
internal const val VECTOR_FIELD_NAME = "vector"
internal const val TIME_ADDED_FIELD_NAME = "timeadded"

internal val SPECIAL_FIELDS =
    setOf(
        ID_FIELD_NAME,
        TEXT_FIELD_NAME,
        VECTOR_FIELD_NAME,
        TIME_ADDED_FIELD_NAME,
    )

internal val SORT_BY_TIME_ADDED = Sort(SortField(TIME_ADDED_FIELD_NAME, SortField.Type.LONG, true))

internal object ContentMetadataKeys {
    const val ROOT_ID_KEY = "rootId"
    const val AUTHOR_GID_KEY = "authorGID"
    const val AUTHOR_PUBLIC_KEY_KEY = "authorPublicKey"
    const val AUTHOR_SIGNATURE_KEY = "authorSignature"
    const val SIGNATURE_VERSION_KEY = "signatureVersion"
    const val RECEIVED_AT_KEY = "receivedAt"
}
