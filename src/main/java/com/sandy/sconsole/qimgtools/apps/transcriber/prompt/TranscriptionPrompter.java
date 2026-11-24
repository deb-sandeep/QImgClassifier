package com.sandy.sconsole.qimgtools.apps.transcriber.prompt;

import com.sandy.sconsole.ai.AIReply;
import com.sandy.sconsole.ai.AIRequest;
import com.sandy.sconsole.ai.TokenLogprob;
import com.sandy.sconsole.qimgtools.apps.transcriber.gate.ConfidenceScore;
import com.sandy.sconsole.qimgtools.apps.transcriber.gate.ReplyMend;
import com.sandy.sconsole.qimgtools.apps.transcriber.gate.WellFormed;
import com.sandy.sconsole.qimgtools.apps.transcriber.model.Transcription;
import com.sandy.sconsole.qimgtools.apps.transcriber.pipeline.TranscriptionJob;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.model.Subject;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskPrompter;
import com.sandy.sconsole.qimgtools.core.util.ImageCollator;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * What a model is asked to transcribe, and the gate on its reply
 * (feature-design.md §6): the prompt from the {@link PromptFile} for the
 * job's subject, type and task; the job's images stacked into one, as
 * stored; and the reply read through the well-formed checks (any model) and,
 * for a reply with token probabilities, the confidence score. A malformed
 * reply is a {@link BadReply}, which sends Qwen's job to Nemotron by its
 * fallback guards. Each reply's gate result is handed to {@code notes}, for
 * the attempt's transcript. Thread-safe.
 */
