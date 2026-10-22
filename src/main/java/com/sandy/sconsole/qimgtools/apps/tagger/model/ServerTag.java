package com.sandy.sconsole.qimgtools.apps.tagger.model;

/**
 * A tag already on a question, as the server's bulk tag read returns it
 * ({@code POST /Master/TagAssociation/QUESTION/Tags}).
 *
 * @param id       the tag's id
 * @param tagText  its text
 * @param topicId  its topic; null for a tag of no topic
 * @param explicit true if put on directly, false if only implied by a rule
 */
public record ServerTag( int id, String tagText, Integer topicId,
                         boolean explicit ) {}
