package com.sandy.sconsole.qimgtools.apps.transcriber.gate;

import com.sandy.sconsole.ai.TokenLogprob;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the gate against the calibration's golden cases
 * ({@code /apps/transcriber/gate-golden.json}, written by
 * {@code calibration/replay.py golden}): for each stored Qwen reply, the
 * well-formed problems, C at every margin and the contested positions must
 * be the replay's. Run once at startup, as the NXR classifier checks its
 * golden cases, so a gate that has drifted from its calibration refuses to
 * run.
 */
public final class GoldenCases {

    /** The classpath resource holding the cases. */
    public static final String RESOURCE = "/apps/transcriber/gate-golden.json" ;

    private GoldenCases() {}

    /**
     * Checks every case.
     *
     * @return the number of cases checked
     * @throws IllegalStateException naming the first case that differs, and
     *         how
     * @throws IOException if the cases can't be read
     */
    public static int check() throws IOException {

        JsonNode root ;
        try( InputStream in = GoldenCases.class.getResourceAsStream(
                RESOURCE ) ) {
            if( in == null ) throw new IOException( RESOURCE + " not found" ) ;
            root = new ObjectMapper().readTree( in ) ;
        }

        int n = 0 ;
        for( JsonNode c : root.path( "cases" ) ) {
            checkCase( c, root.path( "margins" ) ) ;
            n++ ;
        }
        if( n == 0 ) throw new IOException( RESOURCE + " has no cases" ) ;
        return n ;
    }

    private static void checkCase( JsonNode c, JsonNode margins ) {

        String key  = c.path( "key" ).asText() ;
        String text = c.path( "text" ).asText() ;
        String done = c.path( "doneReason" ).isTextual()
                      ? c.path( "doneReason" ).asText() : null ;

        List<String> wf       = WellFormed.problems( text, done ) ;
        List<String> expected = new ArrayList<>() ;
        c.path( "wellFormed" ).forEach( p -> expected.add( p.asText() ) ) ;
        if( !wf.equals( expected ) ) {
            throw mismatch( key, "well-formed problems " + wf +
                                 ", expected " + expected ) ;
        }

        ConfidenceScore.Result score =
                ConfidenceScore.score( text, tokens( c.path( "tokens" ) ) ) ;
        for( JsonNode m : margins ) {
            double margin = m.asDouble() ;
            int    want   = c.path( "C3" ).path( m.asText() ).asInt( -1 ) ;
            int    got    = score.c( margin ) ;
            if( got != want ) {
                throw mismatch( key, "C at margin " + margin + " is " + got +
                                     ", expected " + want ) ;
            }
        }

        List<String> got = new ArrayList<>() ;
        score.contests().forEach( x -> got.add(
                x.line() + ":" + x.chosen() + "|" + x.rival() ) ) ;
        List<String> want = new ArrayList<>() ;
        c.path( "contested3" ).forEach( x -> want.add(
                x.path( "line" ).asInt() + ":" + x.path( "chosen" ).asText() +
                "|" + x.path( "rival" ).asText() ) ) ;
        if( !got.equals( want ) ) {
            throw mismatch( key, "contested " + got + ", expected " + want ) ;
        }
    }

    private static List<TokenLogprob> tokens( JsonNode tokens ) {

        List<TokenLogprob> list = new ArrayList<>() ;
        for( JsonNode t : tokens ) {
            List<TokenLogprob.Alternative> top = new ArrayList<>() ;
            for( JsonNode r : t.path( "top" ) ) {
                top.add( new TokenLogprob.Alternative(
                        r.path( "t" ).asText(), r.path( "lp" ).asDouble(),
                        bytes( r ) ) ) ;
            }
            list.add( new TokenLogprob( t.path( "t" ).asText(),
                                        t.path( "lp" ).asDouble(),
                                        bytes( t ), top ) ) ;
        }
        return list ;
    }

    private static byte[] bytes( JsonNode token ) {

        JsonNode b = token.path( "b" ) ;
        if( !b.isArray() ) {
            return token.path( "t" ).asText()
                        .getBytes( StandardCharsets.UTF_8 ) ;
        }
        byte[] bytes = new byte[b.size()] ;
        for( int i = 0 ; i < bytes.length ; i++ ) {
            bytes[i] = (byte)b.get( i ).asInt() ;
        }
        return bytes ;
    }

    private static IllegalStateException mismatch( String key, String how ) {
        return new IllegalStateException( "The transcription gate doesn't " +
                "match its calibration (golden case " + key + "): " + how ) ;
    }
}
