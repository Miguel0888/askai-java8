package com.aresstack.askai.java8.stt;

/** A speech-to-text failure whose message is written for the end user. */
public class SpeechToTextException extends Exception {

    public SpeechToTextException(String message) {
        super(message);
    }

    public SpeechToTextException(String message, Throwable cause) {
        super(message, cause);
    }
}
