package com.clhs.score.data

open class SchoolException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class SchoolAuthenticationException(
    message: String = "登入狀態已失效",
) : SchoolException(message)

class SchoolTransientException(
    message: String,
    cause: Throwable? = null,
) : SchoolException(message, cause)
