package com.sandy.sconsole.qimgtools.apps.tagger.model;

/**
 * What a model proposed for a question.
 *
 * @param model the model that gave it
 * @param reply its reply, as read
 */
public record TagProposal( String model, TagReply reply ) {}
