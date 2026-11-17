package com.sandy.sconsole.qimgtools.apps.transcriber.model;

/**
 * One transcription to store on the server.
 *
 * @param questionId    the question's server id
 * @param aiModel       the model whose text it is; {@code user} for an edit
 * @param content       the whole content (for LCT, passage + marker +
 *                      question part)
 * @param lctCtxModel   with {@code lctCtxContent}: the passage to store
 *                      first, and its model; both null to send none
 * @param lctCtxContent the passage to store first, or null
 */
public record SaveItem( int questionId, String aiModel, String content,
                        String lctCtxModel, String lctCtxContent ) {

    /** True if it carries its group's passage. */
    public boolean hasPassage() {
        return lctCtxContent != null ;
    }
}
