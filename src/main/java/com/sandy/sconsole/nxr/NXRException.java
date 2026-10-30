package com.sandy.sconsole.nxr;

/**
 * A fault in the classifier's setup, from {@link NXRClassifier#load}: the
 * model file missing, unreadable or of an unknown version, or tesseract
 * missing or failing on its test image. A fault on a single question is
 * never thrown; it gives a null result with a reason instead.
 */
public class NXRException extends Exception {

    public NXRException( String message ) {
        super( message ) ;
    }

    public NXRException( String message, Throwable cause ) {
        super( message, cause ) ;
    }
}
