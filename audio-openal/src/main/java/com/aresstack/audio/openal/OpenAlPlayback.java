package com.aresstack.audio.openal;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAL Soft playback of interleaved PCM16 audio. On Windows OpenAL Soft routes through WASAPI shared
 * mode — the same path browsers and media players use — so it opens dedicated endpoints (e.g. the Sound
 * Blaster AE-7) that Java's built-in DirectSound lines reject.
 *
 * <p>Devices are enumerated by their exact OpenAL specifier and opened by that exact specifier; there is
 * no fuzzy name matching and no silent fallback to a different endpoint. Every failure surfaces the
 * failing phase plus the raw {@code alcGetError}/{@code alGetError} codes via {@link OpenAlException}, and
 * all resources (source, buffer, context, device) are released even on failure.</p>
 */
public final class OpenAlPlayback implements OpenAlAudioBackend {

    public static final String BACKEND = "OpenAL Soft (WASAPI on Windows)";
    private static final long NULL = 0L;

    /** @return every OpenAL playback endpoint, using ALC_ALL_DEVICES_SPECIFIER when available. */
    public List<OpenAlDevice> listPlaybackDevices() {
        int token = ALC10.alcIsExtensionPresent(NULL, "ALC_ENUMERATE_ALL_EXT")
                ? ALC11.ALC_ALL_DEVICES_SPECIFIER : ALC10.ALC_DEVICE_SPECIFIER;
        List<String> specifiers = ALUtil.getStringList(NULL, token);
        List<OpenAlDevice> devices = new ArrayList<OpenAlDevice>();
        if (specifiers != null) {
            for (String spec : specifiers) {
                if (spec != null && spec.trim().length() > 0) {
                    devices.add(new OpenAlDevice(spec, spec));
                }
            }
        }
        return devices;
    }

    /** @return the OS default playback specifier, or null if none is reported. */
    public String defaultPlaybackSpecifier() {
        int token = ALC10.alcIsExtensionPresent(NULL, "ALC_ENUMERATE_ALL_EXT")
                ? ALC11.ALC_DEFAULT_ALL_DEVICES_SPECIFIER : ALC10.ALC_DEFAULT_DEVICE_SPECIFIER;
        return ALC10.alcGetString(NULL, token);
    }

    /**
     * Play interleaved PCM16 on exactly {@code deviceSpecifier} (empty/null = OS default). Blocks until the
     * sound finishes or {@code cancellation} fires, then releases everything.
     */
    public OpenAlPlaybackResult play(short[] interleaved, int channels, int sampleRateHz,
                                     String deviceSpecifier, OpenAlCancellation cancellation)
            throws OpenAlException {
        if (interleaved == null || interleaved.length == 0) {
            throw new OpenAlException("validate", deviceSpecifier, 0, 0, "No PCM samples to play.");
        }
        int format = alFormat(channels, deviceSpecifier);
        OpenAlCancellation cancel = cancellation == null ? OpenAlCancellation.NEVER : cancellation;

        long device = NULL;
        long context = NULL;
        int buffer = 0;
        int source = 0;
        boolean contextCurrent = false;
        try {
            device = openDevice(deviceSpecifier);
            ALCCapabilities alcCaps = ALC.createCapabilities(device);
            context = ALC10.alcCreateContext(device, (IntBuffer) null);
            if (context == NULL) {
                throw new OpenAlException("createContext", deviceSpecifier, ALC10.alcGetError(device), 0,
                        "alcCreateContext returned NULL.");
            }
            if (!ALC10.alcMakeContextCurrent(context)) {
                throw new OpenAlException("makeCurrent", deviceSpecifier, ALC10.alcGetError(device), 0,
                        "alcMakeContextCurrent failed.");
            }
            contextCurrent = true;
            AL.createCapabilities(alcCaps);
            AL10.alGetError(); // clear any stale error

            buffer = AL10.alGenBuffers();
            checkAl("genBuffer", deviceSpecifier, device);
            ShortBuffer data = BufferUtils.createShortBuffer(interleaved.length);
            data.put(interleaved);
            ((java.nio.Buffer) data).flip(); // via Buffer: ShortBuffer.flip() is covariant only on Java 9+
            AL10.alBufferData(buffer, format, data, sampleRateHz);
            checkAl("bufferData", deviceSpecifier, device);

            source = AL10.alGenSources();
            checkAl("genSource", deviceSpecifier, device);
            AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
            checkAl("bindBuffer", deviceSpecifier, device);

            long startNanos = System.nanoTime();
            AL10.alSourcePlay(source);
            checkAl("play", deviceSpecifier, device);

            boolean cancelled = awaitCompletion(source, cancel);
            if (cancelled) {
                AL10.alSourceStop(source);
            }

            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            int frames = interleaved.length / channels;
            long nominalMillis = frames * 1000L / sampleRateHz;
            return new OpenAlPlaybackResult(BACKEND,
                    deviceSpecifier == null || deviceSpecifier.trim().length() == 0
                            ? "system default" : deviceSpecifier,
                    channels, sampleRateHz, cancelled ? elapsedMillis : nominalMillis,
                    interleaved.length * 2, cancelled);
        } finally {
            releaseSource(source);
            releaseBuffer(buffer);
            releaseContext(contextCurrent, context);
            releaseDevice(device);
        }
    }

