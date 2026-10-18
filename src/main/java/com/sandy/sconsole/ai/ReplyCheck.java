package com.sandy.sconsole.ai;

/**
 * The app's verdict on a reply ({@link JobListener#check}).
 *
 * @param <R> the app's result type
 */
public sealed interface ReplyCheck<R> {

    /** The reply is the job's result. */
    record Accept<R>( R value ) implements ReplyCheck<R> {}

    /**
     * The reply is no use: the attempt fails as {@link FailureKind#BAD_REPLY}
     * and the job retries or falls back as for any failure.
     */
    record Reject<R>( String reason ) implements ReplyCheck<R> {}

    /**
     * The reply will do, but another root should be asked: the job goes on
     * to the models of {@code root} (not the one that replied). The first of
     * them to give an accepted reply provides the result; if none does (they
     * fail, or the job is stopped), {@code value} is the result. With no
     * usable model in {@code root}, {@code value} is taken at once.
     */
    record Escalate<R>( R value, String root, String why )
            implements ReplyCheck<R> {}

    static <R> ReplyCheck<R> accept( R value ) {
        return new Accept<>( value ) ;
    }

    static <R> ReplyCheck<R> reject( String reason ) {
        return new Reject<>( reason ) ;
    }

    static <R> ReplyCheck<R> escalate( R value, String root, String why ) {
        return new Escalate<>( value, root, why ) ;
    }
}
