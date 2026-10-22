package com.sandy.sconsole.qimgtools.apps.tagger.server;

import java.util.List;

/**
 * One question's new tag set for {@code POST /Master/TagAssociation/Refresh},
 * with, for a question of the scope's topic, its dependencies, or, for a
 * dependent, the dependency it closes.
 *
 * @param questionId         the question's numeric server id
 * @param tagIds             its full explicit tag set
 * @param dependencyTopicIds its full set of dependencies; null to leave
 *                           them as they are (always null with
 *                           {@code forDependencyOf})
 * @param dependencyActor    {@code AI} or {@code User}, with
 *                           {@code dependencyTopicIds}
 * @param dependencyModel    the AI model credited; null for {@code User}
 * @param forDependencyOf    the topic whose tags a dependent gets, closing
 *                           its dependency on it; null for a question of
 *                           the scope's topic
 */
public record RefreshItem( int questionId, List<Integer> tagIds,
                           List<Integer> dependencyTopicIds,
                           String dependencyActor, String dependencyModel,
                           Integer forDependencyOf ) {}
