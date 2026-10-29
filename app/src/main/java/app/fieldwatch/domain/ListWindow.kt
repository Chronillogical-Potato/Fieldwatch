package app.fieldwatch.domain

/**
 * A list row is in view only when the whole row sits inside the window.
 * A sliver at the bottom of a short list, such as the strip under the live map, does not count.
 * A row taller than the window is the caller's job: pin its top.
 */
fun rowFillsWindow(offset: Int, size: Int, windowStart: Int, windowEnd: Int): Boolean {
    if (size <= 0 || windowEnd <= windowStart) return false
    return offset >= windowStart && offset + size <= windowEnd
}
