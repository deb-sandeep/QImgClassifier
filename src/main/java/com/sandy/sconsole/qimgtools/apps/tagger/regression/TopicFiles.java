package com.sandy.sconsole.qimgtools.apps.tagger.regression;

import com.sandy.sconsole.qimgtools.apps.tagger.model.CatalogueTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagCatalogue;
import com.sandy.sconsole.qimgtools.apps.tagger.prompt.TopicPrompt;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.RegressionModel.Route;
import com.sandy.sconsole.qimgtools.apps.tagger.regression.RegressionModel.TagModel;
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

/**
 * A topic's regression model and LLM prompt, read directly from the tag
 * workspace in the question-bank folder and checked. Nothing is copied: the
 * two stay in memory until the next scope, so a file rebuilt meanwhile doesn't
 * change a run. The hashes tell, later on, whether a cycle was rebuilt under
 * the same name. Immutable.
 *
 * @param cycleDir   the folder of the cycle they came from
 * @param modelHash  SHA-256 of the model file, as read
 * @param promptHash SHA-256 of the prompt file, as read
 */
public record TopicFiles( File cycleDir, RegressionModel model,
                          TopicPrompt prompt, String modelHash,
                          String promptHash ) {

    /**
     * The latest cycle's files of {@code topic}.
     *
     * @throws TopicFileException if there are none, or they fail a check that
     *         needs no server
     */
    public static TopicFiles load( File tagWorkspace, Topic topic )
            throws TopicFileException {

        File   cycle       = TopicFileFinder.latestCycle( tagWorkspace, topic ) ;
        byte[] modelBytes  = read( cycle, TopicFileFinder.MODEL_FILE ) ;
        byte[] promptBytes = read( cycle, TopicFileFinder.PROMPT_FILE ) ;

        RegressionModel model = RegressionModel.parse( modelBytes ) ;
        TopicPrompt     prompt ;
        try {
            prompt = TopicPrompt.parse( promptBytes ) ;
        }
        catch( TopicPrompt.TopicPromptException e ) {
            throw new TopicFileException( e.getMessage(), e ) ;
        }

        checkTopic( model, topic, cycle ) ;
        checkAgree( model, prompt ) ;
        return new TopicFiles( cycle, model, prompt, sha256( modelBytes ),
                               sha256( promptBytes ) ) ;
    }

    /** The name of the cycle they came from. */
    public String cycle() {
        return cycleDir.getName() ;
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
                                    File cycle ) throws TopicFileException {

        if( model.topicId() != topic.getId()
            || !model.topicName().equals( topic.getTopicName() ) ) {
            throw new TopicFileException( TopicFileFinder.MODEL_FILE + " is for " +
                    model.topicName() + " (" + model.topicId() + "), not " +
                    topic.getTopicName() + " (" + topic.getId() + ")" ) ;
        }
        if( !model.cycle().equals( cycle.getName() ) ) {
            throw new TopicFileException( TopicFileFinder.MODEL_FILE +
                    " says it is cycle " + model.cycle() + ", but is in " +
                    cycle.getName() ) ;
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

    private static byte[] read( File cycle, String name )
            throws TopicFileException {

        File file = TopicFileFinder.file( cycle, name ) ;
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
