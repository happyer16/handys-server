package co.handys.api.common

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun handle(ex: ApiException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(ex.status).body(
            ApiErrorResponse(reason = ex.reason, message = ex.message, blocker = ex.blocker),
        )
}
