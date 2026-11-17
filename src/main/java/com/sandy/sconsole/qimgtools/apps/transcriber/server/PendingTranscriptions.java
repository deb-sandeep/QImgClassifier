package com.sandy.sconsole.qimgtools.apps.transcriber.server;

import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;

import java.util.List;

/**
 * The server's answer to a fetch of pending questions.
 *
 * @param totalResults the pending questions matching the criteria, those
 *                     returned included, whatever the cursor
 * @param items        the questions, by server id
 */
public record PendingTranscriptions( int totalResults, List<Item> items ) {

    /**
     * A pending question.
     *
     * @param question          the question
     * @param lctCtxId          its LCT group; null for another type
     * @param lctCtxTranscribed true if the server holds the group's
     *                          passage; null for another type
     */
    public record Item( ServerQuestion question, String lctCtxId,
                        Boolean lctCtxTranscribed ) {}

    public PendingTranscriptions {
        items = items == null ? List.of() : List.copyOf( items ) ;
    }
}
