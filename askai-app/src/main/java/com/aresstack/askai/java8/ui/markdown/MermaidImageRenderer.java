package com.aresstack.askai.java8.ui.markdown;

import java.awt.image.BufferedImage;

/** Render Mermaid source into an image without exposing the rendering library to the UI. */
public interface MermaidImageRenderer {

    BufferedImage render(String diagramCode, int width);
}
