package com.sandy.sconsole.qimgtools.apps.transcriber.model;

/**
 * A staged transcription to save.
 *
 * @param item          the question
 * @param transcription its text: an LCT question's part only, to which the
 *                      group's passage is put in front
 * @param route         how it came about
 */
public record Saving( TranscriptionItem item, Transcription transcription,
                      TranscriptionRoute route ) {}
