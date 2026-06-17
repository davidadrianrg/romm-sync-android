package es.davidrg.rommsync.domain.model

/**
 * Typed result wrapper for API calls.
 * Forces callers to handle success/error explicitly instead of catching exceptions.
 */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val kind: ErrorKind, val message: String) : ApiResult<Nothing>()
}

enum class ErrorKind {
    NETWORK,
    AUTH,
    NOT_FOUND,
    SERVER,
    UNKNOWN,
}