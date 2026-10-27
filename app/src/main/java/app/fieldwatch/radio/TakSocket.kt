package app.fieldwatch.radio

/** A socket opened with no network is replaced once a network exists. One opened on a network stays. */
internal fun replaceTakSocket(openedWithoutNetwork: Boolean, networkNow: Boolean): Boolean =
    openedWithoutNetwork && networkNow
