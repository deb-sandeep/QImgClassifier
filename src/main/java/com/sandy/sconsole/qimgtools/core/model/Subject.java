package com.sandy.sconsole.qimgtools.core.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * JEE subject a question belongs to, derived from the first segment of its
 * question id ({@code P_SCA_12} is Physics). Each known subject maps to the
 * syllabus name its topics carry in {@code topic_master.json}.
 */
@Getter
@RequiredArgsConstructor
public enum Subject {

    PHYSICS  ( "P", "IIT Physics"   ),
    CHEMISTRY( "C", "IIT Chemistry" ),
    MATHS    ( "M", "IIT Maths"     ),

    /** The question id carries no recognised subject prefix. */
    UNKNOWN  ( "?", null            ) ;

    /**
     * Single-letter code used in question ids and shown in the UI, e.g. "P";
     * "?" for {@link #UNKNOWN}.
     */
    private final String code ;

    /**
     * Syllabus name in the topic master, e.g. "IIT Physics"; null for
     * {@link #UNKNOWN}.
     */
    private final String syllabusName ;

    /**
     * True if the subject has a syllabus, i.e. its questions can be classified
     * and associated.
     */
    public boolean hasSyllabus() { return syllabusName != null ; }

    /**
     * Subject of a question id such as {@code C_SCA_74} or {@code C_SCA_74(2)};
     * {@link #UNKNOWN} if unrecognised.
     */
    public static Subject fromQuestionId( String questionId ) {
        for( Subject s : values() ) {
            if( s != UNKNOWN && questionId.startsWith( s.code + "_" ) ) return s ;
        }
        return UNKNOWN ;
    }

    @Override
    public String toString() { return code ; }
}
