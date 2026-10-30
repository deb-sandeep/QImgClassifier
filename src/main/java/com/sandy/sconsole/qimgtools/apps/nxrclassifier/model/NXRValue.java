package com.sandy.sconsole.qimgtools.apps.nxrclassifier.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A question's NXR label, as the server stores it in
 * {@code QuestionVO.nxrValue}: what solving it mostly takes. Mock tests keep
 * a set share of each, to resemble the real exam.
 */
@Getter
@RequiredArgsConstructor
public enum NXRValue {

    /** Mostly computation. */
    N( "Numeric" ),

    /** Mostly reasoning. */
    X( "Reasoning" ),

    /** Mostly recall of facts. */
    R( "Recall" ) ;

    private final String label ;

    /** The value named {@code code} ("N", "X" or "R"); null for any other. */
    public static NXRValue of( String code ) {

        if( code == null ) return null ;
        for( NXRValue v : values() ) {
            if( v.name().equals( code ) ) return v ;
        }
        return null ;
    }

    /** "N  Numeric". */
    public String describe() {
        return name() + "  " + label ;
    }
}
