package com.aresstack.askai.research.knowledge;

import java.util.List;

/** Neutral sentence segmentation port — the OpenNLP adapter lives in {@code :research-text-opennlp}. */
public interface SentenceSegmentationPort {

    List<String> segment(String text);
}
