package com.sandy.sconsole.qimgtools.apps.transcriber.server;

/**
 * A question's transcription as the server stores it.
 *
 * @param questionId   the question's server id
 * @param aiModel      the model that wrote it, or {@code user}
 * @param content      the whole question, an LCT one's passage included
 * @param lastUpdateAt when it was stored
 * @param lctCtx       the group's stored passage; null for a non-LCT
 *                     question
 */
public record StoredTranscription( int questionId, String aiModel,
                                   String content, String lastUpdateAt,
                                   Passage lctCtx ) {

    /** An LCT group's stored passage. */
    public record Passage( String lctCtxId, String aiModel, String content,
                           String lastUpdateAt ) {}
}
