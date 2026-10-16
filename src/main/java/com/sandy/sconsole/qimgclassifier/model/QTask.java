package com.sandy.sconsole.qimgclassifier.model;

import lombok.Getter;

import java.io.File;
import java.util.List;

/**
 * Immutable identity of one question classification task.
 * <p>
 * A question is either a single image or a set of part images
 * ({@code X(1)}, {@code X(2)}, ...) which are collated vertically before being
 * sent to the model. An LCT question's images start with its group's shared
 * context images ({@code <S>_LCT_<n>_Ctx}), so the context is sent with every
 * question of the group. The question id is always the base id (without the
 * part suffix).
 * <p>
 * Carries no mutable state - all changing state (status, timing, attempts,
 * result) lives in the UI's table model and is only ever touched on the Swing
 * event dispatch thread, so a {@code QTask} can be shared freely between the
 * worker threads and the UI. Equality is identity: each question is
 * discovered once and the same instance is used throughout.
 */
@Getter
public final class QTask {

    /** Name of the source folder, e.g. {@code AITS-17-M-FT2}. */
    private final String sourceName ;
    private final File   srcDir ;

    /** Base question id (without any part suffix). */
    private final String  questionId ;
    private final Subject subject ;

    /**
     * The question's image, or its part images in ascending part order, after
     * any LCT context images. Never empty.
     */
    private final List<File> imgFiles ;

    /**
     * @param srcDir     the source folder the question belongs to
     * @param questionId base question id, e.g. {@code C_SCA_74}
     * @param imgFiles   the question's image, or its part images in ascending
     *         part order
     */
    public QTask( File srcDir, String questionId, List<File> imgFiles ) {
        this.sourceName = srcDir.getName() ;
        this.srcDir     = srcDir ;
        this.questionId = questionId ;
        this.subject    = Subject.fromQuestionId( questionId ) ;
        this.imgFiles   = List.copyOf( imgFiles ) ;
    }

    /**
     * True if the question is made of more than one image (parts, or an LCT
     * context and the question).
     */
    public boolean isMultiPart() { return imgFiles.size() > 1 ; }

    @Override
    public String toString() {
        return sourceName + "/" + questionId ;
    }
}
