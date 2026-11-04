package com.sandy.sconsole.qimgtools.apps.tagger.regression;

import com.sandy.sconsole.qimgtools.core.model.Topic;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a topic's output files in the tag workspace, which is laid out as
 * <pre>
 * topics/&lt;subject&gt;/&lt;id&gt;-&lt;name&gt;/cycles/&lt;nn&gt;-&lt;kind&gt;/output/
 * </pre>
 * The topic's folder is found by its id, so subjects need no mapping to folder
 * names. The latest cycle is the highest-numbered one whose output has both
 * files; a later cycle without them is still being built, and is passed over.
 */
@Slf4j
public final class TopicFileFinder {

    public static final String MODEL_FILE  = "regression-model.json" ;
    public static final String PROMPT_FILE = "llm-prompt.md" ;

    private static final String TOPICS_DIR = "topics" ;
    private static final String CYCLES_DIR = "cycles" ;
    private static final String OUTPUT_DIR = "output" ;

    /** The number a cycle's folder name starts with. */
    private static final Pattern CYCLE_NUMBER = Pattern.compile( "^(\\d+)" ) ;

    private TopicFileFinder() {}

    /**
     * The folder of the latest cycle of {@code topic} with both files.
     *
     * @throws TopicFileException if there is none
     */
    public static File latestCycle( File tagWorkspace, Topic topic )
            throws TopicFileException {

        File topicDir = topicDir( tagWorkspace, topic ) ;
        File cycles   = new File( topicDir, CYCLES_DIR ) ;

        List<File> sorted = new ArrayList<>( dirs( cycles ) ) ;
        sorted.sort( Comparator.comparingInt( TopicFileFinder::cycleNumber )
                               .thenComparing( File::getName )
                               .reversed() ) ;
        for( File cycle : sorted ) {
            if( hasFile( cycle, MODEL_FILE ) && hasFile( cycle, PROMPT_FILE ) ) {
                return cycle ;
            }
            log.warn( "{}: passing over {}, it lacks {} or {}",
                      topic.getTopicName(), cycle.getName(), MODEL_FILE,
                      PROMPT_FILE ) ;
        }
        throw new TopicFileException( topic.getTopicName() + " has no " +
                "regression model: no cycle has both " + MODEL_FILE + " and " +
                PROMPT_FILE ) ;
    }

    /** True if {@code topic} has a cycle with both files. */
    public static boolean hasFiles( File tagWorkspace, Topic topic ) {
        try {
            latestCycle( tagWorkspace, topic ) ;
            return true ;
        }
        catch( TopicFileException e ) {
            log.debug( "{}", e.getMessage() ) ;
            return false ;
        }
    }

    /** The file named {@code name} in {@code cycle}'s output. */
    public static File file( File cycle, String name ) {
        return new File( new File( cycle, OUTPUT_DIR ), name ) ;
    }

    private static File topicDir( File tagWorkspace, Topic topic )
            throws TopicFileException {

        File topics = new File( tagWorkspace, TOPICS_DIR ) ;
        if( !topics.isDirectory() ) {
            throw new TopicFileException( "No tag workspace at " + tagWorkspace ) ;
        }

        String     prefix = topic.getId() + "-" ;
        List<File> found  = new ArrayList<>() ;

        for( File subject : dirs( topics ) ) {
            for( File dir : dirs( subject ) ) {
                if( dir.getName().startsWith( prefix ) ) found.add( dir ) ;
            }
        }
        if( found.isEmpty() ) {
            throw new TopicFileException( topic.getTopicName() + " has no " +
                    "regression model: no folder for it in the tag workspace" ) ;
        }
        if( found.size() > 1 ) {
            log.warn( "{} has several folders: {}", topic.getTopicName(), found ) ;
            throw new TopicFileException( topic.getTopicName() + " has " +
                    found.size() + " folders in the tag workspace: " + found ) ;
        }
        return found.get( 0 ) ;
    }

    private static List<File> dirs( File parent ) {

        File[] all = parent.listFiles( File::isDirectory ) ;
        return all == null ? List.of() : Arrays.asList( all ) ;
    }

    private static boolean hasFile( File cycle, String name ) {
        return file( cycle, name ).isFile() ;
    }

    /** The number {@code cycle}'s name starts with; -1 if it has none. */
    private static int cycleNumber( File cycle ) {

        Matcher m = CYCLE_NUMBER.matcher( cycle.getName() ) ;
        if( !m.find() ) return -1 ;
        try {
            return Integer.parseInt( m.group( 1 ) ) ;
        }
        catch( NumberFormatException e ) {
            return -1 ;
        }
    }
}
