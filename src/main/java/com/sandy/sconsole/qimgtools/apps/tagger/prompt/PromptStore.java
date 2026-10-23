package com.sandy.sconsole.qimgtools.apps.tagger.prompt;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagCatalogue;
import com.sandy.sconsole.qimgtools.core.model.Topic;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * The topic prompts, in the source tree ({@code prompts.dir}):
 * <pre>
 * qtagger.prompt                   the shared template
 * axes.prompt                      the axis rules, one section per variant
 * &lt;syllabus&gt;/&lt;id&gt;-&lt;topic name&gt;/
 *   tags.generated.prompt          the mechanical base, rewritten by Generate
 *   tags.reviewed.prompt           tuned by hand, approved; the one runs use
 * </pre>
 * {@code tags.reviewed.prompt}'s header says whether it is approved, for
 * which catalogue and text: {@code topicId}, {@code status} ({@code draft} /
 * {@code approved}), {@code promptVersion}, {@code catalogueFingerprint},
 * {@code contentHash}, {@code generatedAt}, {@code approvedAt}. Files are
 * read afresh on every call, so an edit takes effect without a restart.
 * Writes go through a temp file and a rename. Thread-safe.
 */
@Slf4j
public final class PromptStore {

    public static final String TEMPLATE  = "qtagger.prompt" ;
    public static final String AXES      = "axes.prompt" ;
    public static final String GENERATED = "tags.generated.prompt" ;
    public static final String REVIEWED  = "tags.reviewed.prompt" ;

    public static final String DRAFT    = "draft" ;
    public static final String APPROVED = "approved" ;

    /** Where a topic's prompt stands. */
    public enum State {

        NONE( "none" ), BASE_ONLY( "base only" ), DRAFT( "draft" ),
        APPROVED( "approved" ), STALE( "stale" ) ;

        private final String label ;

        State( String label ) {
            this.label = label ;
        }

        @Override
        public String toString() { return label ; }
    }

    private final File root ;

    public PromptStore( File root ) {
        this.root = root ;
    }

    public File root() {
        return root ;
    }

    /** The topic's folder: {@code <syllabus>/<id>-<name>}. */
    public File dir( Topic topic ) {
        return new File( new File( root, safe( topic.getSyllabusName() ) ),
                         topic.getId() + "-" + safe( topic.getTopicName() ) ) ;
    }

    /** {@code name} with the characters a file name can't hold replaced. */
    private static String safe( String name ) {
        return name.replaceAll( "[/\\\\:*?\"<>|]", "-" ).strip() ;
    }

    /**
     * The shared template.
     *
     * @throws IOException if it is missing or unreadable
     */
    public PromptFile template() throws IOException {
        return read( new File( root, TEMPLATE ) ) ;
    }

    /**
     * The template's {@code {{axes.prompt}}} text: an {@code ## Own} and a
     * {@code ## Dependent} section.
     *
     * @throws IOException if it is missing or unreadable
     */
    public PromptFile axes() throws IOException {
        return read( new File( root, AXES ) ) ;
    }

    /** The topic's {@code tags.reviewed.prompt}; null if it has none. */
    public PromptFile topicPrompt( Topic topic ) throws IOException {
        File f = new File( dir( topic ), REVIEWED ) ;
        return f.isFile() ? read( f ) : null ;
    }

    /** The topic's {@code tags.generated.prompt}; null if it has none. */
    public PromptFile generated( Topic topic ) throws IOException {
        File f = new File( dir( topic ), GENERATED ) ;
        return f.isFile() ? read( f ) : null ;
    }

    /**
     * Where the topic's prompt stands, from its files and, when given, the
     * catalogue now ({@code catalogue} null: an approved file is taken as
     * current).
     */
    public State state( Topic topic, TagCatalogue catalogue )
            throws IOException {

        PromptFile p = topicPrompt( topic ) ;
        if( p == null ) {
            return new File( dir( topic ), GENERATED ).isFile()
                   ? State.BASE_ONLY : State.NONE ;
        }
        return state( p, catalogue ) ;
    }

    /**
     * A topic's prompt at a glance, from its files alone (an approved prompt
     * is taken as current: whether its catalogue changed needs the server).
     *
     * @param state       where it stands
     * @param version     its {@code promptVersion}; 0 if it has no
     *                    {@code tags.reviewed.prompt}
     * @param approvedAt  when it was last approved; null if never
     * @param generatedAt when its base was last generated; null if never
     */
    public record Summary( State state, int version, Instant approvedAt,
                           Instant generatedAt ) {

        /** A topic with no prompt files. */
        public static final Summary NONE =
                new Summary( State.NONE, 0, null, null ) ;
    }

    /** The topic's prompt at a glance. */
    public Summary summary( Topic topic ) throws IOException {

        PromptFile reviewed  = topicPrompt( topic ) ;
        PromptFile generated = generated( topic ) ;

        if( reviewed == null ) {
            return generated == null ? Summary.NONE
                   : new Summary( State.BASE_ONLY, 0, null,
                                  instant( generated.get( "generatedAt" ) ) ) ;
        }
        return new Summary( state( reviewed, null ),
                            reviewed.getInt( "promptVersion", 0 ),
                            instant( reviewed.get( "approvedAt" ) ),
                            instant( generated != null
                                     ? generated.get( "generatedAt" )
                                     : reviewed.get( "generatedAt" ) ) ) ;
    }

