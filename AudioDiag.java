import javax.sound.sampled.*;
import java.util.*;

public class AudioDiag {
    public static void main(String[] args) {
        float[] rates = {44100f, 48000f, 22050f, 16000f};
        int[] channels = {2, 1};
        System.out.println("=== Playback (SourceDataLine) capability probe ===");
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        for (Mixer.Info info : infos) {
            Mixer mixer = AudioSystem.getMixer(info);
            DataLine.Info sdl = new DataLine.Info(SourceDataLine.class, null);
            if (!mixer.isLineSupported(sdl)) continue;
            System.out.println("\nMIXER: [" + info.getName() + "] — " + info.getDescription());
            for (float rate : rates) {
                for (int ch : channels) {
                    AudioFormat fmt = new AudioFormat(rate, 16, ch, true, false);
                    String tag = (int) rate + "Hz/" + ch + "ch";
                    boolean supported = mixer.isLineSupported(new DataLine.Info(SourceDataLine.class, fmt));
                    String openResult;
                    SourceDataLine line = null;
                    try {
                        line = (SourceDataLine) mixer.getLine(new DataLine.Info(SourceDataLine.class, fmt));
                        line.open(fmt);
                        openResult = "OPEN OK";
                    } catch (Throwable t) {
                        openResult = "OPEN FAIL: " + t.getClass().getSimpleName() + " " + t.getMessage();
                    } finally {
                        if (line != null) try { line.close(); } catch (Throwable ignored) {}
                    }
                    System.out.println("   " + tag + " -> isLineSupported=" + supported + " | " + openResult);
                }
            }
        }
        System.out.println("\n=== Default line (AudioSystem.getLine, no mixer) ===");
        for (float rate : rates) {
            for (int ch : channels) {
                AudioFormat fmt = new AudioFormat(rate, 16, ch, true, false);
                SourceDataLine line = null;
                String r;
                try {
                    line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, fmt));
                    line.open(fmt);
                    r = "OPEN OK";
                } catch (Throwable t) {
                    r = "OPEN FAIL: " + t.getClass().getSimpleName() + " " + t.getMessage();
                } finally {
                    if (line != null) try { line.close(); } catch (Throwable ignored) {}
                }
                System.out.println("   " + (int) rate + "Hz/" + ch + "ch -> " + r);
            }
        }
    }
}
