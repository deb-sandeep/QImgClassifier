package com.sandy.sconsole.qimgtools.apps.tagger.regression;

/**
 * Why a topic's regression model and LLM prompt can't be used. The message is
 * written for the user: it is shown in the status bar as it is.
 */
@SuppressWarnings( "serial" )
public class TopicFileException extends Exception {

    public TopicFileException( String message ) {
        super( message ) ;
    }

    public TopicFileException( String message, Throwable cause ) {
        super( message, cause ) ;
    }
}
