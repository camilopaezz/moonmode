package dev.camilo.st2mode.ble

/** A cached failure may invalidate setup only when the caller can rediscover it. */
internal fun recoverCachedEndpoint(address: String, allowScan: Boolean, forget: (String) -> Unit): Boolean {
    if (!allowScan) return false
    forget(address)
    return true
}
