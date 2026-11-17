package com.sandy.sconsole.qimgtools.apps.transcriber.server;

import java.util.List;

/**
 * An LCT group's stored passage, and its questions.
 *
 * @param lctCtxId     the group
 * @param aiModel      the passage's model; null if none is stored
 * @param content      the passage; null if none is stored
 * @param lastUpdateAt when it was stored; null if it isn't
 * @param questions    every LCT question of the group
 */
public record LctContext( String lctCtxId, String aiModel, String content,
                          String lastUpdateAt, List<Member> questions ) {

    /**
     * One question of the group.
     *
     * @param id          its server id
     * @param questionId  its question id string
     * @param transcribed true if it has a transcription
     * @param current     true if its content starts with the stored
     *                    passage and the marker; null if not transcribed
     */
    public record Member( int id, String questionId, boolean transcribed,
                          Boolean current ) {}

    public LctContext {
        questions = questions == null ? List.of() : List.copyOf( questions ) ;
    }

    /** True if the server holds the group's passage. */
    public boolean hasPassage() {
        return content != null ;
    }
}
