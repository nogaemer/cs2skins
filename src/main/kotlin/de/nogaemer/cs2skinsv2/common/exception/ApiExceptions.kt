package de.nogaemer.cs2skinsv2.common.exception

/** Maps to 404, per spec (e.g. "No skin found with id 999999"). */
class NotFoundException(message: String) : RuntimeException(message)

/** Maps to 400. Prefer this or IllegalArgumentException (also handled) over ad-hoc checks. */
class BadRequestException(message: String) : RuntimeException(message)

/** Maps to 409 (e.g. "A calculator run is already RUNNING"). */
class ConflictException(message: String) : RuntimeException(message)
