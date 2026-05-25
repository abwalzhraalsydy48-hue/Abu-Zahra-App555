package com.ultimaterecovery.pro.data.repository

/**
 * A sealed class that wraps the result of an operation, representing
 * the three possible states: [Loading], [Success], or [Error].
 *
 * @param T The type of data held by a successful result.
 */
sealed class Resource<out T> {

    /**
     * Represents a successful operation containing the resulting [data].
     *
     * @param data The payload produced by the operation.
     */
    data class Success<T>(
        val data: T
    ) : Resource<T>()

    /**
     * Represents a failed operation.
     *
     * @param message A human-readable description of what went wrong.
     * @param code    An optional machine-readable error code (e.g. HTTP status,
     *                Room error code, or application-specific code).
     */
    data class Error(
        val message: String,
        val code: Int? = null
    ) : Resource<Nothing>()

    /**
     * Represents an in-progress operation with no result yet.
     */
    data object Loading : Resource<Nothing>()

    // ──────────────────────────────────────────────
    // Convenience helpers
    // ──────────────────────────────────────────────

    /** `true` when this resource is [Loading]. */
    val isLoading: Boolean get() = this is Loading

    /** `true` when this resource is [Success]. */
    val isSuccess: Boolean get() = this is Success

    /** `true` when this resource is [Error]. */
    val isError: Boolean get() = this is Error

    /**
     * Maps the [Success] data to a new type, leaving [Error] and [Loading] untouched.
     */
    fun <R> map(transform: (T) -> R): Resource<R> = when (this) {
        is Success -> Success(transform(data))
        is Error   -> Error(message, code)
        is Loading -> Loading
    }

    /**
     * Returns the success data or `null` if this is not a [Success].
     */
    fun getOrNull(): T? = when (this) {
        is Success -> data
        else       -> null
    }

    /**
     * Returns the success data or [default] if this is not a [Success].
     */
    fun getOrDefault(default: @UnsafeVariance T): @UnsafeVariance T = when (this) {
        is Success -> data
        else       -> default
    }

    /**
     * Returns the error message or `null` if this is not an [Error].
     */
    fun errorMessageOrNull(): String? = when (this) {
        is Error -> message
        else     -> null
    }

    companion object {

        /** Creates a [Success] resource. */
        fun <T> success(data: T): Resource<T> = Success(data)

        /** Creates an [Error] resource. */
        fun <T> error(message: String, code: Int? = null): Resource<T> = Error(message, code)

        /** Creates a [Loading] resource. */
        fun <T> loading(): Resource<T> = Loading
    }
}
