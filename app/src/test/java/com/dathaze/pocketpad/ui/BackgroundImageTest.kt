package com.dathaze.pocketpad.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * The picture the user chose is the only copy of it the app has.
 *
 * The regression being guarded: choosing a new background opened the saved file and copied into
 * it, which empties it first. Anything that went wrong partway — a cloud photo that would not
 * download, a full disk — left the user with no background at all, a fragment of a file that
 * would not decode, and a failure message on every launch.
 */
class BackgroundImageTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun saved() = folder.newFile("controller_bg.jpg")

    // ------------------------------------------------------- replacing the file

    @Test
    fun aCompleteWriteReplacesThePicture() {
        val destination = saved()
        destination.writeText("the old picture")

        val replaced = BackgroundImage.replaceOnlyIfWritten(destination) { out ->
            out.write("the new picture".toByteArray())
        }

        assertTrue(replaced)
        assertEquals("the new picture", destination.readText())
    }

    /** The whole reason this function exists. */
    @Test
    fun aWriteThatFailsPartwayLeavesTheOldPictureIntact() {
        val destination = saved()
        destination.writeText("the old picture")

        val replaced = BackgroundImage.replaceOnlyIfWritten(destination) { out ->
            out.write("half of a ph".toByteArray())
            throw IOException("the photo could not be fetched")
        }

        assertFalse("a failed copy must not report success", replaced)
        assertEquals(
            "the picture the user already had was destroyed",
            "the old picture",
            destination.readText()
        )
    }

    @Test
    fun aSourceThatProducesNothingDoesNotReplaceAnything() {
        val destination = saved()
        destination.writeText("the old picture")

        // Opening the stream worked and then yielded zero bytes. An empty file is not a
        // picture, and it would decode to nothing while looking like a successful choice.
        val replaced = BackgroundImage.replaceOnlyIfWritten(destination) { }

        assertFalse(replaced)
        assertEquals("the old picture", destination.readText())
    }

    @Test
    fun theFirstPictureIsWrittenWhenThereIsNothingToProtect() {
        val destination = folder.newFolder("files").resolve("controller_bg.jpg")
        assertFalse(destination.exists())

        val replaced = BackgroundImage.replaceOnlyIfWritten(destination) { out ->
            out.write("first picture".toByteArray())
        }

        assertTrue(replaced)
        assertEquals("first picture", destination.readText())
    }

    @Test
    fun theScratchFileIsNotLeftBehindEitherWay() {
        val destination = saved()
        val scratch = BackgroundImage.scratchFor(destination)

        BackgroundImage.replaceOnlyIfWritten(destination) { out -> out.write(byteArrayOf(1, 2)) }
        assertFalse("left behind after success", scratch.exists())

        BackgroundImage.replaceOnlyIfWritten(destination) { throw IOException("nope") }
        assertFalse("left behind after failure", scratch.exists())
    }

    @Test
    fun theScratchFileIsNeverThePictureItself() {
        // It sits beside the real file. If the two names ever collided, a half-written copy
        // would be the thing that gets decoded.
        val destination = saved()
        val scratch = BackgroundImage.scratchFor(destination)
        assertEquals(destination.parentFile, scratch.parentFile)
        assertFalse(scratch.name == destination.name)
        assertTrue(scratch.name.endsWith(BackgroundImage.SCRATCH_SUFFIX))
    }

    /**
     * The bytes arrived, but they could not be put in place. Reporting success there would
     * leave the app showing the old picture while telling the user it had taken the new one.
     *
     * Forced here by making the destination something a rename cannot land on; in the app the
     * equivalent is a filesystem that refuses the swap.
     */
    @Test
    fun aMoveIntoPlaceThatFailsIsNotReportedAsSuccess() {
        val destination = folder.newFolder("controller_bg.jpg")
        destination.resolve("occupied").writeText("in the way")

        val replaced = BackgroundImage.replaceOnlyIfWritten(destination) { out ->
            out.write("the new picture".toByteArray())
        }

        assertFalse("a picture that never got into place must not report success", replaced)
        assertFalse(
            "and the half-finished copy must not be left lying around",
            BackgroundImage.scratchFor(destination).exists()
        )
    }

    @Test
    fun aLeftoverScratchFileFromAnEarlierAttemptIsNotReused() {
        val destination = saved()
        destination.writeText("the old picture")
        BackgroundImage.scratchFor(destination).writeText("junk from last time")

        val replaced = BackgroundImage.replaceOnlyIfWritten(destination) { out ->
            out.write("the new picture".toByteArray())
        }

        assertTrue(replaced)
        assertEquals("the new picture", destination.readText())
    }

    // ---------------------------------------------------------- scaling it down

    @Test
    fun aPictureAtOrBelowScreenSizeIsNotScaled() {
        assertEquals(1, BackgroundImage.sampleSizeFor(1080, 2400, 2400))
        assertEquals(1, BackgroundImage.sampleSizeFor(800, 600, 2400))
    }

    @Test
    fun aLargePictureIsHalvedUntilItIsAboutScreenSize() {
        // 4800 long side, 2400 screen: one halving lands exactly on the screen size, so a
        // second would drop below it.
        assertEquals(2, BackgroundImage.sampleSizeFor(4800, 2700, 2400))
        assertEquals(4, BackgroundImage.sampleSizeFor(9600, 5400, 2400))
        assertEquals(8, BackgroundImage.sampleSizeFor(20000, 11000, 2400))
    }

    @Test
    fun theResultIsAlwaysAPowerOfTwo() {
        // inSampleSize is rounded down to a power of two by BitmapFactory anyway, so returning
        // anything else would quietly decode larger than asked for.
        for (width in listOf(100, 1000, 3000, 7777, 40000)) {
            val sample = BackgroundImage.sampleSizeFor(width, width / 2, 1080)
            assertEquals("$width -> $sample", 0, sample and (sample - 1))
        }
    }

    /**
     * `BitmapFactory` reports -1 for a file it could not decode, which is the state the old
     * truncating copy left behind. Feeding that back into a halving loop is how a bad photo
     * became a hang.
     */
    @Test
    fun dimensionsThatCouldNotBeReadAskForNoScaling() {
        assertEquals(1, BackgroundImage.sampleSizeFor(-1, -1, 2400))
        assertEquals(1, BackgroundImage.sampleSizeFor(0, 0, 2400))
    }

    @Test
    fun anUnknownScreenSizeTerminatesInsteadOfLooping() {
        // A zero or negative target makes `longest / (sample * 2) >= target` true forever, so
        // the loop would run until sample * 2 overflowed to 0 and then divide by it.
        assertEquals(1, BackgroundImage.sampleSizeFor(4000, 3000, 0))
        assertEquals(1, BackgroundImage.sampleSizeFor(4000, 3000, -1))
    }

    @Test
    fun scalingIsCappedSoTheLoopAlwaysEnds() {
        // A one-pixel target against a huge picture: the cap is the only thing stopping it.
        val sample = BackgroundImage.sampleSizeFor(Int.MAX_VALUE, Int.MAX_VALUE, 1)
        assertEquals(BackgroundImage.MAX_SAMPLE, sample)
        assertTrue(sample > 0)
    }
}
