package com.sandy.sconsole.ai;

import com.sandy.sconsole.ai.config.ModelInfo;

/**
 * One request of a job, as it goes out.
 *
 * @param jobId     the job's id
 * @param number    1-based, across the job's models; an attempt handed back
 *                  after a rate limit is not counted, so the next one has
 *                  the same number
 * @param model     the model asked
 * @param request   what was sent
 * @param startedAt epoch ms the request was sent
 */
public record Attempt( long jobId, int number, ModelInfo model,
                       AIRequest request, long startedAt ) {}