    /** {@code s} as an instant; null if blank or unreadable. */
    private static Instant instant( String s ) {

        if( s == null || s.isBlank() ) return null ;
        try {
            return Instant.parse( s.trim() ) ;
        }
        catch( RuntimeException e ) {
            return null ;
        }
    }

    /** Where topic prompt {@code p} stands against {@code catalogue}. */
    public static State state( PromptFile p, TagCatalogue catalogue ) {

        if( !APPROVED.equals( p.get( "status" ) )
            || !p.contentHash().equals( p.get( "contentHash" ) ) ) {
            return State.DRAFT ;
        }
        if( catalogue != null && !catalogue.fingerprint().equals(
                p.get( "catalogueFingerprint" ) ) ) {
            return State.STALE ;
        }
        return State.APPROVED ;
    }

    /**
     * Write the topic's base prompt {@code body} to
     * {@code tags.generated.prompt}, and, if the topic has no
     * {@code tags.reviewed.prompt} yet, copy it there as a draft.
     *
     * @return true if {@code tags.reviewed.prompt} was created
     */
    public synchronized boolean writeGenerated( Topic topic, String body,
                                                TagCatalogue catalogue )
            throws IOException {

        String now = now() ;
        PromptFile generated = new PromptFile( Map.of(), body )
                .with( "topicId", topic.getId() )
                .with( "catalogueFingerprint", catalogue.fingerprint() )
                .with( "generatedAt", now ) ;

        write( new File( dir( topic ), GENERATED ), generated ) ;
        log.info( "Wrote the base prompt of topic {} to {}", topic.getId(),
                  dir( topic ) ) ;
        if( topicPrompt( topic ) != null ) return false ;

        PromptFile draft = new PromptFile( Map.of(), body )
                .with( "topicId", topic.getId() )
                .with( "status", DRAFT )
                .with( "promptVersion", 0 )
                .with( "catalogueFingerprint", "" )
                .with( "contentHash", "" )
                .with( "generatedAt", now )
                .with( "approvedAt", "" ) ;
        write( new File( dir( topic ), REVIEWED ), draft ) ;
        return true ;
    }

    /**
     * Approve the topic's {@code tags.reviewed.prompt} as it stands, for
     * {@code catalogue}: status approved, the next prompt version, the
     * catalogue's fingerprint and the text's hash.
     *
     * @return the approved file
     */
    public synchronized PromptFile approve( Topic topic,
                                            TagCatalogue catalogue )
            throws IOException {

        PromptFile p = topicPrompt( topic ) ;
        if( p == null ) {
            throw new IOException( "topic " + topic.getId() +
                                   " has no " + REVIEWED ) ;
        }
        return stamp( topic, p, catalogue ) ;
    }

    /**
     * Append {@code line} to the {@code section} (an axis heading, e.g.
     * "Setup") of the topic's approved prompt, and approve the result.
     *
     * @return the approved file
     */
    public synchronized PromptFile appendAndApprove( Topic topic,
                                                     String section,
                                                     String line,
                                                     TagCatalogue catalogue )
            throws IOException {

        PromptFile p = topicPrompt( topic ) ;
        if( p == null ) {
            throw new IOException( "topic " + topic.getId() +
                                   " has no " + REVIEWED ) ;
        }
        String body = PromptText.appendToSection( p.body(), section, line ) ;
        return stamp( topic, new PromptFile( p.header(), body ), catalogue ) ;
    }

    private PromptFile stamp( Topic topic, PromptFile p,
                              TagCatalogue catalogue ) throws IOException {

        PromptFile approved = p
                .with( "topicId", topic.getId() )
                .with( "status", APPROVED )
                .with( "promptVersion", p.getInt( "promptVersion", 0 ) + 1 )
                .with( "catalogueFingerprint", catalogue.fingerprint() )
                .with( "contentHash", p.contentHash() )
                .with( "approvedAt", now() ) ;

        write( new File( dir( topic ), REVIEWED ), approved ) ;
        log.info( "Approved the prompt of topic {}, version {}", topic.getId(),
                  approved.get( "promptVersion" ) ) ;
        return approved ;
    }

    private static PromptFile read( File f ) throws IOException {
        return PromptFile.parse( Files.readString( f.toPath(),
                                                   StandardCharsets.UTF_8 ) ) ;
    }

    private static void write( File f, PromptFile p ) throws IOException {

        Files.createDirectories( f.getParentFile().toPath() ) ;
        File tmp = new File( f.getParentFile(), f.getName() + ".tmp" ) ;
        Files.writeString( tmp.toPath(), p.text(), StandardCharsets.UTF_8 ) ;
        Files.move( tmp.toPath(), f.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE ) ;
    }

    private static String now() {
        return Instant.now().truncatedTo( ChronoUnit.SECONDS ).toString() ;
    }
}
