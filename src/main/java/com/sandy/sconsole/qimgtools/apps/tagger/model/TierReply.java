package com.sandy.sconsole.qimgtools.apps.tagger.model;

/**
 * One tier's reply on a question.
 *
 * @param tier  1 or 2
 * @param model the model that gave it
 * @param reply the reply as read
 */
public record TierReply( int tier, String model, TagReply reply ) {

    public String answer() {
        return reply.answer() ;
    }
}
