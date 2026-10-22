package com.sandy.sconsole.qimgtools.core.server;


import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * The first questions, by id, of one of the server's queues: the topic
 * review queue ({@code /Master/Question/TopicReview/Pending}) or the
 * tagger's ({@code /Master/Question/AutoTagging/Pending} and
 * {@code .../Dependents}), after the {@code afterId} asked for, if any.
 */
@Data
public class PendingResult {

    /**
     * Every question in the queue matching the criteria, the returned
     * included, whatever the {@code afterId}.
     */
    private int totalResults ;

    private List<ServerQuestion> questions = new ArrayList<>() ;
}
