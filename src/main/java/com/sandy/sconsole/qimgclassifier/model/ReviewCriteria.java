package com.sandy.sconsole.qimgclassifier.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Which questions of the server's topic review queue to fetch.
 *
 * @param syllabusNames syllabuses, e.g. "IIT Physics"
 * @param topicIds      topics; when not empty, the server uses these instead
 *        of {@code syllabusNames}
 * @param limit         questions per fetch, 1-100
 */
public record ReviewCriteria( List<String> syllabusNames,
                              List<Integer> topicIds, int limit ) {

    public ReviewCriteria {
        syllabusNames = List.copyOf( syllabusNames ) ;
        topicIds      = List.copyOf( topicIds ) ;
    }

    /** True if the server has something to filter on. */
    public boolean isValid() {
        return !syllabusNames.isEmpty() || !topicIds.isEmpty() ;
    }

    /** "IIT Physics, IIT Maths" or "topics 12, 15", for the title. */
    public String describe() {

        if( topicIds.isEmpty() ) return String.join( ", ", syllabusNames ) ;
        return "topics " + topicIds.stream()
                                   .map( String::valueOf )
                                   .collect( Collectors.joining( ", " ) ) ;
    }
}
