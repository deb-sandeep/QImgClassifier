package com.sandy.sconsole.qimgtools.apps.transcriber.workspace;

import com.sandy.sconsole.qimgtools.apps.transcriber.model.SaveItem;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.SaveResult;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.Transcription;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionRoute;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;
import com.sandy.sconsole.qimgtools.core.workspace.JsonFiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The transcriber's audit trail, one JSON object per line (JSON Lines),
 * only ever appended to. Every line has {@code time} and {@code event}:
 * <ul>
 * <li>{@code transcribed}: a transcription accepted (question, passage or
 * question part), with its model, route and score; read back for the
 * Check tab's choice between a question's transcriptions;
 * <li>{@code saved}: the server's answer to a store, with what was sent,
 * its score and the server question (images included), read back by the
 * saved-questions spot check;
 * <li>{@code mark}: the user's spot-check mark ({@code checked},
 * {@code faulty}, {@code faultyPassage});
 * <li>{@code edited}: a hand edit, the text before and after.
 * </ul>
 * Thread-safe.
 */
@Slf4j
public final class TranscriptionAuditLog {

    /** One line per entry. */
    private static final ObjectWriter WRITER =
            JsonFiles.MAPPER.writer()
                            .without( SerializationFeature.INDENT_OUTPUT ) ;

    /**
     * A transcription the server took, as the saved-questions spot check
     * reads it back.
     *
     * @param at       when it was saved
     * @param question the server question, as loaded then
     * @param route    how the transcription came about
     * @param c        Qwen's C; null without a score
     * @param minP     its lowest token probability; null without a score
     */
    public record Saved( Instant at, ServerQuestion question,
                         TranscriptionRoute route, Integer c, Double minP ) {}

    /**
     * A transcription a model gave a question (or an LCT question's part),
     * as the {@code transcribed} line keeps it: without its contested
     * positions.
     *
     * @param at            when it was accepted
     * @param transcription the transcription
     * @param route         how it came about
     */
    public record Given( Instant at, Transcription transcription,
                         TranscriptionRoute route ) {}

    private final File file ;

    public TranscriptionAuditLog( File file ) {
        this.file = file ;
    }

    /**
     * A transcription accepted for {@code q} ({@code what} is
     * {@code question}, {@code part} or {@code passage}).
     */
    public void transcribed( ServerQuestion q, String lctCtxId, String what,
                             Transcription t, TranscriptionRoute route,
                             boolean provisional ) throws IOException {

        ObjectNode line = line( "transcribed", q ) ;

        line.put( "lctCtxId", lctCtxId ) ;
        line.put( "what", what ) ;
        line.put( "provisional", provisional ) ;
        score( line, t, route ) ;
        append( line ) ;
    }

    /** The server's answer {@code r} to {@code item}, made of {@code t}. */
    public void saved( ServerQuestion q, SaveItem item, Transcription t,
                       TranscriptionRoute route, SaveResult r )
            throws IOException {

        ObjectNode line = line( "saved", q ) ;

        line.set( "question", JsonFiles.MAPPER.valueToTree( q ) ) ;
        line.put( "aiModel", item.aiModel() ) ;
        line.put( "withPassage", item.hasPassage() ) ;
        line.put( "status", r.status().name() ) ;
        line.put( "message", r.message() ) ;
        score( line, t, route ) ;
        append( line ) ;
    }

    /** The user marked {@code q}'s transcription {@code mark}. */
    public void mark( ServerQuestion q, String mark, TranscriptionRoute route )
            throws IOException {

        ObjectNode line = line( "mark", q ) ;
        line.put( "mark", mark ) ;
        line.set( "route", TranscriptionJson.route( route ) ) ;
        append( line ) ;
    }

    /** The user edited {@code what} of {@code q} from {@code before}. */
    public void edited( ServerQuestion q, String lctCtxId, String what,
                        String before, String after ) throws IOException {

        ObjectNode line = line( "edited", q ) ;
        line.put( "lctCtxId", lctCtxId ) ;
        line.put( "what", what ) ;
        line.put( "before", before ) ;
        line.put( "after", after ) ;
        append( line ) ;
    }

    /**
     * The transcriptions the server took at or after {@code since}, the
     * latest of each question, oldest first; a line that can't be read, or
     * written before lines kept the question, is skipped.
     */
    public List<Saved> savedSince( Instant since ) throws IOException {

        Map<Integer,Saved> latest = new LinkedHashMap<>() ;
        if( !file.exists() ) return List.of() ;

        try( BufferedReader in = Files.newBufferedReader(
                file.toPath(), StandardCharsets.UTF_8 ) ) {
            String text ;
            while( ( text = in.readLine() ) != null ) {
                JsonNode n = read( text ) ;
                if( n == null || !"saved".equals( n.path( "event" ).asText() )
                    || !"APPLIED".equals( n.path( "status" ).asText() )
                    || !n.path( "question" ).isObject() ) {
                    continue ;
                }
                Instant at = instant( n.path( "time" ).asText() ) ;
                Saved   s  = at == null || at.isBefore( since ) ? null
                                                                : saved( n, at ) ;
                if( s == null ) continue ;

                latest.remove( s.question().getId() ) ;
                latest.put( s.question().getId(), s ) ;
            }
        }
        return List.copyOf( latest.values() ) ;
    }

