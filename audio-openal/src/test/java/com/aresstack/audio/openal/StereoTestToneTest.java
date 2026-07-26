package com.aresstack.audio.openal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The stereo test tone plays left first, then right, so channel routing is audible and verifiable. */
public class StereoTestToneTest {

    @Test
    public void isInterleavedStereoWithLeftThenRightEnergy() {
        int rate = 44100;
        short[] tone = StereoTestTone.interleaved(rate);

        assertEquals("interleaved stereo has an even sample count", 0, tone.length % 2);
        assertEquals(2, StereoTestTone.CHANNELS);

        // Early frames (first tone) carry left-channel energy and silent right.
        long leftEarly = channelEnergy(tone, 0, rate / 4, 0);
        long rightEarly = channelEnergy(tone, 0, rate / 4, 1);
        assertTrue("left channel has energy during the first tone", leftEarly > 0);
        assertEquals("right channel is silent during the first tone", 0, rightEarly);

        // Late frames (second tone) carry right-channel energy and silent left.
        int totalFrames = tone.length / 2;
        long leftLate = channelEnergy(tone, totalFrames - rate / 4, totalFrames, 0);
        long rightLate = channelEnergy(tone, totalFrames - rate / 4, totalFrames, 1);
        assertEquals("left channel is silent during the second tone", 0, leftLate);
        assertTrue("right channel has energy during the second tone", rightLate > 0);
    }

    private static long channelEnergy(short[] interleaved, int fromFrame, int toFrame, int channel) {
        long sum = 0;
        for (int frame = fromFrame; frame < toFrame; frame++) {
            sum += Math.abs(interleaved[frame * 2 + channel]);
        }
        return sum;
    }
}
