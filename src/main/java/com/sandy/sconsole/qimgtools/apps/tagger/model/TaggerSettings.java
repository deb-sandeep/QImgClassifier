package com.sandy.sconsole.qimgtools.apps.tagger.model;

/**
 * The choices the user makes in the Settings dialog.
 *
 * @param autoSendToLlm true to send the questions awaiting the LLM to it as
 *                      soon as a Load has read them; false to wait for Start
 * @param batchSize     questions per Load
 */
public record TaggerSettings( boolean autoSendToLlm, int batchSize ) {

    /** The settings until the user changes them. */
    public static final TaggerSettings DEFAULT = new TaggerSettings( true, 50 ) ;
}
