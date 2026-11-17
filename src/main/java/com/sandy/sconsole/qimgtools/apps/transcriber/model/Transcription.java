package com.sandy.sconsole.qimgtools.apps.transcriber.model;

import com.sandy.sconsole.qimgtools.apps.transcriber.gate.ConfidenceScore;

import java.util.List;

/**
 * A model's transcription of a question, an LCT question part or a
 * passage, as the gate read it.
 *
 * @param content  the markdown, trailing whitespace stripped
 * @param model    the model that wrote it
 * @param c        Qwen's contested positions at the gate's margin; null for
 *                 a reply without token probabilities
 * @param geoMeanP the geometric mean probability of the scored tokens; null
 *                 if none
 * @param minP     the lowest of those probabilities; null if none
 * @param contests the contested positions counted in {@code c}
 */
public record Transcription( String content, String model, Integer c,
                             Double geoMeanP, Double minP,
                             List<ConfidenceScore.Contest> contests ) {

    /** Between an LCT question's passage and its question part. */
    public static final String LCT_MARKER = "\n\n<!-- question -->\n\n" ;

    public Transcription {
        contests = contests == null ? List.of() : List.copyOf( contests ) ;
    }

    /** A transcription without a score: a passage read, an edit. */
    public static Transcription unscored( String content, String model ) {
        return new Transcription( content, model, null, null, null,
                                  List.of() ) ;
    }

    /** An LCT question's whole content: passage, marker, question part. */
    public static String lctContent( String passage, String questionPart ) {
        return passage + LCT_MARKER + questionPart ;
    }

    /**
     * The question part of an LCT question's whole {@code content}: what
     * follows the marker; null if there is no marker.
     */
    public static String questionPart( String content ) {
        int at = content == null ? -1 : content.indexOf( LCT_MARKER ) ;
        return at < 0 ? null : content.substring( at + LCT_MARKER.length() ) ;
    }

    /** This transcription, credited to {@code model}. */
    public Transcription withModel( String model ) {
        return new Transcription( content, model, c, geoMeanP, minP,
                                  contests ) ;
    }

    /** This transcription's score, with {@code content} in place of its text. */
    public Transcription withContent( String content ) {
        return new Transcription( content, model, c, geoMeanP, minP,
                                  contests ) ;
    }

    /** "C 2", or "C -" without a score. */
    public String describeC() {
        return "C " + ( c == null ? "-" : c.toString() ) ;
    }
}
