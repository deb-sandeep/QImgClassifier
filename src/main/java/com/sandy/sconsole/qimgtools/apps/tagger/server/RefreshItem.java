package com.sandy.sconsole.qimgtools.apps.tagger.server;

import java.util.List;

/**
 * One question's new tag set for {@code POST /Master/TagAssociation/Refresh}.
 *
 * @param questionId the question's numeric server id
 * @param tagIds     its full explicit tag set
 */
public record RefreshItem( int questionId, List<Integer> tagIds ) {}
