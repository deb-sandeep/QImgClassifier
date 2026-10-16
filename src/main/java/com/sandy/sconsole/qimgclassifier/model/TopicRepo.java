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
 * The topic master: every syllabus topic, loaded once from the
 * {@code /topic_master.json} classpath resource. Topic ids are the ones
 * QImgExtractor and the SConsole database use. If the resource is missing or
 * unreadable the repo is empty (and the error logged).
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

    private TopicRepo() {

        try( InputStream is = TopicRepo.class.getResourceAsStream( RESOURCE ) ) {
            if( is == null ) {
                log.error( "{} not found in classpath", RESOURCE ) ;
                return ;
            }
            ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(
                    PropertyNamingStrategies.SNAKE_CASE ) ;
            List<Topic> topics = mapper.readValue( is, new TypeReference<>() {} ) ;
            for( Topic topic : topics ) {
                topicById.put( topic.getId(), topic ) ;
                topicsBySyllabus.computeIfAbsent( topic.getSyllabusName(),
                                                  k -> new ArrayList<>() )
                                .add( topic ) ;
            }
            topicsBySyllabus.replaceAll(
                    ( k, v ) -> Collections.unmodifiableList( v ) ) ;
        }
        catch( Exception e ) {
            log.error( "Error loading topics from {}", RESOURCE, e ) ;
        }
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
