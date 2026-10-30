package com.sandy.sconsole.nxr;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Pattern;

/**
 * The tiers before the model: the rules on the metadata alone (spec §4.1),
 * each switched on or off in the model file's {@code rules}, and the Maths
 * gate on the OCR text (§4.3). Also the Physics/Maths rule on the model's
 * scores (§4.5) and the switch of the Chemistry patterns (§4.6).
 */
final class Rules {

    static final String PHYSICS_NUMERIC_N = "physicsNumericN" ;
    static final String ART_X             = "artX" ;
    static final String MATHS_GATE        = "mathsGate" ;
    static final String PM_NO_RECALL      = "physicsMathsNoRecall" ;
    static final String CHEM_PATTERNS     = "chemistryPatterns" ;

    private final boolean physicsNumericN ;
    private final boolean artX ;
    private final boolean pmNoRecall ;
    private final boolean chemPatterns ;

    /** The p(N) at which a Physics or Maths question is N rather than X. */
    private final double pmNMin ;

    /** Matched against the cleaned text; no match means N. */
    private final Pattern mathsFlag ;

    Rules( JsonNode model ) throws NXRException {

        JsonNode rules = ModelFile.node( model, "rules" ) ;
        var      names = rules.fieldNames() ;

        while( names.hasNext() ) {
            String name = names.next() ;
            if( !name.equals( PHYSICS_NUMERIC_N ) && !name.equals( ART_X )
                && !name.equals( PM_NO_RECALL )
                && !name.equals( CHEM_PATTERNS ) ) {
                throw new NXRException( "Unknown rule '" + name + "'" ) ;
            }
        }
        physicsNumericN = rules.path( PHYSICS_NUMERIC_N ).asBoolean( false ) ;
        artX            = rules.path( ART_X ).asBoolean( false ) ;
        pmNoRecall      = rules.path( PM_NO_RECALL ).asBoolean( false ) ;
        chemPatterns    = rules.path( CHEM_PATTERNS ).asBoolean( false ) ;
        pmNMin          = pmNoRecall
                          ? ModelFile.number( model, "physicsMathsNMin" ) : 0 ;
        mathsFlag       = Normaliser.pattern( model, "mathsFlagKeywords" ) ;
    }

    /**
     * The rule that labels a question of {@code subject} and {@code type}
     * without reading it, as {label, rule}; null if none does.
     */
    String[] rule( String subject, String type ) {

        if( physicsNumericN && subject.equals( "P" )
            && ( "IVT".equals( type ) || "NVT".equals( type ) ) ) {
            return new String[] { "N", PHYSICS_NUMERIC_N } ;
        }
        if( artX && "ART".equals( type ) ) {
            return new String[] { "X", ART_X } ;
        }
        return null ;
    }

    /**
     * True if the Maths gate labels the question N: the cleaned
     * {@code text} has none of the flag keywords.
     */
    boolean gatePasses( String text ) {
        return !mathsFlag.matcher( text ).find() ;
    }

    /**
     * The label of a Physics or Maths question the model has scored with
     * {@code pN} = p(N): never R, so N if {@code pN} reaches
     * {@code physicsMathsNMin}, else X. Null if the rule is off or the
     * subject is Chemistry.
     */
    String noRecall( String subject, double pN ) {

        if( !pmNoRecall || subject.equals( "C" ) ) return null ;
        return pN >= pmNMin ? "N" : "X" ;
    }

    /** True if the Chemistry patterns (§4.6) are on. */
    boolean chemistryPatterns() {
        return chemPatterns ;
    }
}
