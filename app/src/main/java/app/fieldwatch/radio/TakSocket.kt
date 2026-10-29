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
 * is connected, including when cellular was already up. A socket opened on
 * a LAN stays after that LAN drops.
 */
internal fun replaceLoopbackSocket(openedOnLan: Boolean, lanNow: Boolean): Boolean =
    !openedOnLan && lanNow
