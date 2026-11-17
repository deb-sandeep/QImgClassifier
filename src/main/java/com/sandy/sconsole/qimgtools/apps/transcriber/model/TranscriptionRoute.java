package com.sandy.sconsole.qimgtools.apps.transcriber.model;

/**
 * How a transcription came about.
 *
 * @param kind   the way it took
 * @param model  the model whose text it is (for an edit, the model whose
 *               text was edited; null if it was typed from scratch)
 * @param reason why it left Qwen, or null
 */
public record TranscriptionRoute( Kind kind, String model, String reason ) {

    /** The ways a transcription comes about. */
    public enum Kind {

        /** Qwen's, accepted. */
        QWEN,

        /** Qwen's reply failed or wasn't confident: Nemotron's taken. */
        ESCALATED,

        /** Qwen was down or held back: Nemotron stood in. */
        STAND_IN,

        /** Re-sent after the user marked the earlier one faulty. */
        RESENT,

        /** Sent by the user to a model of their choice, that model alone. */
        CHOSEN,

        /** Edited by hand. */
        EDITED,

        /** A passage read from the server. */
        STORED
    }

    /** The {@code aiModel} saved with it: {@code user} for an edit. */
    public String aiModel() {
        return kind == Kind.EDITED ? "user" : model ;
    }
}
