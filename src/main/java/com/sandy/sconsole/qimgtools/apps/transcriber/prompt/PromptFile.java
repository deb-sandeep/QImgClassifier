package com.sandy.sconsole.qimgtools.apps.transcriber.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The transcriber's prompt file ({@code qtranscriber.prompt}): sections
 * headed {@code === <name> ===}, lines starting with {@code #} left out,
 * each section's body trimmed. Filled in as the calibration's
 * {@code run.py} fills it, so the prompt the gate was calibrated on is the
 * one sent. Immutable.
 */
public final class PromptFile {

    private static final Pattern HEADING = Pattern.compile( "^=== (.+) ===$" ) ;

    private final Map<String,String> sections ;
    private final String             sha256 ;

    private PromptFile( Map<String,String> sections, String sha256 ) {
        this.sections = sections ;
        this.sha256   = sha256 ;
    }

    /**
     * Read the classpath resource {@code resource}.
     *
     * @throws IllegalStateException if it can't be read, or lacks the
     *         {@code PROMPT} section or a {@code TASK} one
     */
    public static PromptFile load( String resource ) {

        byte[] raw ;
        try( InputStream in = PromptFile.class.getResourceAsStream(
                resource ) ) {
            if( in == null ) {
                throw new IllegalStateException( "No prompt file " + resource ) ;
            }
            raw = in.readAllBytes() ;
        }
        catch( IOException e ) {
            throw new IllegalStateException( "Could not read the prompt file " +
                                             resource, e ) ;
        }

        PromptFile file = parse( new String( raw, StandardCharsets.UTF_8 ),
                                 sha256( raw ) ) ;
        for( String name : List.of( "PROMPT", "TASK QUESTION",
                                    "TASK LCT_PASSAGE",
                                    "TASK LCT_QUESTION" ) ) {
            if( !file.sections.containsKey( name ) ) {
                throw new IllegalStateException( "The prompt file " +
                        resource + " has no section " + name ) ;
            }
        }
        return file ;
    }

    private static PromptFile parse( String text, String sha256 ) {

        Map<String,List<String>> lines   = new LinkedHashMap<>() ;
        List<String>             current = null ;

        for( String line : text.split( "\\R", -1 ) ) {
            if( line.startsWith( "#" ) ) continue ;
            Matcher m = HEADING.matcher( line ) ;
            if( m.matches() ) {
                current = new ArrayList<>() ;
                lines.put( m.group( 1 ), current ) ;
            }
            else if( current != null ) {
                current.add( line ) ;
            }
        }

        Map<String,String> sections = new LinkedHashMap<>() ;
        lines.forEach( ( k, v ) -> sections.put(
                k, String.join( "\n", v ).strip() ) ) ;
        return new PromptFile( Map.copyOf( sections ), sha256 ) ;
    }

    /** The SHA-256 of the file's bytes, in hex. */
    public String sha256() {
        return sha256 ;
    }

    /** True if the file has a {@code TYPE <type>} section. */
    public boolean knowsType( String type ) {
        return sections.containsKey( "TYPE " + type ) ;
    }

    /**
     * The prompt for a question of {@code subject} (Physics, Chemistry or
     * Mathematics) and {@code type} (e.g. {@code SCA}), for the task in
     * section {@code taskSection}, with {@code passage} as the passage
     * (null for none).
     *
     * @throws IllegalArgumentException if there is no section for the type
     */
    public String fill( String subject, String type, String taskSection,
                        String passage ) {

        String note = sections.get( "TYPE " + type ) ;
        if( note == null ) {
            throw new IllegalArgumentException( "The prompt has no section " +
                                                "for question type " + type ) ;
        }

        int    dot  = note.indexOf( '.' ) ;
        String name = dot < 0 ? note : note.substring( 0, dot ) ;
        String task = sections.get( taskSection )
                              .replace( "{{PASSAGE}}",
                                        passage == null ? "" : passage ) ;

        return sections.get( "PROMPT" )
                       .replace( "{{SUBJECT}}", subject )
                       .replace( "{{TYPE}}", name )
                       .replace( "{{SUBJECT_NOTE}}",
                                 sections.getOrDefault( "SUBJECT " + subject,
                                                        "" ) )
                       .replace( "{{TYPE_NOTE}}", note )
                       .replace( "{{TASK}}", task ) ;
    }

    private static String sha256( byte[] bytes ) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance( "SHA-256" ).digest( bytes ) ) ;
        }
        catch( NoSuchAlgorithmException e ) {
            throw new IllegalStateException( e ) ;
        }
    }
}
