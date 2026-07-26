package com.aresstack.audio.openal;

import java.util.List;

/**
 * Abstraction over the OpenAL playback engine so callers can enumerate devices and play PCM without being
 * bound to the native implementation — enabling headless tests with a fake in place of {@link OpenAlPlayback}.
 */
public interface OpenAlAudioBackend {

    List<OpenAlDevice> listPlaybackDevices();

    OpenAlPlaybackResult play(short[] interleaved, int channels, int sampleRateHz,
                              String deviceSpecifier, OpenAlCancellation cancellation) throws OpenAlException;
}
