package com.sandy.sconsole.ai;

import com.sandy.sconsole.ai.config.GatewayType;

import java.util.List;

/**
 * A snapshot of the hub, cheap enough to poll every second.
 *
 * @param takenAt    epoch ms of the snapshot
 * @param gateways   every gateway of the config
 * @param models     every model the app uses
 * @param jobs       job counts
 * @param nextSendAt epoch ms from which some model with waiting work may
 *                   send (now or earlier: already); {@code Long.MAX_VALUE}
 *                   when no work waits
 * @param notices    what is held back by a limit right now, and why
 */
public record HubStatus( long takenAt, List<GatewayStatus> gateways,
                         List<ModelStatus> models, Jobs jobs, long nextSendAt,
                         List<Notice> notices ) {

    /**
     * @param name          the gateway's name
     * @param type          its transport
     * @param enabled       its switch in the global config
     * @param reachable     false while its last request could not connect
     *                      and none has since
     * @param heldUntil     epoch ms its suspension (after a rate limit or
     *                      failing to connect) lifts; 0 if none
     * @param heldReason    why it is held back; null if it isn't
     * @param heldForTheDay its daily limit is reached
     * @param requestsToday its requests counted against its daily limit
     *                      (only kept with an rpd)
     * @param inFlight      its requests in flight
     * @param maxParallel   its cap on them; 0 for none
     */
    public record GatewayStatus( String name, GatewayType type,
                                 boolean enabled, boolean reachable,
                                 long heldUntil, String heldReason,
                                 boolean heldForTheDay, int requestsToday,
                                 int inFlight, int maxParallel ) {}

    /**
     * @param id            the model id
     * @param gateway       its gateway's name
     * @param displayName   its display name, else its id
     * @param usable        it can be sent requests at all
     * @param available     usable and not held back right now
     * @param heldUntil     epoch ms its pause (after a rate limit on it)
     *                      lifts; 0 if none
     * @param heldReason    why it is held back; null if it isn't
     * @param heldForTheDay its own daily limit is reached
     * @param inFlight      its requests in flight
     * @param maxParallel   its slots
     * @param waiting       waiting jobs that may run on it now
     * @param avgLatencyMs  its average reply time over its last replies; 0
     *                      if it has none
     * @param succeeded     its attempts that succeeded since the start
     * @param failed        its attempts that failed (not handed back)
     */
    public record ModelStatus( String id, String gateway, String displayName,
                               boolean usable, boolean available,
                               long heldUntil, String heldReason,
                               boolean heldForTheDay, int inFlight,
                               int maxParallel, int waiting,
                               long avgLatencyMs, int succeeded,
                               int failed ) {}

    /**
     * @param waiting jobs waiting, including those between attempts
     * @param held    jobs held out of scheduling
     * @param running jobs with a request in flight
     * @param done    jobs that ended after starting, since the start
     */
    public record Jobs( int waiting, int held, int running, int done ) {}

    /**
     * A gateway, or one of its models, held back by a limit.
     *
     * @param gateway     the gateway's name
     * @param gatewayType its transport
     * @param model       the model; null when the whole gateway is held
     * @param reason      short readable cause
     * @param until       epoch ms from which it may send again
     * @param daily       held until a daily limit resets
     */
    public record Notice( String gateway, GatewayType gatewayType,
                          String model, String reason, long until,
                          boolean daily ) {}
}
