package com.sandy.sconsole.nxr;

import com.fasterxml.jackson.databind.JsonNode;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Labels a question N (numeric), X (reasoning) or R (recall) locally, from
 * its image and the metadata the server has, by a pipeline of tiers: rules
 * on the metadata, OCR, a Maths gate and a trained model. When it can't
 * label a question with enough confidence it gives a null value, and a
 * human labels it. Specified in
 * {@code docs/feature-enhancements/2026-10-30/nxr-classifier/feature-design.md}.
 * <p>
 * The text features mirror the trainer ({@code train.py} / {@code predict.py}
 * in the question bank's {@code supporting-artefacts/analysis/nxr-model/}),
 * and {@link #load} proves it with the model file's golden vectors.
 * <p>
 * Standalone, like {@code TopicResolver}: the JDK and Jackson only, no
 * Lombok, no SLF4J and no project imports, so it can be copied into another
 * app unchanged. Thread-safe: one instance serves every thread, each call
 * running its own tesseract process.
 */
public final class NXRClassifier {

    /** The subject codes a question may have. */
    private static final Set<String> SUBJECTS = Set.of( "P", "C", "M" ) ;

    /** Most a golden case's probability may differ from the trainer's. */
    private static final double GOLDEN_TOLERANCE = 1e-6 ;

    private final Rules       rules ;
    private final Ocr         ocr ;
    private final Features    features ;
    private final LinearModel model ;

    /** Null when the model file switches them off. */
    private final ChemistryPatterns chemistry ;

    private NXRClassifier( Rules rules, Ocr ocr, Features features,
                           LinearModel model, ChemistryPatterns chemistry ) {
        this.rules     = rules ;
        this.ocr       = ocr ;
        this.features  = features ;
        this.model     = model ;
        this.chemistry = chemistry ;
    }

    /**
     * Load the model file and check that tesseract runs.
     *
     * @param modelFile    the trained model (JSON)
     * @param tesseractBin the tesseract executable
     * @throws NXRException if the model file is missing, unreadable, of an
     *         unknown version or inconsistent, a golden case scores off the
     *         trainer's probabilities or a Chemistry pattern golden case
     *         matches otherwise, or tesseract is missing or fails on
     *         its test image
     */
    public static NXRClassifier load( Path modelFile, Path tesseractBin )
            throws NXRException {

        JsonNode          root     = ModelFile.read( modelFile ) ;
        Normaliser        norm     = new Normaliser(
                ModelFile.node( root, "tokeniser" ) ) ;
        Features          features = new Features( root, norm ) ;
        LinearModel       model    = new LinearModel( root, features.size() ) ;
        Rules             rules    = new Rules( root ) ;
        ChemistryPatterns chem     = rules.chemistryPatterns()
                                     ? new ChemistryPatterns( root, norm )
                                     : null ;
        NXRClassifier     c        = new NXRClassifier(
                rules, new Ocr( root, tesseractBin ), features, model, chem ) ;

        c.checkGolden( ModelFile.node( root, "golden" ) ) ;
        if( chem != null ) {
            if( model.index( "R" ) < 0 ) {
                throw new NXRException( "'classes' has no R" ) ;
            }
            chem.check( ModelFile.node( root, "chemistryPatternGolden" ) ) ;
        }
        c.ocr.check() ;
        return c ;
    }

    /**
     * Score each golden case (OCR text and metadata, with the trainer's
     * probabilities) and fail on the first that drifts.
     */
    private void checkGolden( JsonNode golden ) throws NXRException {

        if( !golden.isArray() || golden.isEmpty() ) {
            throw new NXRException( "The model file has no golden cases" ) ;
        }
        for( int i = 0 ; i < golden.size() ; i++ ) {
            JsonNode g     = golden.get( i ) ;
            JsonNode topic = g.get( "topicId" ) ;
            double[] want  = ModelFile.doubles( ModelFile.node( g, "p" ),
                                                "golden.p" ) ;
            double[] got   = model.probabilities( features.vector(
                    ModelFile.text( g, "text" ),
                    ModelFile.text( g, "subject" ),
                    ModelFile.text( g, "problemType" ),
                    topic == null || topic.isNull() ? null : topic.asInt() ) ) ;

            if( want.length != got.length ) {
                throw new NXRException( "Golden case " + i + " has " +
                                        want.length + " probabilities" ) ;
            }
            for( int c = 0 ; c < got.length ; c++ ) {
                double dp = Math.abs( got[c] - want[c] ) ;
                if( dp > GOLDEN_TOLERANCE ) {
                    throw new NXRException( String.format( Locale.ROOT,
                            "Golden case %d scores %s %.9f, the trainer " +
                            "%.9f: the Java features drift from the " +
                            "trainer's", i, model.label( c ), got[c],
                            want[c] ) ) ;
                }
            }
        }
    }

    /**
     * Label one question. Never throws for a bad image: a fault on the
     * question gives a null value with the reason.
     *
     * @param image       the question as one image, its parts stacked in
     *        order; for an LCT question its own images only, not the shared
     *        context passage
     * @param subject     "P", "C" or "M"
     * @param problemType SCA, MCA, ART, CMT, LCT, MMT, NVT or IVT
     * @param topicId     the server's topic id; null if unknown
     */
    public NXRResult classify( BufferedImage image, String subject,
                               String problemType, Integer topicId ) {
        try {
            return tiers( image, subject, problemType, topicId ) ;
        }
        catch( Exception e ) {
            return none( 0, "failed: " + e, null ) ;
        }
    }

    private NXRResult tiers( BufferedImage image, String subject,
                             String type, Integer topicId )
            throws NXRException {

        if( !SUBJECTS.contains( subject ) ) {
            return none( 0, "unknown subject", null ) ;
        }

        String[] rule = rules.rule( subject, type ) ;
        if( rule != null ) {
            return new NXRResult( rule[0], 1.0, NXRResult.Method.RULE,
                                  rule[1], null, rule[0] ) ;
        }

        if( image == null ) return none( 0, "ocr failed: no image", null ) ;
        Ocr.Text raw = ocr.read( image ) ;
        if( raw.failure() != null ) {
            return none( 0, "ocr failed: " + raw.failure(), null ) ;
        }

        String text = Normaliser.clean( raw.text() ) ;
        if( text.length() < ocr.minChars() ) {
            return none( 0, "ocr empty", text ) ;
        }
        if( subject.equals( "M" ) && rules.gatePasses( text ) ) {
            return new NXRResult( "N", 1.0, NXRResult.Method.GATE,
                                  Rules.MATHS_GATE, text, "N" ) ;
        }

        double[] p = model.probabilities(
                features.vector( raw.text(), subject, type, topicId ) ) ;
        double   pN = p[model.index( "N" )] ;
        String pmLabel = rules.noRecall( subject, pN ) ;
        if( pmLabel != null ) {
            return new NXRResult( pmLabel, pmLabel.equals( "N" ) ? pN : 1 - pN,
                                  NXRResult.Method.MODEL, Rules.PM_NO_RECALL,
                                  text, pmLabel ) ;
        }

        boolean chem = chemistry != null && subject.equals( "C" ) ;
        if( chem ) chemistry.boost( text, p, model.index( "N" ) ) ;

        int    best      = argmax( p ) ;
        String label     = model.label( best ) ;
        double threshold = model.threshold( subject, type,
                                            features.trained( type ), label ) ;
        if( p[best] < threshold && chem ) {
            String[] cp = chemistry.match( text ) ;
            if( cp != null ) {
                return new NXRResult( cp[0], 1.0, NXRResult.Method.RULE,
                                      cp[1], text, cp[0] ) ;
            }
            if( chemistry.scenario( text, p[model.index( "N" )],
                                    p[model.index( "R" )] ) ) {
                return new NXRResult( "X", 1.0, NXRResult.Method.RULE,
                                      ChemistryPatterns.SCENARIO_X, text,
                                      "X" ) ;
            }
        }
        if( p[best] >= threshold && chem && label.equals( "R" )
            && chemistry.compare( text ) ) {
            // a comparison is reasoning, however sure the model is of recall
            return new NXRResult( "X", 1.0, NXRResult.Method.RULE,
                                  ChemistryPatterns.COMPARE_X, text, "X" ) ;
        }
        if( p[best] < threshold ) {
            return new NXRResult( null, p[best], NXRResult.Method.NONE,
                    String.format( Locale.ROOT, "low confidence %.2f < %.2f",
                                   p[best], threshold ), text, label ) ;
        }
        return new NXRResult( label, p[best], NXRResult.Method.MODEL, null,
                              text, label ) ;
    }

    private static int argmax( double[] p ) {

        int best = 0 ;
        for( int c = 1 ; c < p.length ; c++ ) {
            if( p[c] > p[best] ) best = c ;
        }
        return best ;
    }

    private static NXRResult none( double confidence, String reason,
                                   String ocrText ) {
        return new NXRResult( null, confidence, NXRResult.Method.NONE,
                              reason, ocrText, null ) ;
    }
}
