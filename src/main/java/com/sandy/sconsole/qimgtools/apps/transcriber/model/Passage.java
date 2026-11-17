package com.sandy.sconsole.qimgtools.apps.transcriber.model;

/**
 * An LCT group's passage, as the group's questions are transcribed against
 * it.
 *
 * @param lctCtxId      the group
 * @param transcription its text and the model that wrote it
 * @param route         how it came about; {@code STORED} for the server's
 * @param stored        true if the server holds this very text
 */
public record Passage( String lctCtxId, Transcription transcription,
                       TranscriptionRoute route, boolean stored ) {

    /** Its text. */
    public String content() {
        return transcription.content() ;
    }

    /** This passage, now held by the server. */
    public Passage asStored() {
        return new Passage( lctCtxId, transcription, route, true ) ;
    }
}
