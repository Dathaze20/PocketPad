package com.dathaze.pocketpad.ui

import java.io.File
import java.io.OutputStream

/**
 * The two decisions about the user's background picture that do not need Android to make them.
 *
 * Both exist because of the same class of mistake: the picture the user already chose is the
 * only copy there is. It lives in the app's own storage, nothing backs it up, and the only
 * other thing that can produce it is the user going and picking it again.
 */
object BackgroundImage {

    /**
     * Half-written picture. Named so it cannot be mistaken for the real one — only
     * `controller_bg.jpg` is ever decoded, so a leftover scratch file is inert.
     */
    const val SCRATCH_SUFFIX = ".incoming"

    /** Past this, a phone photo is a handful of pixels; the cap is really a loop guard. */
    const val MAX_SAMPLE = 64

    /**
     * How far down to scale a [sourceWidth]x[sourceHeight] picture for a screen whose long
     * side is [targetLongSide] pixels. Returns a power of two, as `inSampleSize` requires.
     *
     * Every argument here comes from outside: the dimensions are whatever `BitmapFactory` read
     * out of a file the user chose, and it reports **-1** for anything it could not decode.
     * So each one is checked rather than assumed — a zero or negative target would otherwise
     * make the halving loop run until the multiplication overflowed to zero and then divide by
     * it.
     */
    fun sampleSizeFor(sourceWidth: Int, sourceHeight: Int, targetLongSide: Int): Int {
        val longest = maxOf(sourceWidth, sourceHeight)
        if (longest <= 0 || targetLongSide <= 0) return 1
        var sample = 1
        while (sample < MAX_SAMPLE && longest / (sample * 2) >= targetLongSide) {
            sample *= 2
        }
        return sample
    }

    /**
     * Put new bytes in [destination], and leave whatever is already there alone unless the new
     * ones arrive complete.
     *
     * The straightforward version of this — open [destination] and copy into it — empties the
     * file the moment it is opened. If the copy then fails halfway, and it can (the picture may
     * be a cloud photo that has to be fetched over the network, the phone may be out of
     * storage, the user may revoke access), **the picture the user had is already gone** and
     * what replaced it is a fragment that will not decode. They are left with no background, a
     * failure message on every launch, and no way to tell that choosing a photo is what
     * destroyed the old one.
     *
     * So [write] fills a scratch file, and only a scratch file that was written without
     * complaint is moved into place. A rename over an existing file is atomic on Android's
     * filesystems, which is what makes the swap safe; the delete-first path below is a fallback
     * for filesystems that refuse such a rename outright.
     *
     * @return true when [destination] now holds the new bytes.
     */
    fun replaceOnlyIfWritten(destination: File, write: (OutputStream) -> Unit): Boolean {
        val scratch = scratchFor(destination)
        scratch.delete()
        try {
            scratch.outputStream().use(write)
        } catch (_: Exception) {
            // Includes running out of storage partway, which is exactly the case that used to
            // take the old picture with it.
            scratch.delete()
            return false
        }
        // A source that opened and then gave us nothing is not a picture, and must not be
        // allowed to replace one.
        if (scratch.length() == 0L) {
            scratch.delete()
            return false
        }
        if (scratch.renameTo(destination)) return true
        if (destination.delete() && scratch.renameTo(destination)) return true
        scratch.delete()
        return false
    }

    fun scratchFor(destination: File): File =
        File(destination.parentFile, destination.name + SCRATCH_SUFFIX)
}
