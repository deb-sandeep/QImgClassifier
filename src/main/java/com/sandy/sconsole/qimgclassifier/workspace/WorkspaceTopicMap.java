package com.sandy.sconsole.qimgclassifier.workspace;

import com.sandy.sconsole.qimgclassifier.model.AIAgent;
import com.sandy.sconsole.qimgclassifier.model.Topic;
import com.sandy.sconsole.qimgclassifier.model.TopicRepo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.sandy.sconsole.qimgclassifier.workspace.JsonFiles.MAPPER;

/**
 * Reads and updates a source's {@code .workspace/ai-topic-map.json}: the
 * model's topic recommendations for each question, keyed by question id,
 * and who made them.
 * <pre>
 * { "C_SCA_74" : {
 *     "classifiedBy" : { "actor" : "AI", "agent" : "openrouter",
 *                        "model" : "google/gemini-2.5-flash" },
 *     "topicMappings" : [
 *       { "topicId" : 12, "topicName" : "...", "syllabusName" : "...",
 *         "confidenceLevel" : 92 },
 *       ... ] } }
 * </pre>
 * A hand-picked topic is classified by {@code "actor" : "User"} with null
 * agent and model. Entries written before {@code classifiedBy} existed have
 * none.
 * A question present in the file is "classified" (even with an empty list).
 * Each question is merged into the file individually, so a single re-queued
 * question can be persisted on its own. All access to a file is serialised
 * through {@link JsonFiles#lockFor}. Reads treat an unreadable file as empty;
 * writes refuse to touch it, so a corrupt file is never silently replaced.
 */
@Slf4j
@NoArgsConstructor( access = AccessLevel.PRIVATE )
public final class WorkspaceTopicMap {

    /** Mapping flag marking a topic chosen by hand rather than by the model. */
    static final String HUMAN_INTERVENTION = "humanIntervention" ;

    /** One stored topic recommendation. */
    public record StoredMapping( int topicId, int confidenceLevel ) {}

    // ---- reads -------------------------------------------------------

    /** Question ids that have an entry in the source's map file. */
    public static Set<String> classifiedQuestionIds( File srcDir ) {
        Set<String> ids = new HashSet<>() ;
        readOrEmpty( srcDir ).fieldNames().forEachRemaining( ids::add ) ;
        return ids ;
    }

    /**
     * Question id -> {@link #summary} of its stored top topic, for every
     * question in the source's map file. A question stored with no topics maps
     * to "".
     */
    public static Map<String,String> associatedSummaries( File srcDir ) {

        Map<String,String> summaries = new HashMap<>() ;
        readOrEmpty( srcDir ).fields().forEachRemaining( e -> {
            JsonNode top = topMapping( e.getValue() ) ;
            summaries.put( e.getKey(), top.isMissingNode() ? "" :
                    summary( top.path( "topicName" ).asText(),
                             top.path( "confidenceLevel" ).asInt(),
                             top.path( HUMAN_INTERVENTION ).asBoolean() ) ) ;
        } ) ;
        return summaries ;
    }

    /**
     * Question id -> topic id of its stored top topic, for every question
     * stored with topics.
     */
    public static Map<String,Integer> topTopicIds( File srcDir ) {

        Map<String,Integer> ids = new HashMap<>() ;
        readOrEmpty( srcDir ).fields().forEachRemaining( e -> {
            JsonNode top = topMapping( e.getValue() ) ;
            if( !top.isMissingNode() ) {
                ids.put( e.getKey(), top.path( "topicId" ).asInt() ) ;
            }
        } ) ;
        return ids ;
    }

    /**
     * The stored recommendations for one question, confidence-descending.
     * Empty if the question has no entry or was stored with no topics.
     */
    /**
     * The AI model that made the question's stored recommendations; null if
     * they were hand-picked, predate this record, or there are none.
     */
    public static String classifierModel( File srcDir, String questionId ) {

        JsonNode model = readOrEmpty( srcDir ).path( questionId )
                                              .path( "classifiedBy" ).path( "model" ) ;
        return model.isTextual() ? model.asText() : null ;
    }

    public static List<StoredMapping> topicMappings( File srcDir,
                                                     String questionId ) {

        List<StoredMapping> mappings = new ArrayList<>() ;
        JsonNode stored = readOrEmpty( srcDir ).path( questionId )
                                               .path( "topicMappings" ) ;

        for( JsonNode m : stored ) {
            mappings.add( new StoredMapping( m.path( "topicId" ).asInt(),
                                             m.path( "confidenceLevel" ).asInt() ) ) ;
        }
        mappings.sort( Comparator.comparingInt( StoredMapping::confidenceLevel )
                                 .reversed() ) ;
        return mappings ;
    }

