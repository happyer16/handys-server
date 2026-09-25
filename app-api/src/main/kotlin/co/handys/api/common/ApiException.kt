package co.handys.api.common

import co.handys.common.domain.ReasonCode
import org.springframework.http.HttpStatus

class ApiException(
    val status: HttpStatus,
    val reason: ReasonCode,
    override val message: String,
    val blocker: String? = null,
) : RuntimeException(message)

data class ApiErrorResponse(
    val reason: ReasonCode,
    val message: String,
    val blocker: String? = null,
)
