package com.sandy.sconsole.nxr;

/**
 * The label {@link NXRClassifier#classify} gives one question, and how it
 * came to it.
 *
 * @param value      "N", "X", "R", or null: a human decides
 * @param confidence 0-1; 1.0 for a rule
 * @param method     the tier that decided, or {@link Method#NONE} when
 *        {@code value} is null
 * @param reason     the rule or gate that fired, or why {@code value} is null
 * @param ocrText    the normalised OCR text; null if OCR did not run
 * @param guess      the label the tier leans to: {@code value} when there
 *        is one, the model's most likely class when it is below its
 *        threshold; null when no tier got that far (not in the spec: it
 *        lets a human see the model's lean on a null result)
 */
public record NXRResult( String value, double confidence, Method method,
                         String reason, String ocrText, String guess ) {

    /** The tier of the pipeline that gave the label. */
    public enum Method {

        /** A rule on the metadata alone, without OCR. */
        RULE,

        /** The Maths gate. */
        GATE,

        /** The trained model, above its threshold. */
        MODEL,

        /** None did: the value is null. */
        NONE
    }
}
