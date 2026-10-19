package com.sandy.sconsole.ai;

/**
 * A model's answer to one request.
 *
 * @param text      the reply text, never blank
 * @param reasoning the model's reasoning before it answered, when the
 *                  gateway returned it; null if none
 * @param usage     the tokens the request used; null if the gateway did not
 *                  say
 */
public record AIReply( String text, String reasoning, TokenUsage usage ) {}
