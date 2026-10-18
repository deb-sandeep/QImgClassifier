package com.sandy.sconsole.qimgtools.core.sequence;

import com.sandy.sconsole.qimgtools.core.model.Subject;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer;
import com.sandy.sconsole.qimgtools.core.server.SConsoleServer.ServerException;
import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;
import com.sandy.sconsole.topicresolver.TopicResolver;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes {@link TopicResolver#SEQUENCE_FILE}, the order the topics of each
 * IIT syllabus are studied in, from the SConsole server's track plan
 * ({@code GET /Master/Track/All}) and topic list
 * ({@code GET /Master/Topic/All}).
 * <p>
 * A syllabus is planned as tracks running side by side, each a run of topics
 * with start dates. A syllabus' sequence is:
 * <ol>
 *   <li>its topics in no track (taken as already studied), by id;</li>
 *   <li>then its scheduled topics by start date, across all its tracks; a
 *       tie goes to the topic of the track that started first.</li>
 * </ol>
 * The file also lists {@link #NEUTRAL_TOPIC_IDS}. Other syllabuses on the
 * server (e.g. Reasoning) are left out.
 */
@Slf4j
public final class TopicSequenceGenerator {

    /**
     * Catch-all topics used across the syllabus, never weighed by when they
     * are studied: Units and Measurements, Practical Skills.
     */
    public static final List<Integer> NEUTRAL_TOPIC_IDS = List.of( 102, 123 ) ;

    /** The syllabuses written, in this order. */
    private static final List<String> SYLLABUSES = List.of(
            Subject.PHYSICS.getSyllabusName(),
            Subject.CHEMISTRY.getSyllabusName(),
            Subject.MATHS.getSyllabusName() ) ;

    /**
     * What a generation wrote: per syllabus, how many topics were scheduled
     * and the ids of those that were not.
     */
    public record Summary( Map<String,Integer> scheduled,
                           Map<String,List<Integer>> unscheduled ) {

        /** One line per syllabus, for a message to the user. */
        public String describe() {

            StringBuilder sb = new StringBuilder() ;
            scheduled.forEach( ( syllabus, n ) -> {
                List<Integer> rest = unscheduled.get( syllabus ) ;
                sb.append( syllabus ).append( ": " ).append( n )
                  .append( " scheduled" ) ;
                if( !rest.isEmpty() ) {
                    sb.append( ", not in any track (first): " ).append( rest ) ;
                }
                sb.append( '\n' ) ;
            } ) ;
            return sb.toString().trim() ;
        }
    }

    /** A topic of the server's list. */
    private record ServerTopic( int id, String topicName ) {}

    /** A topic's assignment to a track, with what orders it. */
    private record Scheduled( int topicId, Instant start, Instant trackStart,
                              int trackId, int sequence ) {}

    private final SConsoleServer server ;

    public TopicSequenceGenerator( SConsoleServer server ) {
        this.server = server ;
    }

    /**
     * Generate the file if it does not exist.
     *
     * @return what was written; null if the file was already there
     */
    public Summary generateIfMissing() throws ServerException, IOException {
        return Files.exists( TopicResolver.SEQUENCE_FILE ) ? null : generate() ;
    }

    /**
     * Fetch the track plan and the topics, and (re)write the file. Nothing is
     * written if a fetch fails.
     */
    public Summary generate() throws ServerException, IOException {

        JsonNode tracks = server.get( "/Master/Track/All",
                                      new TypeReference<JsonNode>() {} ) ;
        ObjectNode root = JsonFiles.MAPPER.createObjectNode() ;

        root.put( "generatedAt", Instant.now().toString() ) ;
        root.put( "server", server.baseUrl() ) ;
        ArrayNode neutral = root.putArray( "neutralTopicIds" ) ;
        NEUTRAL_TOPIC_IDS.forEach( neutral::add ) ;

        ObjectNode syllabuses = root.putObject( "syllabuses" ) ;
        Map<String,Integer>       scheduled   = new LinkedHashMap<>() ;
        Map<String,List<Integer>> unscheduled = new LinkedHashMap<>() ;

        for( String syllabus : SYLLABUSES ) {
            List<ServerTopic> topics = topics( syllabus ) ;
            List<Scheduled>   plan   = scheduled( tracks, syllabus ) ;
            ArrayNode         list   = syllabuses.putArray( syllabus ) ;

            Map<Integer,String> names   = new LinkedHashMap<>() ;
            Set<Integer>        planned = new HashSet<>() ;

            topics.forEach( t -> names.put( t.id(), t.topicName() ) ) ;
            plan.forEach( s -> planned.add( s.topicId() ) ) ;

            List<Integer> rest = topics.stream()
                                       .map( ServerTopic::id )
                                       .filter( id -> !planned.contains( id ) )
                                       .sorted()
                                       .toList() ;
            for( int id : rest ) {
                list.addObject()
                    .put( "id", id )
                    .put( "topicName", names.get( id ) )
                    .putNull( "startDate" ) ;
            }
            for( Scheduled s : plan ) {
                list.addObject()
                    .put( "id", s.topicId() )
                    .put( "topicName", names.get( s.topicId() ) )
                    .put( "startDate", s.start().toString() ) ;
            }
            scheduled.put( syllabus, plan.size() ) ;
            unscheduled.put( syllabus, rest ) ;
        }

        File file = TopicResolver.SEQUENCE_FILE.toFile() ;
        File dir  = file.getParentFile() ;

        if( !dir.isDirectory() && !dir.mkdirs() ) {
            throw new IOException( "Could not create the folder " + dir ) ;
        }
        JsonFiles.writeAtomically( file, root ) ;

        Summary summary = new Summary( scheduled, unscheduled ) ;
        log.info( "Wrote the topic sequence {} from {}:\n{}", file,
                  server.baseUrl(), summary.describe() ) ;
        return summary ;
    }

    /** The server's topics of {@code syllabus}. */
    private List<ServerTopic> topics( String syllabus ) throws ServerException {

        List<ServerTopic> topics = server.get(
                "/Master/Topic/All?syllabusName=" + SConsoleServer.encode( syllabus ),
                new TypeReference<List<ServerTopic>>() {} ) ;
        if( topics.isEmpty() ) {
            throw new ServerException( "the server has no topics for " + syllabus ) ;
        }
        return topics ;
    }

    /** The topics the tracks of {@code syllabus} schedule, in study order. */
    private static List<Scheduled> scheduled( JsonNode tracks, String syllabus )
            throws ServerException {

        List<Scheduled> plan = new ArrayList<>() ;
        for( JsonNode track : tracks ) {
            if( !syllabus.equals( track.path( "syllabusName" ).asText() ) ) {
                continue ;
            }
            int     trackId    = track.path( "id" ).asInt() ;
            Instant trackStart = instant( track.path( "startDate" ) ) ;

            for( JsonNode a : track.path( "assignedTopics" ) ) {
                plan.add( new Scheduled( a.path( "topicId" ).asInt(),
                                         instant( a.path( "startDate" ) ),
                                         trackStart, trackId,
                                         a.path( "sequence" ).asInt() ) ) ;
            }
        }
        plan.sort( Comparator.comparing( Scheduled::start )
                             .thenComparing( Scheduled::trackStart )
                             .thenComparingInt( Scheduled::trackId )
                             .thenComparingInt( Scheduled::sequence ) ) ;
        return plan ;
    }

    /** A server date such as {@code 2025-09-30T18:30:00.000+00:00}. */
    private static Instant instant( JsonNode date ) throws ServerException {
        try {
            return OffsetDateTime.parse( date.asText() ).toInstant() ;
        }
        catch( DateTimeParseException e ) {
            throw new ServerException( "unexpected date in the track plan: " +
                                       date ) ;
        }
    }
}