@Slf4j
public final class TranscriptionPrompter
        implements TaskPrompter<Transcription> {

    private final PromptFile                       file ;
    private final Function<QTask,TranscriptionJob> jobs ;
    private final double                           margin ;
    private final BiConsumer<QTask,String>         notes ;

    /** The last passage reply the gate refused, by LCT group. */
    private final Map<String,String> rejectedPassages =
            new ConcurrentHashMap<>() ;

    /**
     * The last question reply the gate refused (an LCT question's part
     * only), by the question's server id.
     */
    private final Map<Integer,String> rejectedQuestions =
            new ConcurrentHashMap<>() ;

    /**
     * @param file   the prompt file
     * @param jobs   the job each task sent is
     * @param margin the gate's margin: C counts the positions contested
     *               within it
     * @param notes  takes each reply's gate result, as a transcript section
     */
    public TranscriptionPrompter( PromptFile file,
                                  Function<QTask,TranscriptionJob> jobs,
                                  double margin,
                                  BiConsumer<QTask,String> notes ) {
        this.file   = file ;
        this.jobs   = jobs ;
        this.margin = margin ;
        this.notes  = notes ;
    }

    /**
     * The last reply the gate refused for LCT group {@code lctCtxId}'s
     * passage, this session; null if none.
     */
    public String rejectedPassage( String lctCtxId ) {
        return lctCtxId == null ? null : rejectedPassages.get( lctCtxId ) ;
    }

    /**
     * The last reply the gate refused for the question with server id
     * {@code id} (an LCT question's part only), this session; null if none.
     */
    public String rejectedQuestion( int id ) {
        return rejectedQuestions.get( id ) ;
    }

    /** The subject's name in the prompt; null for an unknown subject. */
    public static String subjectName( Subject subject ) {
        return switch( subject ) {
            case PHYSICS   -> "Physics" ;
            case CHEMISTRY -> "Chemistry" ;
            case MATHS     -> "Mathematics" ;
            default        -> null ;
        } ;
    }

    /**
     * Why a question of {@code subject} and {@code type} can't be sent;
     * null if it can.
     */
    public String unsupported( Subject subject, String type ) {

        if( subjectName( subject ) == null ) return "unknown subject" ;
        if( type == null || !file.knowsType( type ) ) {
            return "the prompt has no section for question type " + type ;
        }
        return null ;
    }

    @Override
    public String prompt( QTask task ) {

        TranscriptionJob job = jobs.apply( task ) ;
        return file.fill( subjectName( task.getSubject() ), job.problemType(),
                          job.kind().section, job.passage() ) ;
    }

    /** The job's images stacked into one, as stored (no scaling). */
    @Override
    public AIRequest.Image prepareImage( QTask task ) throws IOException {

        List<File> files = task.getImgFiles() ;
        return AIRequest.Image.of( ImageCollator.collatedBytes( files ),
                                   ImageCollator.collatedMimeType( files ) ) ;
    }

    @Override
    public Transcription parse( String reply, QTask task ) throws BadReply {
        return parse( new AIReply( reply, null, null ), task ) ;
    }

    /**
     * {@code reply} through the gate: well formed, or a {@link BadReply}
     * naming the problems; then scored if it has token probabilities. The
     * transcription's model is the caller's to set.
     */
    @Override
    public Transcription parse( AIReply reply, QTask task ) throws BadReply {

        String             raw      = reply.text() == null ? ""
                                                           : reply.text() ;
        ReplyMend.Mended   mended   = ReplyMend.mend( raw ) ;
        String             text     = mended.text() ;
        String             content  = text.stripTrailing() ;
        TranscriptionJob   job      = jobs.apply( task ) ;
        boolean            passage  = job != null
                                      && job.kind()
                                         == TranscriptionJob.Kind.PASSAGE ;
        List<String>       problems = new ArrayList<>(
                WellFormed.problems( text, null ) ) ;
        String             mendNote = mended.changed()
                ? "Mended      : " + mended.describe() + "\n" : "" ;

        problems.addAll( WellFormed.appProblems( text ) ) ;
        if( job != null && job.kind() == TranscriptionJob.Kind.LCT_QUESTION
            && repeatsPassage( content, job.passage() ) ) {
            problems.add( "repeats the passage" ) ;
        }
        if( !problems.isEmpty() ) {
            if( passage && !content.isBlank() ) {
                rejectedPassages.put( job.lctCtxId(), content ) ;
            }
            else if( job != null && !content.isBlank() ) {
                rejectedQuestions.put( job.item().id(), content ) ;
            }
            notes.accept( task, mendNote + "Well formed : no - " +
                                String.join( "; ", problems ) + "\n" ) ;
            throw new BadReply( "malformed transcription: " +
                                String.join( "; ", problems ) ) ;
        }

        List<TokenLogprob> tokens = reply.tokens() ;
        if( tokens == null || tokens.isEmpty() ) {
            notes.accept( task, mendNote + "Well formed : yes\nScore" +
                                "       : no token " +
                                "probabilities\n" ) ;
            return new Transcription( content, null, null, null, null,
                                      List.of() ) ;
        }
        if( !raw.equals( joined( tokens ) ) ) {
            log.warn( "[{}] The reply's tokens don't add up to its text - " +
                      "not scored", task ) ;
            notes.accept( task, mendNote + "Well formed : yes\nScore" +
                                "       : none, the " +
                                "tokens don't add up to the text\n" ) ;
            return new Transcription( content, null, null, null, null,
                                      List.of() ) ;
        }

        // Scored as the model wrote it; the contests moved onto the text
        // as mended.
        ConfidenceScore.Result score = ConfidenceScore.score( raw, tokens ) ;
        Transcription          t     = new Transcription(
                content, null, score.c( margin ), score.geoMeanP(),
                score.minP(), shifted( score.contests( margin ), mended ) ) ;

        notes.accept( task, mendNote + describe( score ) ) ;
        return t ;
    }

    /**
     * {@code contests}, positions in the reply as the model wrote it, at
     * their places in the {@code mended} text: offsets and lines moved by
     * the changes made before them.
     */
    private static List<ConfidenceScore.Contest> shifted(
            List<ConfidenceScore.Contest> contests, ReplyMend.Mended mended ) {

        if( !mended.changed() ) return contests ;
        return contests.stream().map( x -> new ConfidenceScore.Contest(
                mended.shift( x.offset() ),
                x.line() + mended.linesBefore( x.offset() ), x.chosen(),
                x.pChosen(), x.rival(), x.pRival(), x.continuation() ) )
                       .toList() ;
    }

    /** The transcript's Confidence section for {@code score}. */
    private String describe( ConfidenceScore.Result score ) {

        StringBuilder sb = new StringBuilder() ;

        sb.append( "Well formed : yes\n" )
          .append( String.format( Locale.ROOT, "C           : %d at margin " +
                                  "%.2f%n", score.c( margin ), margin ) )
          .append( String.format( Locale.ROOT, "Tokens      : %d, geometric " +
                                  "mean p %s, lowest p %s%n", score.tokens(),
                                  p( score.geoMeanP() ), p( score.minP() ) ) ) ;
        if( score.contests().isEmpty() ) {
            return sb.append( "Candidates  : none\n" ).toString() ;
        }
        sb.append( "Candidates  : the closest rival at each position within " )
          .append( ConfidenceScore.MAX_MARGIN ).append( '\n' ) ;
        for( ConfidenceScore.Contest x : score.contests() ) {
            sb.append( String.format( Locale.ROOT,
                    "  line %-4d %s %s  vs  %s %s  diff %.3f  %s  at %s%n",
                    x.line(), quote( x.chosen() ), p( x.pChosen() ),
                    quote( x.rival() ), p( x.pRival() ), x.diff(),
                    x.diff() < margin ? "CONTESTED" : "not counted",
                    quote( x.continuation() ) ) ) ;
        }
        return sb.toString() ;
    }

    private static String p( Double p ) {
        return p == null ? "-" : String.format( Locale.ROOT, "%.3f", p ) ;
    }

    private static String quote( String s ) {
        return "\"" + s.replace( "\n", "\\n" ) + "\"" ;
    }

    private static String joined( List<TokenLogprob> tokens ) {

        StringBuilder sb = new StringBuilder() ;
        tokens.forEach( t -> sb.append( t.token() ) ) ;
        return sb.toString() ;
    }

    /**
     * True if {@code part} starts with the first non-blank line of
     * {@code passage}: the model transcribed the passage again.
     */
    static boolean repeatsPassage( String part, String passage ) {

        String first = firstLine( passage ) ;
        return !first.isEmpty() && firstLine( part ).equals( first ) ;
    }

    private static String firstLine( String text ) {

        if( text == null ) return "" ;
        for( String line : text.split( "\n" ) ) {
            if( !line.isBlank() ) return line.strip() ;
        }
        return "" ;
    }
}
