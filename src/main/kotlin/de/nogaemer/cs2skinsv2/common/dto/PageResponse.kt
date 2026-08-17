package de.nogaemer.cs2skinsv2.common.dto

/**
 * Generic list-response envelope used by every paginated endpoint (spec Section 5.1).
 * Deliberately NOT Spring Data's Page<T> -- this project has no spring-data-jpa/commons
 * dependency (all queries are hand-written JDBC, a decision made earlier in this project
 * specifically to avoid ORM machinery), so this is a small standalone equivalent instead
 * of pulling in a dependency just for its Page type.
 */
data class PageMetaDto(
    val number: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)

data class PageResponse<T>(
    val content: List<T>,
    val page: PageMetaDto
) {
    companion object {
        fun <T> of(content: List<T>, pageNumber: Int, pageSize: Int, totalElements: Long): PageResponse<T> {
            val totalPages = if (pageSize <= 0) 0 else ((totalElements + pageSize - 1) / pageSize).toInt()
            return PageResponse(content, PageMetaDto(pageNumber, pageSize, totalElements, totalPages))
        }
    }
}

/**
 * Common request-side pagination params, shared across every list controller method.
 * Validation happens in the constructor -- callers should catch IllegalArgumentException
 * via GlobalExceptionHandler rather than checking bounds manually in each controller.
 */
data class PageRequestParams(
    val page: Int,
    val size: Int
) {
    companion object {
        const val MAX_SIZE = 100
    }

    init {
        require(page >= 0) { "page must be >= 0" }
        require(size in 1..MAX_SIZE) { "size must be between 1 and $MAX_SIZE" }
    }
}

/**
 * Parses a "field,direction" sort query param (e.g. "rating,desc") against a whitelist of
 * allowed columns. Throws IllegalArgumentException (-> 400 via GlobalExceptionHandler) for
 * anything not in the whitelist -- never let a client-supplied sort field reach raw SQL,
 * both for injection safety and to avoid an accidental unindexed sort across millions of rows.
 */
data class SortSpec(val field: String, val direction: String) {
    companion object {
        fun parse(sort: String, allowedFields: Set<String>, default: SortSpec): SortSpec {
            val parts = sort.split(",")
            val field = parts.getOrNull(0)?.trim().orEmpty()
            val direction = parts.getOrNull(1)?.trim()?.lowercase() ?: "asc"

            if (field.isBlank()) return default
            require(field in allowedFields) {
                "Invalid sort field '$field' — allowed values: ${allowedFields.joinToString(", ")}"
            }
            require(direction == "asc" || direction == "desc") {
                "Invalid sort direction '$direction' — must be 'asc' or 'desc'"
            }
            return SortSpec(field, direction)
        }
    }
}
