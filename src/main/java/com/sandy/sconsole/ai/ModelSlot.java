package com.sandy.sconsole.ai;

import com.sandy.sconsole.ai.config.ModelInfo;

/**
 * A model slot that is free and may send now, asking the
 * {@link RoutingPolicy} for a job.
 *
 * @param model        the slot's model
 * @param inFlight     the model's requests in flight
 * @param avgLatencyMs the model's average reply time over its last few
 *                     replies; 0 if it has none yet
 */
public record ModelSlot( ModelInfo model, int inFlight, long avgLatencyMs ) {}
