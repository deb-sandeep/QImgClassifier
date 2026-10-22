package com.sandy.sconsole.qimgtools.core.server;

import com.sandy.sconsole.qimgtools.core.model.Topic;

import lombok.Data;

/** A topic as the SConsole server's {@code /Master/Topic/All} returns it. */
@Data
public class ServerTopic {

    private int    id ;
    private String syllabusName ;
    private String sectionName ;
    private String topicName ;

    /** This topic as the application's {@link Topic}. */
    public Topic toTopic() {

        Topic topic = new Topic() ;
        topic.setId( id ) ;
        topic.setSyllabusName( syllabusName ) ;
        topic.setSection( sectionName ) ;
        topic.setTopicName( topicName ) ;
        return topic ;
    }
}
