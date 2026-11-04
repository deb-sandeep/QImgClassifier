package com.sandy.sconsole.qimgtools.apps.tagger.prompt;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One topic's {@code llm-prompt.md} (see the tag workspace's
 * {@code file-formats/llm-prompt.md}), read and checked: the text sent to the
 * LLM, which is what lies below the note's {@code ---} line, and the tag names
 * in its table, the only ones the LLM may return. Immutable.
 */
public final class TopicPrompt {

    public static final String QUESTION_ID = "{{question_id}}" ;
    public static final String OCR_TEXT    = "{{ocr_text}}" ;
    public static final String ACCEPTED    = "{{accepted_tags}}" ;
    public static final String UNDECIDED   = "{{undecided_tags}}" ;
    public static final String TRIGGERED   = "{{triggered_tags}}" ;

    private static final String FILE = "llm-prompt.md" ;

    private static final List<String> PLACEHOLDERS =
            List.of( QUESTION_ID, OCR_TEXT, ACCEPTED, UNDECIDED, TRIGGERED ) ;

    /** The line between the note for the driver and the prompt. */
    private static final Pattern NOTE_END =
            Pattern.compile( "^---[ \\t]*\\r?$", Pattern.MULTILINE ) ;

    /** The heading of the section that holds the table. */
    private static final Pattern TAGS_HEADING =
            Pattern.compile( "^## Tags[ \\t]*\\r?$", Pattern.MULTILINE ) ;

    private static final Pattern NEXT_HEADING =
            Pattern.compile( "^## ", Pattern.MULTILINE ) ;

    /** A row of the table: the name is its first cell, in backticks. */
    private static final Pattern TAG_ROW =
            Pattern.compile( "^\\| `([^`]+)` \\|", Pattern.MULTILINE ) ;

    private static final Pattern ANY_PLACEHOLDER =
            Pattern.compile( "\\{\\{[^}]*}}" ) ;

    private final String      text ;
    private final Set<String> tagNames ;

    private TopicPrompt( String text, Set<String> tagNames ) {
        this.text     = text ;
        this.tagNames = tagNames ;
    }

    /**
     * The prompt in {@code bytes}.
     *
     * @throws TopicPromptException if it fails a check
     */
    public static TopicPrompt parse( byte[] bytes )
            throws TopicPromptException {

        String  all = new String( bytes, StandardCharsets.UTF_8 ) ;
        Matcher end = NOTE_END.matcher( all ) ;

        if( !end.find() ) throw problem( "it has no --- line" ) ;

        String text = all.substring( end.end() ).strip() ;
        checkPlaceholders( text ) ;
        return new TopicPrompt( text, tagNames( text ) ) ;
    }

    /** The text to send, with its placeholders still in it. */
    public String text() {
        return text ;
    }

    /** The tags of its table, in order. */
    public Set<String> tagNames() {
        return tagNames ;
    }

    private static void checkPlaceholders( String text )
            throws TopicPromptException {

        for( String p : PLACEHOLDERS ) {
            int count = text.split( Pattern.quote( p ), -1 ).length - 1 ;
            if( count != 1 ) {
                throw problem( p + " occurs " + count + " times, not once" ) ;
            }
        }
        Matcher m = ANY_PLACEHOLDER.matcher( text ) ;
        while( m.find() ) {
            if( !PLACEHOLDERS.contains( m.group() ) ) {
                throw problem( "unknown placeholder " + m.group() ) ;
            }
        }
    }

    private static Set<String> tagNames( String text )
            throws TopicPromptException {

        Matcher heading = TAGS_HEADING.matcher( text ) ;
        if( !heading.find() ) throw problem( "it has no Tags section" ) ;

        Matcher next = NEXT_HEADING.matcher( text ) ;
        int     to   = next.find( heading.end() ) ? next.start()
                                                  : text.length() ;

        Set<String> names = new LinkedHashSet<>() ;
        Matcher     row   = TAG_ROW.matcher( text.substring( heading.end(), to ) ) ;

        while( row.find() ) names.add( row.group( 1 ) ) ;
        if( names.isEmpty() ) throw problem( "its Tags table has no tags" ) ;

        return Collections.unmodifiableSet( names ) ;
    }

    private static TopicPromptException problem( String what ) {
        return new TopicPromptException( FILE + ": " + what ) ;
    }

    /** Why a prompt can't be used; the message is written for the user. */
    @SuppressWarnings( "serial" )
    public static final class TopicPromptException extends Exception {

        TopicPromptException( String message ) {
            super( message ) ;
        }
    }
}
