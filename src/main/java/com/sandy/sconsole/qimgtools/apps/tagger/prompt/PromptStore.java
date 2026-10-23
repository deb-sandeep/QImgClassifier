package com.sandy.sconsole.qimgtools.apps.tagger.prompt;

import com.sandy.sconsole.qimgtools.apps.tagger.model.TagCatalogue;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagClass;
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
 * The topic prompts of one pass, in the source tree ({@code prompts.dir}),
 * where both passes keep theirs side by side:
 * <pre>
 * qtagger.setup.prompt             the setup pass' template
 * qtagger.concept.prompt           the concept pass' template
 * qtagger.concept.axes.prompt      its axis rules, one section per variant
 * &lt;syllabus&gt;/&lt;id&gt;-&lt;topic name&gt;/
 *   setup-tags.generated.prompt    the mechanical base, rewritten by Generate
 *   setup-tags.reviewed.prompt     tuned by hand, approved; the one runs use
 *   concept-tags.generated.prompt  the same for the concept pass
 *   concept-tags.reviewed.prompt
 * </pre>
 * A reviewed prompt's header says whether it is approved, for which
 * catalogue and text: {@code topicId}, {@code status} ({@code draft} /
 * {@code approved}), {@code promptVersion}, {@code catalogueFingerprint}
 * (of the pass's tags only), {@code contentHash}, {@code generatedAt},
 * {@code approvedAt}. Files are read afresh on every call, so an edit takes
 * effect without a restart. Writes go through a temp file and a rename.
 * Thread-safe.
 */
@Slf4j
public final class PromptStore {

    /** The concept pass' axis rules (the setup pass has none). */
    public static final String AXES = "qtagger.concept.axes.prompt" ;

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

    private final File     root ;
    private final TagClass tagClass ;

    /** The prompts of pass {@code tagClass} under {@code root}. */
    public PromptStore( File root, TagClass tagClass ) {
        this.root     = root ;
        this.tagClass = tagClass ;
    }

    /** The pass' template file name, e.g. {@code qtagger.setup.prompt}. */
    public static String templateName( TagClass c ) {
        return "qtagger." + c.wire() + ".prompt" ;
    }

    /** A topic's base file name, e.g. {@code setup-tags.generated.prompt}. */
    public static String generatedName( TagClass c ) {
        return c.wire() + "-tags.generated.prompt" ;
    }

    /** A topic's tuned file name, e.g. {@code setup-tags.reviewed.prompt}. */
    public static String reviewedName( TagClass c ) {
        return c.wire() + "-tags.reviewed.prompt" ;
    }

    public TagClass tagClass() {
        return tagClass ;
    }

    /** The pass' template file name. */
    public String templateName() {
        return templateName( tagClass ) ;
    }

    /** The pass' base file name in a topic's folder. */
    public String generatedName() {
        return generatedName( tagClass ) ;
    }

    /** The pass' tuned file name in a topic's folder. */
    public String reviewedName() {
        return reviewedName( tagClass ) ;
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
     * The pass' template.
     *
     * @throws IOException if it is missing or unreadable
     */
    public PromptFile template() throws IOException {
        return read( new File( root, templateName() ) ) ;
    }

    /**
     * The concept template's {@code {{qtagger.concept.axes.prompt}}} text:
     * an {@code ## Own} and a {@code ## Dependent} section; null in the
     * setup pass, whose template states its one rule itself.
     *
     * @throws IOException if it is missing or unreadable
     */
    public PromptFile axes() throws IOException {
        return tagClass == TagClass.CONCEPT ? read( new File( root, AXES ) )
                                            : null ;
    }

    /** The topic's reviewed prompt of the pass; null if it has none. */
    public PromptFile topicPrompt( Topic topic ) throws IOException {
        File f = new File( dir( topic ), reviewedName() ) ;
        return f.isFile() ? read( f ) : null ;
    }

    /** The topic's base prompt of the pass; null if it has none. */
    public PromptFile generated( Topic topic ) throws IOException {
        File f = new File( dir( topic ), generatedName() ) ;
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
            return new File( dir( topic ), generatedName() ).isFile()
                   ? State.BASE_ONLY : State.NONE ;
        }
        return state( p, catalogue, tagClass ) ;
    }

    /**
     * A topic's prompt at a glance, from its files alone (an approved prompt
     * is taken as current: whether its catalogue changed needs the server).
     *
     * @param state       where it stands
     * @param version     its {@code promptVersion}; 0 if it has no
     *                    reviewed prompt
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
        return new Summary( state( reviewed, null, tagClass ),
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

    /**
     * Where topic prompt {@code p} of pass {@code tagClass} stands against
     * {@code catalogue}.
     */
    public static State state( PromptFile p, TagCatalogue catalogue,
                               TagClass tagClass ) {

        if( !APPROVED.equals( p.get( "status" ) )
            || !p.contentHash().equals( p.get( "contentHash" ) ) ) {
            return State.DRAFT ;
        }
        if( catalogue != null && !catalogue.fingerprint( tagClass ).equals(
                p.get( "catalogueFingerprint" ) ) ) {
            return State.STALE ;
        }
        return State.APPROVED ;
    }

    /**
     * Write the topic's base prompt {@code body} to the pass' generated
     * file, and, if the topic has no reviewed prompt of the pass yet, copy
     * it there as a draft.
     *
     * @return true if the reviewed prompt was created
     */
    public synchronized boolean writeGenerated( Topic topic, String body,
                                                TagCatalogue catalogue )
            throws IOException {

        String now = now() ;
        PromptFile generated = new PromptFile( Map.of(), body )
                .with( "topicId", topic.getId() )
                .with( "catalogueFingerprint",
                       catalogue.fingerprint( tagClass ) )
                .with( "generatedAt", now ) ;

        write( new File( dir( topic ), generatedName() ), generated ) ;
        log.info( "Wrote the {} base prompt of topic {} to {}", tagClass.wire(),
                  topic.getId(), dir( topic ) ) ;
        if( topicPrompt( topic ) != null ) return false ;

        PromptFile draft = new PromptFile( Map.of(), body )
                .with( "topicId", topic.getId() )
                .with( "status", DRAFT )
                .with( "promptVersion", 0 )
                .with( "catalogueFingerprint", "" )
                .with( "contentHash", "" )
                .with( "generatedAt", now )
                .with( "approvedAt", "" ) ;
        write( new File( dir( topic ), reviewedName() ), draft ) ;
        return true ;
    }

    /**
     * Approve the topic's reviewed prompt of the pass as it stands, for
     * {@code catalogue}: status approved, the next prompt version, the
     * fingerprint of the catalogue's tags of the pass and the text's hash.
     *
     * @return the approved file
     */
    public synchronized PromptFile approve( Topic topic,
                                            TagCatalogue catalogue )
            throws IOException {

        PromptFile p = topicPrompt( topic ) ;
        if( p == null ) {
            throw new IOException( "topic " + topic.getId() +
                                   " has no " + reviewedName() ) ;
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
                                   " has no " + reviewedName() ) ;
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
                .with( "catalogueFingerprint",
                       catalogue.fingerprint( tagClass ) )
                .with( "contentHash", p.contentHash() )
                .with( "approvedAt", now() ) ;

        write( new File( dir( topic ), reviewedName() ), approved ) ;
        log.info( "Approved the {} prompt of topic {}, version {}",
                  tagClass.wire(), topic.getId(),
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
