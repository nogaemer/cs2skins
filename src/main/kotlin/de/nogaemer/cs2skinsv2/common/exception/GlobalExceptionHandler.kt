package de.nogaemer.cs2skinsv2.common.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import java.net.URI

/**
 * Converts every exception type this API can throw into the RFC 7807 ProblemDetail shape
 * documented in spec Section 5.2 -- one consistent error envelope regardless of which
 * controller/endpoint failed, so the frontend's error-handling layer only needs to
 * understand one shape.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(ex: NotFoundException, request: WebRequest): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, ex.message, request)

    @ExceptionHandler(BadRequestException::class)
    fun handleBadRequest(ex: BadRequestException, request: WebRequest): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, ex.message, request)

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(ex: ConflictException, request: WebRequest): ProblemDetail =
        problem(HttpStatus.CONFLICT, ex.message, request)

    /** Thrown by Spring itself when a @RequestParam(required = true) is missing. */
    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(ex: MissingServletRequestParameterException, request: WebRequest): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "Required query parameter '${ex.parameterName}' is missing", request)

    /** Catches PageRequestParams/SortSpec validation failures (require {} blocks). */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException, request: WebRequest): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, ex.message, request)

    private fun problem(status: HttpStatus, detail: String?, request: WebRequest): ProblemDetail {
        val problemDetail = ProblemDetail.forStatusAndDetail(status, detail ?: status.reasonPhrase)
        problemDetail.title = status.reasonPhrase
        problemDetail.instance = URI.create(request.getDescription(false).removePrefix("uri="))
        return problemDetail
    }
}
