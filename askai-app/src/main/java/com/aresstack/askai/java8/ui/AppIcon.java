package com.aresstack.askai.java8.ui;

import java.awt.Image;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The AskAI application icon (the "A" with the shadow wizard), replacing the Java default cup on every
 * window. Two variants of the same artwork: the ice "A" reads best on a DARK taskbar, the obsidian "A"
 * on a LIGHT one. Windows shows the same window icon in the title bar and the taskbar, so the taskbar
 * theme decides ({@link WindowsTaskbarTheme}); unknown falls back to the obsidian variant, which still
 * reads on dark. Override with {@code -Daskai.icon.variant=dark|light}.
 *
 * <p>Loaded ONCE and offered in multiple sizes so the title bar, taskbar and Alt-Tab each pick a crisp
 * variant instead of scaling one blurry image. A missing/broken resource logs loudly and yields an empty
 * list — a window without an icon is cosmetic, a crash at startup is not.</p>
 */
public final class AppIcon {

    static final String DARK_TASKBAR_RESOURCE = "/icons/askai-dark.png";
    static final String LIGHT_TASKBAR_RESOURCE = "/icons/askai-light.png";
    static final String VARIANT_PROPERTY = "askai.icon.variant";
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

    /** The variant closest to (at least) {@code size} pixels, or null when the icon is unavailable. */
    public static Image sized(int size) {
        List<Image> images = images();
        if (images.isEmpty()) {
            return null;
        }
        for (int i = 0; i < SIZES.length; i++) {
            if (SIZES[i] >= size) {
                return images.get(i);
            }
        }
        return images.get(images.size() - 1);
    }

    public static List<Image> images() {
        List<Image> images = cached;
        if (images == null) {
            synchronized (AppIcon.class) {
                images = cached;
                if (images == null) {
                    images = load(resourceFor(System.getProperty(VARIANT_PROPERTY, "auto")));
                    cached = images;
                }
            }
        }
        return images;
    }

    /** {@code dark}/{@code light} force a variant; anything else follows the Windows taskbar theme. */
    static String resourceFor(String variant) {
        String normalized = variant == null ? "" : variant.trim().toLowerCase();
        if ("dark".equals(normalized)) {
            return DARK_TASKBAR_RESOURCE;
        }
        if ("light".equals(normalized)) {
            return LIGHT_TASKBAR_RESOURCE;
        }
        return WindowsTaskbarTheme.detect() == WindowsTaskbarTheme.Mode.DARK
                ? DARK_TASKBAR_RESOURCE : LIGHT_TASKBAR_RESOURCE;
    }

    static List<Image> load(String resource) {
        try {
            InputStream stream = AppIcon.class.getResourceAsStream(resource);
            if (stream == null) {
                System.err.println("[app-icon] resource missing: " + resource);
                return Collections.emptyList();
            }
            BufferedImage source;
            try {
                source = javax.imageio.ImageIO.read(stream);
            } finally {
                stream.close();
            }
            if (source == null) {
                System.err.println("[app-icon] resource unreadable: " + resource);
                return Collections.emptyList();
            }
            List<Image> images = new ArrayList<Image>(SIZES.length);
            for (int size : SIZES) {
                images.add(source.getScaledInstance(size, size, Image.SCALE_SMOOTH));
            }
            return Collections.unmodifiableList(images);
        } catch (Exception broken) {
            System.err.println("[app-icon] failed to load " + resource + ": " + broken);
            return Collections.emptyList();
        }
    }
}
