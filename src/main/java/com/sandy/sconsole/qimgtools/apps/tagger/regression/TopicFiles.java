package com.sandy.sconsole.qimgtools.apps.tagger.regression;

import com.sandy.sconsole.qimgtools.apps.tagger.model.CatalogueTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagCatalogue;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.TopicPrompt;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.RegressionModel.Route;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.RegressionModel.TagModel;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.TopicFileFinder.Build;
import com.sandy.sconsole.qimgtools.core.model.Topic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A topic's regression model and LLM prompt, read directly from the tag
 * workspace in the question-bank folder and checked. Nothing is copied: the
 * two stay in memory until the next scope, so a file published meanwhile
 * doesn't change a run. The hashes tell, later on, exactly which files were
 * used. Immutable.
 *
 * @param build      the build they came from
 * @param modelHash  SHA-256 of the model file, as read
 * @param promptHash SHA-256 of the prompt file, as read
 */
public record TopicFiles( Build build, RegressionModel model,
                          TopicPrompt prompt, String modelHash,
                          String promptHash ) {

    /** The number a cycle's name starts with. */
    private static final Pattern CYCLE_NUMBER = Pattern.compile( "(\\d+)" ) ;

    /**
     * The latest build's files of {@code topic}.
     *
     * @throws TopicFileException if there are none, or they fail a check that
     *         needs no server
     */
    public static TopicFiles load( File tagWorkspace, Topic topic )
            throws TopicFileException {

        Build  build       = TopicFileFinder.latest( tagWorkspace, topic ) ;
        byte[] modelBytes  = read( build.model() ) ;
        byte[] promptBytes = read( build.prompt() ) ;

        RegressionModel model = RegressionModel.parse( modelBytes ) ;
        TopicPrompt     prompt ;
        try {
            prompt = TopicPrompt.parse( promptBytes ) ;
        }
        catch( TopicPrompt.TopicPromptException e ) {
            throw new TopicFileException( e.getMessage(), e ) ;
        }

        checkTopic( model, topic, build ) ;
        checkAgree( model, prompt ) ;
        return new TopicFiles( build, model, prompt, sha256( modelBytes ),
                               sha256( promptBytes ) ) ;
    }

    /**
     * The build they came from, e.g. {@code c02.v01}: what replies stored for
     * a question are kept against.
     */
    public String buildTag() {
        return build.tag() ;
    }

    /** The name of the cycle that built them, e.g. {@code 02-rebuild}. */
    public String cycle() {
        return model.cycle() ;
    }

    /**
     * The tags the model can apply or the prompt offers that aren't among the
     * server's: they couldn't be saved. A tag marked {@code never} doesn't
     * count, as nothing proposes it.
     */
    public List<String> missingFrom( TagCatalogue catalogue ) {

        Set<String> names = new LinkedHashSet<>( prompt.tagNames() ) ;
        for( TagModel t : model.tags() ) {
            if( t.route() != Route.NEVER ) names.add( t.tagText() ) ;
        }
        return names.stream().filter( n -> !catalogue.contains( n ) ).toList() ;
    }

    /**
     * The server's tags the model has no entry for: they can be applied by
     * hand, but the model can't propose them.
     */
    public List<String> notInModel( TagCatalogue catalogue ) {

        List<String> names = new ArrayList<>() ;
        for( CatalogueTag t : catalogue.tags() ) {
            if( model.tag( t.tagText() ) == null ) names.add( t.tagText() ) ;
        }
        return names ;
    }

    private static void checkTopic( RegressionModel model, Topic topic,
                                    Build build )
            throws TopicFileException {

        if( model.topicId() != topic.getId()
            || !model.topicName().equals( topic.getTopicName() ) ) {
            throw new TopicFileException( TopicFileFinder.MODEL_FILE + " is for " +
                    model.topicName() + " (" + model.topicId() + "), not " +
                    topic.getTopicName() + " (" + topic.getId() + ")" ) ;
        }
        Matcher m = CYCLE_NUMBER.matcher( model.cycle() ) ;
        if( !m.lookingAt() || Integer.parseInt( m.group( 1 ) ) != build.cycle() ) {
            throw new TopicFileException( build.model().getName() +
                    " says it is cycle " + model.cycle() + ", not cycle " +
                    build.cycle() ) ;
        }
    }

    /** Every tag the prompt offers must be one the model can use. */
    private static void checkAgree( RegressionModel model, TopicPrompt prompt )
            throws TopicFileException {

        for( String name : prompt.tagNames() ) {
            TagModel t = model.tag( name ) ;
            if( t == null ) {
                throw new TopicFileException( TopicFileFinder.PROMPT_FILE +
                        " offers " + name + ", which the model doesn't have" ) ;
            }
            if( t.route() == Route.NEVER ) {
                throw new TopicFileException( TopicFileFinder.PROMPT_FILE +
                        " offers " + name + ", which the model marks never" ) ;
            }
        }
    }

    private static byte[] read( File file ) throws TopicFileException {
        try {
            return Files.readAllBytes( file.toPath() ) ;
        }
        catch( IOException e ) {
            throw new TopicFileException( "Could not read " + file + ": " +
                                          e.getMessage(), e ) ;
        }
    }

    /** The SHA-256 of {@code bytes}, in hex. */
    public static String sha256( byte[] bytes ) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance( "SHA-256" ).digest( bytes ) ) ;
        }
        catch( NoSuchAlgorithmException e ) {
            throw new IllegalStateException( e ) ;
        }
    }
}
