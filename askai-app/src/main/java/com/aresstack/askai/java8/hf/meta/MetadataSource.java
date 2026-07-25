package com.aresstack.askai.java8.hf.meta;

/**
 * Where one piece of model metadata came from. Ordered from most to least authoritative so that, when
 * two sources disagree about the same field, the higher-ranked (lower ordinal) one wins:
 * a structured repository file beats a structured API field, which beats card metadata, a tag, and
 * finally a value merely guessed from a file name.
 */
public enum MetadataSource {

    /** A structured repository file such as {@code config.json} or {@code generation_config.json}. */
    CONFIG_JSON,

    /** {@code generation_config.json} specifically (sampling defaults). */
    GENERATION_CONFIG,

    /** {@code tokenizer_config.json} (chat template, special tokens). */
    TOKENIZER_CONFIG,

    /** Metadata embedded in the GGUF itself. */
    GGUF_METADATA,

    /** A structured field of the Hugging Face model-info API. */
    HF_MODEL_API,

    /** The model card's front-matter ({@code cardData}). */
    HF_CARD_DATA,

    /** A curated, tested mapping maintained inside AskAI (e.g. the model-family registry). */
    REGISTRY,

    /** A repository tag (e.g. {@code base_model:...}, {@code license:...}). */
    TAG,

    /** Derived from the selected file name (e.g. the quantization in {@code ...Q4_K_M.gguf}). */
    FILE_NAME
}
