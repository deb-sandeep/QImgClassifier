package com.sandy.sconsole.ai;

import com.sandy.sconsole.ai.config.ModelInfo;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Work for the hub: what to ask, where it may start, and who to tell.
 *
 * @param payload   the app's own object, handed back in every callback
 * @param roots     the roots the job may start on (any model of any of them;
 *                  the {@link RoutingPolicy} decides)
 * @param request   builds the request for an attempt on a model
 * @param keepGoing checked before each attempt; once false, a job that has
 *                  not started is dropped, and a started one ends with what
 *                  it has (a provisional result, else its last failure)
 * @param listener  told about the job's progress
 * @param model     the one model the job runs on until it is escalated, in
 *                  place of its roots' models: it retries there and never
 *                  falls back or has a stand-in, waiting out any hold; null
 *                  for any model of its roots. After an escalation the job
 *                  goes to the escalation root's models as usual
 * @param <P>       the payload type
 * @param <R>       the result type
 */
public record AIJob<P,R>( P payload, List<String> roots,
                          RequestFactory request, BooleanSupplier keepGoing,
                          JobListener<P,R> listener, String model ) {

    public AIJob {
        roots = List.copyOf( roots ) ;
    }

    /** A job on any model of its {@code roots}. */
    public AIJob( P payload, List<String> roots, RequestFactory request,
                  BooleanSupplier keepGoing, JobListener<P,R> listener ) {
        this( payload, roots, request, keepGoing, listener, null ) ;
    }

    /** Builds a job's request for one attempt. */
    @FunctionalInterface
    public interface RequestFactory {

        /**
         * The request to send to {@code model}. Called before every attempt,
         * so cache anything costly.
         *
         * @throws Exception if it cannot be built; the job then fails
         */
        AIRequest build( ModelInfo model ) throws Exception ;
    }
}
