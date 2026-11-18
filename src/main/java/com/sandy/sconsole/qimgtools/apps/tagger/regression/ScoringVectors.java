package com.sandy.sconsole.qimgtools.apps.tagger.regression;

import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A build's {@code scoring-test-vectors}: questions with the reference
 * scorer's output on their transcription, published with the model to check
 * a port of it. They are in-sample, so they check this port, not accuracy.
 * {@link #check} scores each question whose transcription is still the one
 * the build used (same {@code textSha256}) and refuses the model on any
 * difference, as a port that scores otherwise would tag otherwise.
 */
@Slf4j
public final class ScoringVectors {

    /** How far a score may be from the reference's, which is rounded to 6. */
    private static final double TOLERANCE = 1.5e-6 ;

    /** One question and what the reference made of it. */
    private record Vector( int questionId, String question, String textSha256,
                           int terms, Map<String,Double> scores,
                           List<String> accepted, List<String> undecided,
                           List<String> triggered, String route ) {}

    /** Reads the transcriptions of the vectors' questions, by question id. */
    @FunctionalInterface
    public interface TextSource {
        Map<Integer,String> read( List<Integer> questionIds ) throws Exception ;
    }

    /**
     * What a check found.
     *
     * @param checked the vectors scored and matched
     * @param skipped the vectors whose question has no transcription now, or
     *                another one than the build used
     */
    public record Result( int checked, int skipped ) {}

    private ScoringVectors() {}

    /**
     * Check {@code scorer} against {@code build}'s vectors.
     *
     * @return what was checked; {@code null} if the build has no vectors
     * @throws TopicFileException if the file can't be read, a transcription
     *         can't be fetched, or a question is scored otherwise than the
     *         reference scored it
     */
    public static Result check( TopicFileFinder.Build build,
                                RegressionScorer scorer, TextSource texts )
            throws TopicFileException {

        File file = build.vectors() ;
        if( !file.isFile() ) {
            log.warn( "{}: no {}; the scorer is not checked", build,
                      file.getName() ) ;
            return null ;
        }

        List<Vector>        vectors = read( file ) ;
        Map<Integer,String> stored ;
        try {
            stored = texts.read( vectors.stream().map( Vector::questionId )
                                        .toList() ) ;
        }
        catch( Exception e ) {
            throw new TopicFileException( "Could not read the transcriptions " +
                    "of " + file.getName() + ": " + e.getMessage(), e ) ;
        }

        int checked = 0 ;
        int skipped = 0 ;

        for( Vector v : vectors ) {
            String text = stored.get( v.questionId() ) ;
            if( text == null || !v.textSha256().equals( TopicFiles.sha256(
                    text.getBytes( StandardCharsets.UTF_8 ) ) ) ) {
                log.warn( "{}: skipping {} ({}): its transcription {}", build,
                          v.questionId(), v.question(),
                          text == null ? "is missing" : "has changed" ) ;
                skipped++ ;
                continue ;
            }
            String diff = compare( v, scorer, text ) ;
            if( diff != null ) {
                throw new TopicFileException( file.getName() + ": question " +
                        v.questionId() + " (" + v.question() + ") is scored " +
                        "otherwise than the reference scored it: " + diff ) ;
            }
            checked++ ;
        }
        log.info( "{}: the scorer matches {} of {} test vectors ({} skipped)",
                  build, checked, vectors.size(), skipped ) ;
        return new Result( checked, skipped ) ;
    }

    /** How the port's result differs from {@code v}'s; null if it doesn't. */
    private static String compare( Vector v, RegressionScorer scorer,
                                   String text ) {

        int terms = scorer.termCount( text ) ;
        if( terms != v.terms() ) {
            return terms + " terms, not " + v.terms() ;
        }

        Decision d = scorer.decide( text ) ;
        if( !d.scores().keySet().equals( v.scores().keySet() ) ) {
            return "scores tags " + d.scores().keySet() + ", not " +
                   v.scores().keySet() ;
        }
        for( Map.Entry<String,Double> e : v.scores().entrySet() ) {
            double got = d.scores().get( e.getKey() ) ;
            if( Math.abs( got - e.getValue() ) > TOLERANCE ) {
                return e.getKey() + " scores " + got + ", not " + e.getValue() ;
            }
        }

        String lists = differs( "accepted", d.accepted(), v.accepted() ) ;
        if( lists == null ) {
            lists = differs( "undecided", d.undecided(), v.undecided() ) ;
        }
        if( lists == null ) {
            lists = differs( "triggered", d.triggered(), v.triggered() ) ;
        }
        if( lists != null ) return lists ;

        String route = d.route().name().toLowerCase( Locale.ROOT ) ;
        return route.equals( v.route() ) ? null
               : "routed " + route + ", not " + v.route() ;
    }

    private static String differs( String what, List<String> got,
                                   List<String> want ) {
        return got.equals( want ) ? null
               : what + " " + got + ", not " + want ;
    }

    private static List<Vector> read( File file ) throws TopicFileException {

        JsonNode root ;
        try {
            root = JsonFiles.MAPPER.readTree( file ) ;
        }
        catch( IOException e ) {
            throw new TopicFileException( file.getName() + " can't be read: " +
                                          e.getMessage(), e ) ;
        }

        List<Vector> vectors = new ArrayList<>() ;
        for( JsonNode n : root.path( "vectors" ) ) {
            if( !n.path( "questionId" ).isIntegralNumber() ) {
                log.warn( "{}: a vector without a question id: {}",
                          file.getName(), n.path( "question" ).asText() ) ;
                continue ;
            }
            vectors.add( vector( n ) ) ;
        }
        if( vectors.isEmpty() ) {
            throw new TopicFileException( file.getName() + " has no vectors" ) ;
        }
        return vectors ;
    }

    private static Vector vector( JsonNode n ) {

        Map<String,Double> scores = new LinkedHashMap<>() ;
        n.path( "scores" ).fields().forEachRemaining(
                e -> scores.put( e.getKey(), e.getValue().asDouble() ) ) ;

        return new Vector( n.get( "questionId" ).asInt(),
                           n.path( "question" ).asText(),
                           n.path( "textSha256" ).asText(),
                           n.path( "terms" ).asInt( -1 ), scores,
                           names( n, "accepted" ), names( n, "undecided" ),
                           names( n, "triggered" ),
                           n.path( "route" ).asText() ) ;
    }

    private static List<String> names( JsonNode n, String key ) {

        List<String> names = new ArrayList<>() ;
        n.path( key ).forEach( t -> names.add( t.asText() ) ) ;
        return names ;
    }
}
