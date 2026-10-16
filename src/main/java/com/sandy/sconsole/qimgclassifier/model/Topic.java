package com.sandy.sconsole.qimgclassifier.model;

import lombok.Data;

/**
 * A syllabus topic. Serves two purposes, both bound from snake_case JSON:
 * <ul>
 *   <li>an entry of the topic master ({@code topic_master.json}, see
 *       {@link TopicRepo}), where {@link #matchProbability} is unused;</li>
 *   <li>one recommendation in a model's reply, where {@link #matchProbability}
 *       (0..1) is the model's confidence. Only {@link #id} is trusted; the name
 *       is checked against, and replaced by, the master entry.</li>
 * </ul>
 */
@Data
public class Topic {
    private int    id ;
    private String syllabusName ;

    /** Branch / chapter grouping within the syllabus, e.g. "Algebra". */
    private String section ;
    private String topicName ;

    /** Model confidence in 0..1; only set on a model recommendation. */
    private float matchProbability ;
}
