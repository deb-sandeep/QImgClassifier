package com.sandy.sconsole.qimgtools.apps.nxrclassifier.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Where a question awaiting its NXR label stands in the NXR classifier. */
@Getter
@RequiredArgsConstructor
public enum NXRStatus {

    /** Loaded; not classified yet. */
    PENDING( "Pending" ),

    /** The classifier gave a label; it is saved as it is. */
    CLASSIFIED( "Classified" ),

    /** The classifier gave no label; awaits the user's, or the AI's. */
    UNDECIDED( "Undecided" ),

    /** Sent to an AI model, as the classifier gave no label. */
    ASKING_AI( "Asking AI" ),

    /** An AI model gave the label the classifier didn't; saved as it is. */
    AI_LABELLED( "AI labelled" ),

    /** The question's images are not in the question bank. */
    NO_IMAGES( "No local images" ),

    /** The image could not be read, or the classifier failed on it. */
    FAILED( "Failed" ),

    /** The user chose the label; not sent to the server yet. */
    DECIDED( "Decided" ),

    /** The label is being sent to the server. */
    SAVING( "Saving" ),

    /** The server holds the label. */
    SAVED( "Saved" ),

    /** The server refused the label, or could not be reached. */
    ERROR( "Error" ) ;

    private final String label ;

    @Override
    public String toString() { return label ; }
}
