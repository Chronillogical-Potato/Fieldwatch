package app.fieldwatch.radio

import app.fieldwatch.domain.TakBirth

/** A socket opened with no network is replaced once a network exists. One opened on a network stays. */
internal fun replaceTakSocket(openedWithoutNetwork: Boolean, networkNow: Boolean): Boolean =
    openedWithoutNetwork && networkNow

/** Wi-Fi or Ethernet. Cellular is not a LAN for the same-phone socket. */
internal fun takLanUp(wifi: Boolean, ethernet: Boolean): Boolean = wifi || ethernet

/** Wi-Fi wins when both LAN links are up. Cellular is recorded only with no LAN. */
internal fun takBirth(wifi: Boolean, ethernet: Boolean, cellular: Boolean): TakBirth = when {
    wifi -> TakBirth.WIFI
    ethernet -> TakBirth.ETHERNET
    cellular -> TakBirth.CELLULAR
    else -> TakBirth.NONE
}

/**
 * A same-phone socket opened with no LAN is replaced once Wi-Fi or Ethernet
 * is connected, even if the pin to lo reported success and even if cellular
 * was already up. A socket opened on a LAN stays after that LAN drops.
 */
internal fun replaceLoopbackSocket(openedOnLan: Boolean, lanNow: Boolean): Boolean =
    !openedOnLan && lanNow

/** A short name for the Diagnostics row. No message text, so a path cannot leak. */
internal fun takBindError(err: Throwable): String {
    val cause = (err as? java.lang.reflect.InvocationTargetException)?.cause ?: err
    val name = cause.javaClass.simpleName.filter { it.isLetterOrDigit() }
    return name.take(24).ifBlank { "refused" }
}
