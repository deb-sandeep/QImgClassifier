package com.sandy.sconsole.qimgclassifier.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A set of syllabus topics. The classifier uses the topic master
 * ({@link #instance()}), loaded once from the {@code /topic_master.json}
 * classpath resource; if that is missing or unreadable the repo is empty
 * (and the error logged). The topic reviewer builds one from the server's
 * topic list. Topic ids are the ones QImgExtractor and the SConsole database
 * use.
 */
@Slf4j
public final class TopicRepo {

    private static final String RESOURCE = "/topic_master.json" ;

    /** Lazily-initialised, thread-safe singleton holder. */
    private static final class Holder {
        private static final TopicRepo INSTANCE = new TopicRepo() ;
    }

    public static TopicRepo instance() {
        return Holder.INSTANCE ;
    }

    private final Map<Integer,Topic>     topicById        = new HashMap<>() ;
    private final Map<String,List<Topic>> topicsBySyllabus = new HashMap<>() ;

    /** A repo of {@code topics}, e.g. the server's topic list. */
    public TopicRepo( List<Topic> topics ) {
        topics.forEach( this::add ) ;
        freeze() ;
    }

    private TopicRepo() {

        try( InputStream is = TopicRepo.class.getResourceAsStream( RESOURCE ) ) {
            if( is == null ) {
                log.error( "{} not found in classpath", RESOURCE ) ;
                return ;
            }
            ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(
                    PropertyNamingStrategies.SNAKE_CASE ) ;
            List<Topic> topics = mapper.readValue( is, new TypeReference<>() {} ) ;

            topics.forEach( this::add ) ;
        }
        catch( Exception e ) {
            log.error( "Error loading topics from {}", RESOURCE, e ) ;
        }
        freeze() ;
    }

    private void add( Topic topic ) {
        topicById.put( topic.getId(), topic ) ;
        topicsBySyllabus.computeIfAbsent( topic.getSyllabusName(),
                                          k -> new ArrayList<>() )
                        .add( topic ) ;
    }

    /** Make the per-syllabus lists unmodifiable once loaded. */
    private void freeze() {
        topicsBySyllabus.replaceAll(
                ( k, v ) -> Collections.unmodifiableList( v ) ) ;
    }

    /** The master topic with this id, or null if there is none. */
    public Topic getTopicById( int id ) {
        return topicById.get( id ) ;
    }

    /**
     * The subject's topics in master-file order (unmodifiable); empty for a
     * subject without a syllabus.
     */
    public List<Topic> getTopics( Subject subject ) {
        if( !subject.hasSyllabus() ) return List.of() ;
        return topicsBySyllabus.getOrDefault( subject.getSyllabusName(),
                                              List.of() ) ;
    }
}
