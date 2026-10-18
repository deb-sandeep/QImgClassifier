package com.sandy.sconsole.ai;

/** The app's hold on a submitted job. Thread-safe. */
public interface JobHandle {

    /** Where a job is. */
    enum State {

        /** Waiting for a model slot (also between attempts). */
        WAITING,

        /** Taken out of scheduling by {@link #hold()}. */
        HELD,

        /** An attempt is in flight. */
        RUNNING,

        /** Ended: succeeded, failed or dropped. */
        DONE
    }

    long id() ;

    State state() ;

    /**
     * Take the job out of scheduling until {@link #release} or
     * {@link #cancel}.
     *
     * @return false unless the job is waiting and has not started
     */
    boolean hold() ;

    /**
     * Put a held job back.
     *
     * @param front ahead of the jobs waiting, else behind them
     * @return false if the job was not held
     */
    boolean release( boolean front ) ;

    /**
     * End a held or waiting job that has not started; its listener is told
     * {@link JobListener#dropped}.
     *
     * @return false if the job has started or ended
     */
    boolean cancel() ;
}