    /**
     * Display form of a recommendation, as shown in the Result column:
     * {@code "Topic (NN%)"}, suffixed {@code " [manual]"} if hand-picked.
     */
    public static String summary( String topicName, int confidenceLevel,
                                  boolean humanIntervention ) {
        return String.format( "%s (%d%%)%s", topicName, confidenceLevel,
                              humanIntervention ? " [manual]" : "" ) ;
    }

    // ---- writes ------------------------------------------------------

    /**
     * Store the model's recommendations for one question, replacing any it
     * had. Topic ids missing from the master are dropped; a name that differs
     * from the master is logged and the master name stored.
     *
     * @throws IOException if the map file cannot be read or written; it is left
     *         untouched
     */
    public static void write( File srcDir, String questionId,
                              List<Topic> matchedTopics, AIAgent agent,
                              String model ) throws IOException {

        TopicRepo topicRepo = TopicRepo.instance() ;
        ArrayNode topicMappings = MAPPER.createArrayNode() ;

        for( Topic t : matchedTopics ) {
            Topic master = topicRepo.getTopicById( t.getId() ) ;
            if( master == null ) {
                log.warn( "[{}] Model returned unknown topicId {} ({}) - skipping",
                          questionId, t.getId(), t.getTopicName() ) ;
                continue ;
            }
            if( !master.getTopicName().equals( t.getTopicName() ) ) {
                log.warn( "[{}] Topic name mismatch for topicId {} - " +
                          "master {}, model {}; using master name", questionId,
                          t.getId(), master.getTopicName(), t.getTopicName() ) ;
            }
            int confidence = Math.round( t.getMatchProbability() * 100 ) ;
            addMapping( topicMappings, master, confidence ) ;
        }
        putQuestion( srcDir, questionId, classifiedBy( "AI", agent, model ),
                     topicMappings ) ;
    }

    /**
     * Replace the question's recommendations with a single hand-picked topic
     * at 100% confidence, flagged {@value #HUMAN_INTERVENTION}.
     *
     * @throws IOException if the map file cannot be read or written; it is left
     *         untouched
     */
    public static void writeManual( File srcDir, String questionId,
                                    Topic topic ) throws IOException {
        ArrayNode topicMappings = MAPPER.createArrayNode() ;
        addMapping( topicMappings, topic, 100 ).put( HUMAN_INTERVENTION, true ) ;
        putQuestion( srcDir, questionId, classifiedBy( "User", null, null ),
                     topicMappings ) ;
    }

    // ---- internals ---------------------------------------------------

    private static File mapFile( File srcDir ) {
        return new File( srcDir, ".workspace/ai-topic-map.json" ) ;
    }

    /**
     * First (top) entry of a question's stored mappings; a missing node if
     * there is none.
     */
    private static JsonNode topMapping( JsonNode questionEntry ) {
        return questionEntry.path( "topicMappings" ).path( 0 ) ;
    }

    /** Read-modify-write of one question's entry. */
    private static void putQuestion( File srcDir, String questionId,
                                     ObjectNode classifiedBy,
                                     ArrayNode topicMappings ) throws IOException {

        File file = mapFile( srcDir ) ;
        synchronized( JsonFiles.lockFor( file ) ) {
            ObjectNode root  = JsonFiles.readObject( file ) ;
            ObjectNode entry = root.putObject( questionId ) ;

            entry.set( "classifiedBy", classifiedBy ) ;
            entry.set( "topicMappings", topicMappings ) ;
            file.getParentFile().mkdirs() ;
            JsonFiles.writeAtomically( file, root ) ;
        }
    }

    private static ObjectNode classifiedBy( String actor, AIAgent agent,
                                            String model ) {
        return MAPPER.createObjectNode()
                .put( "actor", actor )
                .put( "agent", agent != null ? agent.getLabel() : null )
                .put( "model", model ) ;
    }

    private static ObjectNode addMapping( ArrayNode topicMappings, Topic topic,
                                          int confidenceLevel ) {
        return topicMappings.addObject()
                .put( "topicId",         topic.getId() )
                .put( "topicName",       topic.getTopicName() )
                .put( "syllabusName",    topic.getSyllabusName() )
                .put( "confidenceLevel", confidenceLevel ) ;
    }

    /**
     * The map file's root object; empty if it does not exist or cannot be read
     * (logged).
     */
    private static ObjectNode readOrEmpty( File srcDir ) {

        File file = mapFile( srcDir ) ;
        synchronized( JsonFiles.lockFor( file ) ) {
            try {
                return JsonFiles.readObject( file ) ;
            }
            catch( IOException e ) {
                log.error( "Error reading {} - treating it as empty", file, e ) ;
                return MAPPER.createObjectNode() ;
            }
        }
    }
}
