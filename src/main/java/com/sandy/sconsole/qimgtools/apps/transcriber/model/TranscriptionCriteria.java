package com.sandy.sconsole.qimgtools.apps.transcriber.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Which pending questions a Load fetches.
 *
 * @param syllabusNames syllabuses, e.g. "IIT Physics"
 * @param topicIds      topics; when not empty, the server uses these instead
 *        of {@code syllabusNames}
 * @param batchSize     questions per load, 1-100
 */
public record TranscriptionCriteria( List<String> syllabusNames,
                                     List<Integer> topicIds,
                                     int batchSize ) {

    public TranscriptionCriteria {
        syllabusNames = List.copyOf( syllabusNames ) ;
        topicIds      = List.copyOf( topicIds ) ;
    }

    /**
     * True if it names a syllabus or a topic. With neither the server
     * returns every pending question, which the app doesn't ask for.
     */
    public boolean isValid() {
        return !syllabusNames.isEmpty() || !topicIds.isEmpty() ;
    }

    /** These criteria with {@code batchSize} questions per load. */
    public TranscriptionCriteria withBatchSize( int batchSize ) {
        return new TranscriptionCriteria( syllabusNames, topicIds,
                                          batchSize ) ;
    }

    /** "IIT Physics, IIT Maths" or "topics 12, 15", in full. */
    public String describe() {

        if( topicIds.isEmpty() ) return String.join( ", ", syllabusNames ) ;
        return "topics " + topicIds.stream()
                                   .map( String::valueOf )
                                   .collect( Collectors.joining( ", " ) ) ;
    }

    /**
     * {@link #describe()} kept short for the window title: more than
     * {@value #TITLE_TOPICS} topics read as their count, e.g. "14 topics".
     */
    public String shortDescription() {

        if( topicIds.size() <= TITLE_TOPICS ) return describe() ;
        return topicIds.size() + " topics" ;
    }

    /** The most topics the title lists by id. */
    private static final int TITLE_TOPICS = 3 ;
}
