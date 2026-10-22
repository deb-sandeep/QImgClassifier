package com.sandy.sconsole.qimgtools.core.server;

/**
 * A topic of its syllabus a question's solution depends on, as the server
 * keeps it ({@code QuestionVO.topicDependencies}).
 *
 * @param topicId   the topic depended on, never the question's own
 * @param topicName its name
 * @param open      true until that topic's tags have been applied to the
 *                  question
 */
public record TopicDependency( int topicId, String topicName, boolean open ) {}
