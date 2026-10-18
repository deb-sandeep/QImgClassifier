package com.sandy.sconsole.topicresolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Picks the topic a question is filed under from a model's topic
 * recommendations and the order the topics are studied in.
 * <p>
 * Questions are used to curate mock tests limited to the topics studied so
 * far, so a question is filed under the topic studied last among those it
 * needs, not the one it mainly tests. The rule, given recommendations scored
 * independently (0-100) for how much the solution needs each topic:
 * <ol>
 *   <li>the two highest scored are the contenders; with only one, it wins;</li>
 *   <li>if the second scores {@value #SECOND_TOPIC_FLOOR} or less, the first
 *       (dominant) wins;</li>
 *   <li>if either is neutral (a catch-all topic such as Units and
 *       Measurements, used everywhere) the dominant wins;</li>
 *   <li>if either is not in the sequence, or they are of different
 *       syllabuses, the dominant wins;</li>
 *   <li>otherwise the one later in its syllabus' sequence wins.</li>
 * </ol>
 * The sequence and the neutral topics are read from {@link #SEQUENCE_FILE},
 * which QImgClassifier and QTopicReviewer generate from the SConsole server's
 * track plan:
 * <pre>
 * {
 *   "generatedAt"     : "2026-10-18T10:00:00Z",
 *   "neutralTopicIds" : [ 102, 123 ],
 *   "syllabuses"      : {
 *     "IIT Physics" : [ { "id" : 124, ... }, { "id" : 103, ... }, ... ]
 *   }
 * }
 * </pre>
 * Each syllabus lists its topics in study order; only {@code id} is read.
 * <p>
 * <b>Shared source.</b> This class is standalone (JDK and Jackson only) and
 * its source is copied unchanged into QImgExtractor, which only uses it. The
 * master copy is in QImgClassifier: change it there, then copy it across.
 * Thread-safe.
 */
public final class TopicResolver {

    /** The topic sequence file, shared by every app that resolves topics. */
    public static final Path SEQUENCE_FILE =
            Path.of( System.getProperty( "user.home" ), ".sconsole",
                     "topic-sequence.json" ) ;

    /**
     * A second contender must score above this (0-100) to be weighed against
     * the dominant topic.
     */
    public static final int SECOND_TOPIC_FLOOR = 30 ;

    /**
     * One recommendation of the model.
     *
     * @param topicId    the topic's id
     * @param confidence how much the solution needs the topic, 0-100
     */
    public record Recommendation( int topicId, int confidence ) {}

    /**
     * The topic chosen for a question.
     *
     * @param topicId    the chosen topic
     * @param confidence its own confidence, 0-100
     * @param reason     why it was chosen, for logs
     */
    public record Resolution( int topicId, int confidence, String reason ) {}

    private static final ObjectMapper MAPPER = new ObjectMapper() ;

    /** The resolver for {@link #SEQUENCE_FILE} and the time it was read. */
    private static TopicResolver current ;
    private static FileTime      currentModified ;

    private final String            generatedAt ;
    private final Set<Integer>      neutralIds ;
    private final Map<Integer,Slot> slots ;

    /** A topic's place in its syllabus' sequence. */
    private record Slot( String syllabus, int position ) {}

    private TopicResolver( String generatedAt, Set<Integer> neutralIds,
                           Map<Integer,Slot> slots ) {

        this.generatedAt = generatedAt ;
        this.neutralIds  = Set.copyOf( neutralIds ) ;
        this.slots       = Map.copyOf( slots ) ;
    }

    /**
     * The resolver for {@link #SEQUENCE_FILE} as it is now: read on first
     * use, and again whenever the file has changed since.
     *
     * @throws IOException if the file is missing, unreadable or malformed
     */
    public static synchronized TopicResolver current() throws IOException {

        FileTime modified ;
        try {
            modified = Files.getLastModifiedTime( SEQUENCE_FILE ) ;
        }
        catch( NoSuchFileException e ) {
            throw new IOException( "The topic sequence file " + SEQUENCE_FILE +
                                   " is missing; generate it from " +
                                   "QImgClassifier or QTopicReviewer", e ) ;
        }
        if( current == null || !modified.equals( currentModified ) ) {
            current         = load( SEQUENCE_FILE ) ;
            currentModified = modified ;
        }
        return current ;
    }

    /**
     * A resolver for the sequence in {@code file}.
     *
     * @throws IOException if the file is missing, unreadable or malformed
     */
    public static TopicResolver load( Path file ) throws IOException {

        JsonNode root       = MAPPER.readTree( file.toFile() ) ;
        JsonNode syllabuses = root == null ? null : root.get( "syllabuses" ) ;

        if( syllabuses == null || !syllabuses.isObject() ) {
            throw new IOException( file + " has no syllabuses" ) ;
        }

        Map<Integer,Slot> slots = new HashMap<>() ;
        Iterator<Map.Entry<String,JsonNode>> it = syllabuses.fields() ;
        while( it.hasNext() ) {
            Map.Entry<String,JsonNode> e = it.next() ;
            int position = 0 ;
            for( JsonNode topic : e.getValue() ) {
                JsonNode id = topic.get( "id" ) ;
                if( id == null || !id.canConvertToInt() ) {
                    throw new IOException( file + ": a topic of " + e.getKey() +
                                           " has no id" ) ;
                }
                slots.put( id.asInt(), new Slot( e.getKey(), position++ ) ) ;
            }
        }

        Set<Integer> neutral = new HashSet<>() ;
        for( JsonNode id : root.path( "neutralTopicIds" ) ) {
            neutral.add( id.asInt() ) ;
        }
        return new TopicResolver( root.path( "generatedAt" ).asText( null ),
                                  neutral, slots ) ;
    }

    /** When the sequence was generated, as the file says; may be null. */
    public String generatedAt() {
        return generatedAt ;
    }

    /**
     * The topic to file a question under, from its recommendations in any
     * order (sorted here by confidence, highest first; equal confidences keep
     * their order).
     *
     * @return null if there are no recommendations
     */
    public Resolution resolve( List<Recommendation> recommendations ) {

        if( recommendations.isEmpty() ) return null ;

        List<Recommendation> recs = new ArrayList<>( recommendations ) ;
        recs.sort( Comparator.comparingInt( Recommendation::confidence )
                             .reversed() ) ;

        Recommendation first = recs.get( 0 ) ;
        if( recs.size() == 1 ) {
            return dominant( first, "the only topic" ) ;
        }

        Recommendation second = recs.get( 1 ) ;
        if( second.confidence() <= SECOND_TOPIC_FLOOR ) {
            return dominant( first, "second topic " + second.topicId() + " at " +
                                    second.confidence() + "% is not above " +
                                    SECOND_TOPIC_FLOOR + "%" ) ;
        }
        for( Recommendation r : List.of( first, second ) ) {
            if( neutralIds.contains( r.topicId() ) ) {
                return dominant( first, "topic " + r.topicId() + " is neutral" ) ;
            }
            if( !slots.containsKey( r.topicId() ) ) {
                return dominant( first, "topic " + r.topicId() +
                                        " is not in the sequence" ) ;
            }
        }

        Slot a = slots.get( first.topicId() ) ;
        Slot b = slots.get( second.topicId() ) ;
        if( !a.syllabus().equals( b.syllabus() ) ) {
            return dominant( first, "topics " + first.topicId() + " and " +
                                    second.topicId() + " are of different " +
                                    "syllabuses" ) ;
        }
        if( b.position() > a.position() ) {
            return new Resolution( second.topicId(), second.confidence(),
                                   "topic " + second.topicId() + " is studied " +
                                   "after the dominant topic " +
                                   first.topicId() ) ;
        }
        return dominant( first, "it is studied after topic " +
                                second.topicId() ) ;
    }

    private static Resolution dominant( Recommendation r, String why ) {
        return new Resolution( r.topicId(), r.confidence(),
                               "dominant topic " + r.topicId() + ": " + why ) ;
    }
}
