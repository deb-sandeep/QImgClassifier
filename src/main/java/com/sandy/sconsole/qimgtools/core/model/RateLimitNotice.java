package com.sandy.sconsole.qimgtools.core.model;

/**
 * An AI target, or one of its models, that is not sent requests for now
 * because of a rate limit (or because it could not be reached).
 *
 * @param agent  the target
 * @param model  the paused model; null when the whole target is held back
 * @param reason short readable cause, e.g. "daily limit of 1000 requests
 *               reached"
 * @param until  epoch ms from which requests may be sent again
 * @param daily  true if the target's daily quota is used up, so it is held
 *               back until the quota resets
 */
public record RateLimitNotice( AIAgent agent, String model, String reason,
                               long until, boolean daily ) {}
