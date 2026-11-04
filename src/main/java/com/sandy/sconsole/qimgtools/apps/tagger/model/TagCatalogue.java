package com.sandy.sconsole.qimgtools.apps.tagger.model;

import com.sandy.sconsole.qimgtools.core.model.Topic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The scoped topic and its tags, as the server holds them now. Tag names
 * match exactly: they are unique on the server. Immutable.
 */
public final class TagCatalogue {

    private final Topic                    topic ;
    private final List<CatalogueTag>       tags ;
    private final Map<String,CatalogueTag> byText = new LinkedHashMap<>() ;

    public TagCatalogue( Topic topic, List<CatalogueTag> tags ) {

        this.topic = topic ;
        this.tags  = List.copyOf( tags ) ;
        for( CatalogueTag t : this.tags ) byText.put( t.tagText(), t ) ;
    }

    public Topic topic() {
        return topic ;
    }

    /** Every tag, in the server's order. */
    public List<CatalogueTag> tags() {
        return tags ;
    }

    /** The tag named {@code text}; null if the topic has none. */
    public CatalogueTag get( String text ) {
        return byText.get( text ) ;
    }

    /** True if the topic has a tag named {@code text}. */
    public boolean contains( String text ) {
        return byText.containsKey( text ) ;
    }
}