    private long openDevice(String deviceSpecifier) throws OpenAlException {
        long device = deviceSpecifier == null || deviceSpecifier.trim().length() == 0
                ? ALC10.alcOpenDevice((ByteBuffer) null)
                : ALC10.alcOpenDevice(deviceSpecifier);
        if (device == NULL) {
            throw new OpenAlException("openDevice", deviceSpecifier, ALC10.alcGetError(NULL), 0,
                    "alcOpenDevice returned NULL — the endpoint could not be opened.");
        }
        return device;
    }

    private static int alFormat(int channels, String deviceSpecifier) throws OpenAlException {
        if (channels == 1) {
            return AL10.AL_FORMAT_MONO16;
        }
        if (channels == 2) {
            return AL10.AL_FORMAT_STEREO16;
        }
        throw new OpenAlException("validate", deviceSpecifier, 0, 0,
                "Only mono or stereo PCM16 is supported; got channels=" + channels + ".");
    }

    private static boolean awaitCompletion(int source, OpenAlCancellation cancel) {
        while (!cancel.isCancelled()
                && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return cancel.isCancelled();
    }

    private static void checkAl(String phase, String device, long alcDevice) throws OpenAlException {
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            throw new OpenAlException(phase, device, ALC10.alcGetError(alcDevice), error,
                    "OpenAL reported an error during " + phase + ".");
        }
    }

    private static void releaseSource(int source) {
        if (source != 0) {
            try {
                AL10.alSourceStop(source);
            } catch (Throwable ignored) {
                // best-effort cleanup
            }
            try {
                AL10.alDeleteSources(source);
            } catch (Throwable ignored) {
                // best-effort cleanup
            }
        }
    }

    private static void releaseBuffer(int buffer) {
        if (buffer != 0) {
            try {
                AL10.alDeleteBuffers(buffer);
            } catch (Throwable ignored) {
                // best-effort cleanup
            }
        }
    }

    private static void releaseContext(boolean contextCurrent, long context) {
        if (contextCurrent) {
            try {
                ALC10.alcMakeContextCurrent(NULL);
            } catch (Throwable ignored) {
                // best-effort cleanup
            }
        }
        if (context != NULL) {
            try {
                ALC10.alcDestroyContext(context);
            } catch (Throwable ignored) {
                // best-effort cleanup
            }
        }
    }

    private static void releaseDevice(long device) {
        if (device != NULL) {
            try {
                ALC10.alcCloseDevice(device);
            } catch (Throwable ignored) {
                // best-effort cleanup
            }
        }
    }
}
