package com.zhiban.rebuild.data.ilink.network

/**
 * Raised when the iLink Bot API answers `ret: -14` / `errcode: -14` (session expired).
 *
 * This is terminal for the current credentials: the caller must stop all calls, mark the stored
 * credentials invalid and ask the user to re-bind by scanning a fresh QR code. Retrying a `-14`
 * request is explicitly avoided because it triggers stricter server-side limits.
 */
class IlinkSessionExpiredException(message: String = "ILINK_SESSION_EXPIRED") : IllegalStateException(message)
