package com.sandy.sconsole.qimgtools.apps.transcriber.gate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mechanical repairs made to a reply before the well-formed checks,
 * for slips that change nothing the model meant:
 * <ul>
 *   <li>a blank line put after a figure that runs straight into the next
 *       line (markdown would take that line into the figure);</li>
 *   <li>a {@code smiles:} line CDK can't parse, rewritten if its
 *       abbreviations account for it: {@code Ph} a benzene ring,
 *       {@code Me} {@code C}, {@code Et} {@code CC}, {@code R} /
 *       {@code Ar} / {@code X} the wildcard {@code *}, a {@code NO2}
 *       group the nitro group, and structures listed with commas joined
 *       by {@code .}. The rewrite is kept only if it parses; otherwise the
 *       line is left as it was, for the checks to refuse.</li>
 * </ul>
 * Figures and fences are read as {@link ReplyLayout} reads them.
 */
public final class ReplyMend {

    /** A group abbreviation, or {@code NO2} before a bracket or the end. */
    private static final Pattern ABBREVIATION = Pattern.compile(
            "Ph|Me|Et|Ar|R|X|NO2(?=\\)|$)" ) ;

    private static final Pattern COMMAS = Pattern.compile( "\\s*,\\s*" ) ;

    private ReplyMend() {}

    /**
     * One change: {@code removed} characters at {@code at} of the reply
     * replaced by {@code added} ones; {@code newlines} the lines added.
     */
    public record Edit( int at, int removed, int added, int newlines ) {}

    /**
     * A reply as mended.
     *
     * @param text     the mended text
     * @param edits    the changes, by their place in the reply as sent
     * @param figures  how many figures had a blank line put after them
     * @param smiles   each SMILES rewritten, as {@code old -> new}
     */
    public record Mended( String text, List<Edit> edits, int figures,
                          List<String> smiles ) {

        /** True if anything was changed. */
        public boolean changed() { return !edits.isEmpty() ; }

        /**
         * Where {@code offset} of the reply as sent is in the mended text;
         * an offset inside a rewritten SMILES goes to its start.
         */
        public int shift( int offset ) {

            int delta = 0 ;
            for( Edit e : edits ) {
                if( offset >= e.at() + e.removed() ) {
                    delta += e.added() - e.removed() ;
                }
                else if( offset >= e.at() ) {
                    return e.at() + delta ;
                }
            }
            return offset + delta ;
        }

        /** The lines added before {@code offset} of the reply as sent. */
        public int linesBefore( int offset ) {

            int n = 0 ;
            for( Edit e : edits ) {
                if( e.at() <= offset ) n += e.newlines() ;
            }
            return n ;
        }

        /** What was mended, for the transcript; null if nothing. */
        public String describe() {

            if( !changed() ) return null ;
            List<String> what = new ArrayList<>() ;

            if( figures > 0 ) {
                what.add( "a blank line put after " + figures +
                          ( figures == 1 ? " figure" : " figures" ) ) ;
            }
            smiles.forEach( s -> what.add( "SMILES " + s ) ) ;
            return String.join( "; ", what ) ;
        }
    }

    /** {@code text} mended; {@link Mended#changed} false if it needs none. */
    public static Mended mend( String text ) {

        StringBuilder sb      = new StringBuilder( text.length() + 16 ) ;
        List<Edit>    edits   = new ArrayList<>() ;
        List<String>  smiles  = new ArrayList<>() ;
        boolean       inFence = false ;
        boolean       inQuote = false ;
        int           figures = 0 ;
        int           pos     = 0 ;

        for( String line : text.split( "\n", -1 ) ) {
            String  stripped = PyText.strip( line ) ;
            boolean quote    = !inFence
                               && stripped.startsWith( ReplyLayout.QUOTE ) ;

            if( pos > 0 ) sb.append( '\n' ) ;
            if( inQuote && !quote && !stripped.isEmpty() ) {
                sb.append( '\n' ) ;
                edits.add( new Edit( pos, 0, 1, 1 ) ) ;
                figures++ ;
            }
            inQuote = quote ;
            if( !quote && stripped.startsWith( ReplyLayout.FENCE ) ) {
                inFence = !inFence ;
            }
            sb.append( mendSmilesLine( line, pos, edits, smiles ) ) ;
            pos += line.length() + 1 ;
        }
        return new Mended( sb.toString(), List.copyOf( edits ), figures,
                           List.copyOf( smiles ) ) ;
    }

    /**
     * {@code line}, at {@code pos} of the reply, with its SMILES rewritten
     * if it is a {@code smiles:} line that doesn't parse and its rewrite
     * does; the change noted in {@code edits} and {@code smiles}.
     */
    private static String mendSmilesLine( String line, int pos,
                                          List<Edit> edits,
                                          List<String> smiles ) {

        String unquoted = ReplyLayout.unquote( PyText.strip( line ) ) ;
        if( !ReplyLayout.isSmiles( unquoted ) ) return line ;

        int    colon = line.indexOf( ':' ) ;
        String value = PyText.strip( line.substring( colon + 1 ) ) ;
        if( value.isEmpty() || WellFormed.smilesParses( value ) ) return line ;

        String fixed = rewrite( value ) ;
        if( fixed.equals( value ) || !WellFormed.smilesParses( fixed ) ) {
            return line ;
        }
        int start = line.indexOf( value, colon + 1 ) ;

        edits.add( new Edit( pos + start, value.length(), fixed.length(),
                             0 ) ) ;
        smiles.add( value + " -> " + fixed ) ;
        return line.substring( 0, start ) + fixed +
               line.substring( start + value.length() ) ;
    }

    /**
     * {@code smiles} with its abbreviations written out, outside bracket
     * atoms, and a comma-separated list joined by {@code .}.
     */
    static String rewrite( String smiles ) {

        String        ring = freeRingNumber( smiles ) ;
        String        list = COMMAS.matcher( smiles ).replaceAll( "." ) ;
        StringBuilder out  = new StringBuilder() ;
        int           i    = 0 ;

        while( i < list.length() ) {
            char c = list.charAt( i ) ;
            if( c == '[' ) {
                int end = list.indexOf( ']', i ) ;
                end = end < 0 ? list.length() : end + 1 ;
                out.append( list, i, end ) ;
                i = end ;
                continue ;
            }
            Matcher m = ABBREVIATION.matcher( list ).region( i, list.length() ) ;
            if( m.lookingAt() ) {
                out.append( expand( m.group(), ring ) ) ;
                i = m.end() ;
                continue ;
            }
            out.append( c ) ;
            i++ ;
        }
        return out.toString() ;
    }

    private static String expand( String abbreviation, String ring ) {
        return switch( abbreviation ) {
            case "Ph"  -> "c" + ring + "ccccc" + ring ;
            case "Me"  -> "C" ;
            case "Et"  -> "CC" ;
            case "NO2" -> "[N+](=O)[O-]" ;
            default    -> "*" ;
        } ;
    }

    /**
     * A ring-bond number {@code smiles} doesn't use, for a phenyl ring
     * (opened and closed at once, so several can share it): the smallest
     * free digit, else {@code %91}.
     */
    private static String freeRingNumber( String smiles ) {

        for( char d = '1' ; d <= '9' ; d++ ) {
            if( smiles.indexOf( d ) < 0 ) return String.valueOf( d ) ;
        }
        return "%91" ;
    }
}
