package com.sandy.sconsole.qimgtools.apps.tagger.regression;

import com.sandy.sconsole.qimgtools.core.model.Topic;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a topic's published files in the tag workspace, which keeps them in
 * the topic's own output folder:
 * <pre>
 * topics/&lt;subject&gt;/&lt;id&gt;-&lt;name&gt;/output/&lt;name&gt;.c&lt;NN&gt;.v&lt;NN&gt;.&lt;ext&gt;
 * </pre>
 * One build publishes all its files under one cycle and version, and none is
 * ever overwritten. The topic's folder is found by its id, so subjects need no
 * mapping to folder names. The latest build is the highest cycle, then the
 * highest version, for which both the model and the prompt exist; a later one
 * without both is still being built, and is passed over.
 */
@Slf4j
public final class TopicFileFinder {

    public static final String MODEL_FILE   = "regression-model" ;
    public static final String PROMPT_FILE  = "llm-prompt" ;
    public static final String VECTORS_FILE = "scoring-test-vectors" ;

    private static final String TOPICS_DIR = "topics" ;
    private static final String OUTPUT_DIR = "output" ;

    /** A model or prompt file's name: its base, cycle and version. */
    private static final Pattern PUBLISHED = Pattern.compile(
            "^(" + MODEL_FILE + "|" + PROMPT_FILE + ")" +
            "\\.c(\\d+)\\.v(\\d+)\\.(json|md)$" ) ;

    /**
     * One build of a topic's files.
     *
     * @param outputDir the topic's output folder
     * @param cycle     the cycle number, the {@code c} of the names
     * @param version   the version within the cycle, the {@code v}
     */
    public record Build( File outputDir, int cycle, int version )
            implements Comparable<Build> {

        private static final Comparator<Build> ORDER =
                Comparator.comparingInt( Build::cycle )
                          .thenComparingInt( Build::version ) ;

        /** As the file names have it, e.g. {@code c02.v01}. */
        public String tag() {
            return String.format( "c%02d.v%02d", cycle, version ) ;
        }

        public File model() {
            return file( MODEL_FILE, "json" ) ;
        }

        public File prompt() {
            return file( PROMPT_FILE, "md" ) ;
        }

        public File vectors() {
            return file( VECTORS_FILE, "json" ) ;
        }

        private File file( String base, String ext ) {
            return new File( outputDir, base + "." + tag() + "." + ext ) ;
        }

        @Override
        public int compareTo( Build o ) {
            return ORDER.compare( this, o ) ;
        }

        @Override
        public String toString() {
            return tag() ;
        }
    }

    private TopicFileFinder() {}

    /**
     * The latest build of {@code topic} with both a model and a prompt.
     *
     * @throws TopicFileException if there is none
     */
    public static Build latest( File tagWorkspace, Topic topic )
            throws TopicFileException {

        File           topicDir  = topicDir( tagWorkspace, topic ) ;
        File           outputDir = new File( topicDir, OUTPUT_DIR ) ;
        TreeSet<Build> builds    = new TreeSet<>( Comparator.reverseOrder() ) ;
        String[]       names     = outputDir.list() ;

        for( String name : names == null ? new String[0] : names ) {
            Matcher m = PUBLISHED.matcher( name ) ;
            if( m.matches() ) {
                builds.add( new Build( outputDir,
                                       Integer.parseInt( m.group( 2 ) ),
                                       Integer.parseInt( m.group( 3 ) ) ) ) ;
            }
        }
        for( Build b : builds ) {
            if( b.model().isFile() && b.prompt().isFile() ) return b ;
            log.warn( "{}: passing over {}, it lacks {} or {}",
                      topic.getTopicName(), b, b.model().getName(),
                      b.prompt().getName() ) ;
        }
        throw new TopicFileException( topic.getTopicName() + " has no " +
                "regression model: no build in " + outputDir + " has both a " +
                MODEL_FILE + " and an " + PROMPT_FILE ) ;
    }

    /** True if {@code topic} has a build with both files. */
    public static boolean hasFiles( File tagWorkspace, Topic topic ) {
        try {
            latest( tagWorkspace, topic ) ;
            return true ;
        }
        catch( TopicFileException e ) {
            log.debug( "{}", e.getMessage() ) ;
            return false ;
        }
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
}