    /**
     * The transcriptions given the questions with server ids {@code ids}
     * (not their passages), oldest first, by server id; a question with
     * none is left out, and a line that can't be read is skipped.
     */
    public Map<Integer,List<Given>> given( Set<Integer> ids )
            throws IOException {

        Map<Integer,List<Given>> given = new HashMap<>() ;
        if( ids.isEmpty() || !file.exists() ) return given ;

        try( BufferedReader in = Files.newBufferedReader(
                file.toPath(), StandardCharsets.UTF_8 ) ) {
            String text ;
            while( ( text = in.readLine() ) != null ) {
                if( !text.contains( "\"transcribed\"" ) ) continue ;
                JsonNode n = read( text ) ;
                if( n == null
                    || !"transcribed".equals( n.path( "event" ).asText() )
                    || "passage".equals( n.path( "what" ).asText() )
                    || !ids.contains( n.path( "serverId" ).asInt( -1 ) )
                    || !n.path( "content" ).isTextual() ) {
                    continue ;
                }
                Given g = given( n ) ;
                if( g == null ) continue ;
                given.computeIfAbsent( n.path( "serverId" ).asInt(),
                                       k -> new ArrayList<>() ).add( g ) ;
            }
        }
        return given ;
    }

    /** The {@code transcribed} line {@code n}; null if it can't be read. */
    private static Given given( JsonNode n ) {

        Instant at = instant( n.path( "time" ).asText() ) ;
        if( at == null ) return null ;
        try {
            Transcription t = new Transcription(
                    n.path( "content" ).asText(),
                    n.path( "model" ).isTextual()
                    ? n.path( "model" ).asText() : null,
                    n.path( "c" ).isNumber() ? n.path( "c" ).asInt() : null,
                    n.path( "geoMeanP" ).isNumber()
                    ? n.path( "geoMeanP" ).asDouble() : null,
                    n.path( "minP" ).isNumber()
                    ? n.path( "minP" ).asDouble() : null,
                    List.of() ) ;
            return new Given( at, t, TranscriptionJson.route( n ) ) ;
        }
        catch( RuntimeException e ) {
            log.warn( "Skipping an unreadable transcribed line: {}",
                      e.getMessage() ) ;
            return null ;
        }
    }

    /** The {@code saved} line {@code n}; null if it can't be read. */
    private static Saved saved( JsonNode n, Instant at ) {

        try {
            ServerQuestion q = JsonFiles.MAPPER.treeToValue(
                    n.path( "question" ), ServerQuestion.class ) ;

            return new Saved( at, q, TranscriptionJson.route( n ),
                              n.path( "c" ).isNumber()
                              ? n.path( "c" ).asInt() : null,
                              n.path( "minP" ).isNumber()
                              ? n.path( "minP" ).asDouble() : null ) ;
        }
        catch( IOException | RuntimeException e ) {
            log.warn( "Skipping an unreadable saved line: {}", e.getMessage() ) ;
            return null ;
        }
    }

    private static JsonNode read( String text ) {
        try {
            return text.isBlank() ? null : JsonFiles.MAPPER.readTree( text ) ;
        }
        catch( IOException e ) {
            log.warn( "Skipping an unreadable audit line: {}", text ) ;
            return null ;
        }
    }

    private static Instant instant( String s ) {
        try {
            return Instant.parse( s ) ;
        }
        catch( RuntimeException e ) {
            return null ;
        }
    }

    private static ObjectNode line( String event, ServerQuestion q ) {

        ObjectNode line = JsonFiles.MAPPER.createObjectNode() ;

        line.put( "time", Instant.now().toString() ) ;
        line.put( "event", event ) ;
        line.put( "serverId", q.getId() ) ;
        line.put( "questionId", q.getQuestionId() ) ;
        line.put( "sourceId", q.getSourceId() ) ;
        line.put( "problemType", q.getProblemType() ) ;
        return line ;
    }

    private static void score( ObjectNode line, Transcription t,
                               TranscriptionRoute route ) {

        line.put( "model", t.model() ) ;
        line.set( "route", TranscriptionJson.route( route ) ) ;
        line.put( "c", t.c() ) ;
        line.put( "geoMeanP", t.geoMeanP() ) ;
        line.put( "minP", t.minP() ) ;
        line.put( "content", t.content() ) ;
    }

    private synchronized void append( ObjectNode line ) throws IOException {

        File dir = file.getParentFile() ;
        if( dir != null && !dir.isDirectory() && !dir.mkdirs() ) {
            throw new IOException( "Could not create " + dir ) ;
        }
        Files.writeString( file.toPath(),
                           WRITER.writeValueAsString( line ) + "\n",
                           StandardCharsets.UTF_8,
                           StandardOpenOption.CREATE,
                           StandardOpenOption.APPEND ) ;
    }
}
