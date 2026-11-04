package com.sandy.sconsole.qimgtools.apps.tagger.model;

import java.util.List;

/**
 * A model's reply, as read and checked.
 *
 * @param tags the names of the tags it chose, in reply order
 */
public record TagReply( List<String> tags ) {

    public TagReply {
        tags = List.copyOf( tags ) ;
    }
}
