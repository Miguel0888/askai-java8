package com.aresstack.askai.java8.ui;

import com.aresstack.audio.profile.AudioProcessingProfiles;

import org.junit.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Collections;

/**
 * The Java2D pipeline canvas must render into an offscreen image without throwing, so a headless CI run
 * exercises the drawing code (block boxes, arrows, selection marker) that the real editor uses.
 */
public class AudioPipelineCanvasRenderTest {

    @Test
    public void rendersTheDefaultProfileHeadlessWithoutException() {
        AudioPipelineCanvas canvas = new AudioPipelineCanvas();
        canvas.setBlocks(AudioProcessingProfiles.defaultSpeech().getBlocks());
        canvas.setSelectedIndex(2);
        paintOffscreen(canvas, 1200, 200);
    }

    @Test
    public void rendersAnEmptyPipelineHeadlessWithoutException() {
        AudioPipelineCanvas canvas = new AudioPipelineCanvas();
        canvas.setBlocks(Collections.<com.aresstack.audio.profile.AudioBlockDefinition>emptyList());
        paintOffscreen(canvas, 400, 200);
    }

    private static void paintOffscreen(AudioPipelineCanvas canvas, int width, int height) {
        canvas.setSize(width, height);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            canvas.paint(graphics);
        } finally {
            graphics.dispose();
        }
    }
}
