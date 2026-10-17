package com.sandy.sconsole.qimgtools.apps.topicreviewer.server;

import com.sandy.sconsole.qimgtools.apps.topicreviewer.model.ServerQuestion;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * The first questions of the server's topic review queue
 * ({@code /Master/Question/TopicReview/Pending}), by id. There is no cursor:
 * a confirmed question leaves the queue, so the next fetch returns the ones
 * after it.
 */
@Data
public class PendingResult {

    /** Every flagged question matching the criteria, the returned included. */
    private int totalResults ;

    private List<ServerQuestion> questions = new ArrayList<>() ;
}
