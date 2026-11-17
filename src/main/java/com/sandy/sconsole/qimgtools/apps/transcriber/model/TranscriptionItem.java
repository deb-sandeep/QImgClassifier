package com.sandy.sconsole.qimgtools.apps.transcriber.model;

import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.review.QuestionLocator;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;
import com.sandy.sconsole.qimgtools.core.util.FileHelper;

import java.io.File;
import java.util.List;

/**
 * One question awaiting its transcription, as loaded. Immutable; what
 * happens to it later lives in the table model.
 *
 * @param question          the question as the server sent it
 * @param task              the question's own images (an LCT group's
 *                          passage images left out); null if they could
 *                          not be found
 * @param contextFiles      an LCT question's passage images, in order;
 *                          empty for any other question
 * @param problem           why {@code task} is null; null otherwise
 * @param lctCtxId          the LCT group, e.g. {@code LK-A-P-11://P/LCT/1};
 *                          null for a question of another type
 * @param lctCtxTranscribed true if the server holds the group's passage
 */
public record TranscriptionItem( ServerQuestion question, QTask task,
                                 List<File> contextFiles, String problem,
                                 String lctCtxId,
                                 boolean lctCtxTranscribed ) {

    public TranscriptionItem {
        contextFiles = List.copyOf( contextFiles ) ;
    }

    /**
     * The item of a question {@code located} in the question bank: its
     * images split into the passage's ({@code _Ctx}) and its own.
     */
    public static TranscriptionItem of( QuestionLocator.Located located,
                                        String lctCtxId,
                                        Boolean lctCtxTranscribed ) {

        QTask  task    = located.task() ;
        String problem = located.problem() ;

        if( task == null ) {
            return new TranscriptionItem( located.question(), null, List.of(),
                                          problem, lctCtxId,
                                          Boolean.TRUE.equals(
                                                  lctCtxTranscribed ) ) ;
        }

        List<File> context = task.getImgFiles().stream()
                                 .filter( TranscriptionItem::isContext )
                                 .toList() ;
        List<File> own     = task.getImgFiles().stream()
                                 .filter( f -> !isContext( f ) )
                                 .toList() ;

        if( own.isEmpty() ) {
            task    = null ;
            problem = "Only the passage's images were found" ;
        }
        else if( lctCtxId != null && context.isEmpty() ) {
            task    = null ;
            problem = "The passage's images were not found" ;
        }
        else {
            task = new QTask( task.getSrcDir(), task.getQuestionId(), own ) ;
        }
        return new TranscriptionItem( located.question(), task, context,
                                      problem, lctCtxId,
                                      Boolean.TRUE.equals( lctCtxTranscribed ) ) ;
    }

    /** True for an LCT group's passage image ({@code <S>_LCT_<n>_Ctx}). */
    public static boolean isContext( File f ) {
        return FileHelper.baseQuestionId( FileHelper.extractQuestionId( f ) )
                         .endsWith( "_Ctx" ) ;
    }

    /** The question's numeric server id. */
    public int id() {
        return question.getId() ;
    }

    /** True if the question's images were found, so it can be sent. */
    public boolean hasImages() {
        return task != null ;
    }

    /** True for a question of an LCT group. */
    public boolean isLct() {
        return lctCtxId != null ;
    }

    /** Local question id, or the server's if the images weren't found. */
    public String label() {
        return task != null ? task.getQuestionId() : question.getQuestionId() ;
    }

    @Override
    public String toString() {
        return question.getId() + " " + question.getQuestionId() ;
    }
}
