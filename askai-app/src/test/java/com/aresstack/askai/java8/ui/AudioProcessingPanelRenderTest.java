package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.audio.FileAudioProfileRepository;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * The redesigned audio-processing page (wide pipeline on top, inspector card below) must build and render
 * headless without throwing, exercising the canvas + animated card + horizontal inspector together.
 */
public class AudioProcessingPanelRenderTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void buildsAndRendersHeadlessWithoutException() {
        AudioProcessingPanel panel = new AudioProcessingPanel(
                new FileAudioProfileRepository(folder.getRoot()));
        panel.setSize(1000, 460);
        panel.doLayout();

        BufferedImage image = new BufferedImage(1000, 460, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            panel.paint(graphics);
        } finally {
            graphics.dispose();
        }
    }
}
