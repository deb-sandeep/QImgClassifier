package com.sandy.sconsole.qimgtools.apps.transcriber.pipeline;

import com.sandy.sconsole.qimgtools.apps.transcriber.model.TranscriptionItem;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.util.FileHelper;

/**
 * What one task sent to the models is: a question, an LCT question part or
 * an LCT group's passage.
 *
 * @param kind     which of the three
 * @param item     the question; for a passage, the group's question it was
 *                 started for, whose row shows its progress
 * @param task     what is sent: the question's own images, or the
 *                 passage's
 * @param lctCtxId the LCT group; null for a plain question
 * @param passage  for an LCT question part, the passage given as context
 * @param resent   true if it is sent again after its transcription was
 *                 marked faulty
 */
public record TranscriptionJob( Kind kind, TranscriptionItem item, QTask task,
                                String lctCtxId, String passage,
                                boolean resent ) {

    /** The three kinds of job, each with its own TASK section. */
    public enum Kind {

        QUESTION( "TASK QUESTION" ),
        LCT_QUESTION( "TASK LCT_QUESTION" ),
        PASSAGE( "TASK LCT_PASSAGE" ) ;

        /** The prompt file's section for it. */
        public final String section ;

        Kind( String section ) {
            this.section = section ;
        }
    }

    /** The job transcribing {@code item}, an LCT one against {@code passage}. */
    public static TranscriptionJob question( TranscriptionItem item,
                                             String passage, boolean resent ) {
        return item.isLct()
               ? new TranscriptionJob( Kind.LCT_QUESTION, item, item.task(),
                                       item.lctCtxId(), passage, resent )
               : new TranscriptionJob( Kind.QUESTION, item, item.task(), null,
                                       null, resent ) ;
    }

    /**
     * The job transcribing the passage of {@code item}'s group, from its
     * {@code _Ctx} images, as a task named after them (e.g.
     * {@code P_LCT_1_Ctx}), so its transcripts are kept apart.
     */
    public static TranscriptionJob passage( TranscriptionItem item,
                                            boolean resent ) {

        String id   = FileHelper.baseQuestionId( FileHelper.extractQuestionId(
                              item.contextFiles().get( 0 ) ) ) ;
        QTask  task = new QTask( item.task().getSrcDir(), id,
                                 item.contextFiles() ) ;

        return new TranscriptionJob( Kind.PASSAGE, item, task, item.lctCtxId(),
                                     null, resent ) ;
    }

    /** The server's question type, e.g. {@code SCA}; {@code LCT} for a passage. */
    public String problemType() {
        return kind == Kind.PASSAGE ? "LCT" : item.question().getProblemType() ;
    }

    @Override
    public String toString() {
        return kind == Kind.PASSAGE ? "passage " + lctCtxId : item.toString() ;
    }
}
