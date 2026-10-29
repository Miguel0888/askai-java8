package com.aresstack.askai.java8.ui;

import org.junit.Test;

import java.awt.Image;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

public class AppIconTest {

    @Test
    public void bothTaskbarVariantsAreBundledAndLoadInEveryWindowSize() {
        assertEquals(9, AppIcon.load(AppIcon.DARK_TASKBAR_RESOURCE).size());
        assertEquals(9, AppIcon.load(AppIcon.LIGHT_TASKBAR_RESOURCE).size());
    }

    @Test
    public void anExplicitVariantWinsOverTheTaskbarTheme() {
        assertEquals(AppIcon.DARK_TASKBAR_RESOURCE, AppIcon.resourceFor("dark"));
        assertEquals(AppIcon.LIGHT_TASKBAR_RESOURCE, AppIcon.resourceFor(" LIGHT "));
    }

    @Test
    public void sizedPicksTheSmallestVariantThatIsAtLeastAsLarge() {
        List<Image> images = AppIcon.images();
        assertFalse(images.isEmpty());

        assertSame(images.get(0), AppIcon.sized(16));
        assertSame(images.get(3), AppIcon.sized(32));
        assertSame(images.get(3), AppIcon.sized(30));
        assertSame("larger than all variants falls back to the largest",
                images.get(images.size() - 1), AppIcon.sized(1024));
    }

    @Test
    public void theRegistryOutputIsParsedIntoTheTaskbarMode() {
        String header = "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize\r\n";
        assertEquals(WindowsTaskbarTheme.Mode.DARK,
                WindowsTaskbarTheme.parse(header + "    SystemUsesLightTheme    REG_DWORD    0x0\r\n"));
        assertEquals(WindowsTaskbarTheme.Mode.LIGHT,
                WindowsTaskbarTheme.parse(header + "    SystemUsesLightTheme    REG_DWORD    0x1\r\n"));
        assertEquals(WindowsTaskbarTheme.Mode.UNKNOWN,
                WindowsTaskbarTheme.parse("ERROR: The system was unable to find the specified registry key or value."));
        assertEquals(WindowsTaskbarTheme.Mode.UNKNOWN, WindowsTaskbarTheme.parse(null));
    }
}
