package com.aresstack.askai.java8.ui;

import java.awt.Image;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The AskAI application icon (the robot with the speech bubble), replacing the Java default cup
 * on every window. Loaded ONCE from the bundled resource and offered in multiple sizes so the
 * title bar, taskbar and Alt-Tab each pick a crisp variant instead of scaling one blurry image.
 * A missing/broken resource logs loudly and yields an empty list — a window without an icon is
 * cosmetic, a crash at startup is not.
 */
public final class AppIcon {

    private static final String RESOURCE = "/icons/askai.png";
    private static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    private static volatile List<Image> cached;

    private AppIcon() {
    }

    /** Set the multi-size AskAI icon on a top-level window (owned dialogs inherit it anyway). */
    public static void apply(Window window) {
        List<Image> images = images();
        if (window != null && !images.isEmpty()) {
            window.setIconImages(images);
        }
    }

    public static List<Image> images() {
        List<Image> images = cached;
        if (images == null) {
            synchronized (AppIcon.class) {
                images = cached;
                if (images == null) {
                    images = load();
                    cached = images;
                }
            }
        }
        return images;
    }

    private static List<Image> load() {
        try {
            InputStream stream = AppIcon.class.getResourceAsStream(RESOURCE);
            if (stream == null) {
                System.err.println("[app-icon] resource missing: " + RESOURCE);
                return Collections.emptyList();
            }
            BufferedImage source;
            try {
                source = javax.imageio.ImageIO.read(stream);
            } finally {
                stream.close();
            }
            if (source == null) {
                System.err.println("[app-icon] resource unreadable: " + RESOURCE);
                return Collections.emptyList();
            }
            List<Image> images = new ArrayList<Image>(SIZES.length);
            for (int size : SIZES) {
                images.add(source.getScaledInstance(size, size, Image.SCALE_SMOOTH));
            }
            return Collections.unmodifiableList(images);
        } catch (Exception broken) {
            System.err.println("[app-icon] failed to load " + RESOURCE + ": " + broken);
            return Collections.emptyList();
        }
    }
}
