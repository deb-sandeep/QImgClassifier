package com.sandy.sconsole.qimgtools.apps.transcriber.gate;

import com.sandy.sconsole.ai.TokenLogprob;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How sure Qwen was of a transcription: its contested positions, places
 * where it nearly wrote a different reading (logprob-confidence.md §4.2 with
 * the rev 3 additions of §4.2a; the replay's {@code gate3}).
 * <p>
 * A token on a {@code diagram} description line or a fence line is not
 * scored. A scored token is contested at margin {@code m} if one of its
 * alternatives (a <i>rival</i>) with {@code p(chosen) - p(rival) < m} is
 * <ul>
 * <li>not a split UTF-8 piece of the chosen token or of the text from it,
 * <li>not the same reading: with the text up to the token and the rival
 * normalised, it isn't a prefix of the normalised reply, nor the reverse,
 * <li>not minor only: the two don't differ only in digits, {@code .},
 * {@code ,} or signs.
 * </ul>
 * C, the score, is the number of contested positions.
 */
public final class ConfidenceScore {

    /** The largest margin scored; a rival further off is never a contest. */
    public static final double MAX_MARGIN = 0.5 ;

    private static final List<String> REMOVED = List.of(
            "operatorname", "displaystyle", "mathrm", "mathbf", "qquad",
            "right", "text", "left", "quad", "rm", "ce" ) ;

    private static final Pattern REMOVED_CMD = Pattern.compile(
            "\\\\(?:" + String.join( "|", REMOVED ) + ")(?![A-Za-z])" +
            "|\\\\[,;!]", PyText.FLAGS ) ;
    private static final Pattern PARTIAL   = Pattern.compile(
            "\\\\([A-Za-z]*)$", PyText.FLAGS ) ;
    private static final Pattern SPACE_ETC = Pattern.compile(
            "[\\s${}^_]", PyText.FLAGS ) ;
    private static final Pattern DOTS      = Pattern.compile(
            "\\\\(?:l|c)?dots(?![A-Za-z])|\\.{3,}|…", PyText.FLAGS ) ;
    private static final Pattern CDOT      = Pattern.compile(
            "\\\\cdot(?![A-Za-z])", PyText.FLAGS ) ;
    private static final Pattern TABLE_SEP = Pattern.compile(
            "^[ \\t]*\\|?[ \\t:]*-{3,}[-| \\t:]*$",
            PyText.FLAGS | Pattern.MULTILINE ) ;
    private static final Pattern LIST_MARK = Pattern.compile(
            "^[ \\t]*-(?:[ \\t]+|$)", PyText.FLAGS | Pattern.MULTILINE ) ;
    private static final Pattern MD_MARKS  = Pattern.compile(
            "[|`*#]", PyText.FLAGS ) ;
    private static final Pattern DASHES    = Pattern.compile(
            "[–—−]", PyText.FLAGS ) ;
    private static final Pattern MINOR_SKIP = Pattern.compile(
            "[\\s${}^_\\\\]", PyText.FLAGS ) ;
    private static final Pattern MINOR_ONLY = Pattern.compile(
            "[0-9.,+\\-−±]*", PyText.FLAGS ) ;

    /**
     * A contested position.
     *
     * @param offset       the token's offset in the reply
     * @param line         its line, from 1
     * @param chosen       the token Qwen wrote
     * @param pChosen      its probability
     * @param rival        the closest contesting alternative
     * @param pRival       its probability
     * @param continuation the reply from the token on, a few characters
     */
    public record Contest( int offset, int line, String chosen,
                           double pChosen, String rival, double pRival,
                           String continuation ) {

        /** {@code pChosen - pRival}. */
        public double diff() { return pChosen - pRival ; }
    }

    /**
     * The score of a reply.
     *
     * @param contests  every position contested at {@link #MAX_MARGIN},
     *                  each with its closest rival
     * @param tokens    the reply's tokens
     * @param geoMeanP  the geometric mean probability of the scored,
     *                  non-blank tokens; null if none
     * @param minP      the lowest of those probabilities; null if none
     */
    public record Result( List<Contest> contests, int tokens,
                          Double geoMeanP, Double minP ) {

        /** C at {@code margin}: the positions contested within it. */
        public int c( double margin ) {
            return (int)contests.stream()
                                .filter( x -> x.diff() < margin ).count() ;
        }

        /** The contests within {@code margin}. */
        public List<Contest> contests( double margin ) {
            return contests.stream().filter( x -> x.diff() < margin )
                           .toList() ;
        }
    }

    private ConfidenceScore() {}

    /** Scores {@code text}, the reply whose tokens are {@code tokens}. */
    public static Result score( String text, List<TokenLogprob> tokens ) {

        ReplyLayout   layout   = new ReplyLayout( text ) ;
        String        normText = normalise( text, false ) ;
        List<Contest> contests = new ArrayList<>() ;
        double        logSum   = 0 ;
        int           scored   = 0 ;
        Double        minP     = null ;
        int           pos      = 0 ;

        for( TokenLogprob t : tokens ) {
            int start = pos ;
            pos += t.token().length() ;
            if( layout.kindAt( start ) == ReplyLayout.Kind.DESC ) continue ;

            double p = t.probability() ;
            if( !PyText.strip( t.token() ).isEmpty() ) {
                logSum += t.logprob() ;
                scored++ ;
                minP = minP == null ? p : Math.min( minP, p ) ;
            }
            if( onFenceLine( text, start ) ) continue ;

            Contest best = contest( text, normText, start, t, p ) ;
            if( best != null ) contests.add( best ) ;
        }
        return new Result( List.copyOf( contests ), tokens.size(),
                           scored == 0 ? null : Math.exp( logSum / scored ),
                           minP ) ;
    }

    /** The closest rival contesting token {@code t} at {@code start}. */
    private static Contest contest( String text, String normText, int start,
                                    TokenLogprob t, double p ) {

        byte[]  chosen = t.bytes() ;
        byte[]  cont   = text.substring( start )
                             .getBytes( StandardCharsets.UTF_8 ) ;
        String  before = text.substring( 0, start ) ;
        Contest best   = null ;

        for( TokenLogprob.Alternative r : t.top() ) {
            if( r.token().equals( t.token() ) ) continue ;

            double pr = r.probability() ;
            if( p - pr >= MAX_MARGIN ) continue ;
            if( incomplete( r.bytes() )
                && ( startsWith( chosen, r.bytes() )
                     || startsWith( cont, r.bytes() ) ) ) {
                continue ;
            }
            String a = normalise( before + r.token(), true ) ;
            if( normText.startsWith( a ) || a.startsWith( normText ) ) {
                continue ;
            }
            if( minorOnly( t.token(), r.token() ) ) continue ;

            if( best == null || p - pr < best.diff() ) {
                best = new Contest(
                        start, PyText.count( text, '\n', start ) + 1,
                        t.token(), p, r.token(), pr,
                        text.substring( start,
                                        Math.min( text.length(),
                                                  start + 24 ) ) ) ;
            }
        }
        return best ;
    }

    /** True if the line holding {@code offset} is a fence line. */
    private static boolean onFenceLine( String text, int offset ) {

        int from = text.lastIndexOf( '\n', offset - 1 ) + 1 ;
        int to   = text.indexOf( '\n', offset ) ;
        String line = text.substring( from, to >= 0 ? to : text.length() ) ;

        return PyText.strip( line ).startsWith( ReplyLayout.FENCE ) ;
    }

    /**
     * {@code s} with notation removed: markdown structure, dot and dash
     * variants, formatting commands, backslashes, whitespace and
     * {@code $ { } ^ _}. With {@code partial}, a trailing partial command
     * that could be the start of a removed one is dropped first.
     */
    static String normalise( String s, boolean partial ) {

        s = DASHES.matcher( s ).replaceAll( "-" ) ;
        s = DOTS.matcher( s ).replaceAll( "..." ) ;
        s = CDOT.matcher( s ).replaceAll( "." ) ;
        s = TABLE_SEP.matcher( s ).replaceAll( "" ) ;
        s = LIST_MARK.matcher( s ).replaceAll( "" ) ;
        s = MD_MARKS.matcher( s ).replaceAll( "" ) ;

        if( partial ) {
            Matcher m = PARTIAL.matcher( s ) ;
            if( m.find() ) {
                String stub = m.group( 1 ) ;
                if( REMOVED.stream().anyMatch( c -> c.startsWith( stub ) ) ) {
                    s = s.substring( 0, m.start() ) ;
                }
            }
        }
        s = REMOVED_CMD.matcher( s ).replaceAll( "" ) ;
        s = s.replace( "\\", "" ) ;
        return SPACE_ETC.matcher( s ).replaceAll( "" ) ;
    }

    /**
     * True if {@code chosen} and {@code rival} differ only in digits,
     * decimal point or sign: a contest that can only be a minor fault.
     */
    static boolean minorOnly( String chosen, String rival ) {

        String a = MINOR_SKIP.matcher( chosen ).replaceAll( "" ) ;
        String b = MINOR_SKIP.matcher( rival ).replaceAll( "" ) ;

        return !( a.isEmpty() && b.isEmpty() )
               && MINOR_ONLY.matcher( a ).matches()
               && MINOR_ONLY.matcher( b ).matches() ;
    }

    /** True if {@code bytes} aren't valid UTF-8 on their own. */
    private static boolean incomplete( byte[] bytes ) {

        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput( CodingErrorAction.REPORT )
                    .onUnmappableCharacter( CodingErrorAction.REPORT )
                    .decode( ByteBuffer.wrap( bytes ) ) ;
            return false ;
        }
        catch( CharacterCodingException e ) {
            return true ;
        }
    }

    private static boolean startsWith( byte[] bytes, byte[] prefix ) {

        if( prefix.length > bytes.length ) return false ;
        for( int i = 0 ; i < prefix.length ; i++ ) {
            if( bytes[i] != prefix[i] ) return false ;
        }
        return true ;
    }
}
