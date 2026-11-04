package com.sandy.sconsole.qimgtools.apps.tagger.model;

import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;

import java.util.List;

/**
 * One question of a Load, as loaded. Immutable; the question's later state
 * (the model's reply, the user's decision, the server's answer) lives in the
 * tagger's table model.
 *
 * @param question the question as the server sent it
 * @param task     its local images, ready for the models; null if they could
 *                 not be found
 * @param problem  why {@code task} is null; null otherwise
 * @param tags     every tag on it now, explicit or implied
 */
public record TagItem( ServerQuestion question, QTask task, String problem,
                       List<ServerTag> tags ) {

    public TagItem {
        tags = List.copyOf( tags ) ;
    }

    /** The question's numeric server id. */
    public int id() {
        return question.getId() ;
    }

    /** True if the question's images were found. */
    public boolean hasImages() {
        return task != null ;
    }

    /** True if the question can be sent to the models. */
    public boolean isSendable() {
        return task != null ;
    }

    public String type() {
        return question.getProblemType() ;
    }

    /** The explicit tags on it that are {@code catalogue}'s, in its order. */
    public List<String> existing( TagCatalogue catalogue ) {

        List<String> explicit = tags.stream()
                                    .filter( ServerTag::explicit )
                                    .map( ServerTag::tagText )
                                    .toList() ;
        return catalogue.tags().stream()
                        .map( CatalogueTag::tagText )
                        .filter( explicit::contains )
                        .toList() ;
    }

    @Override
    public String toString() {
        return question.getId() + " " + question.getQuestionId() ;
    }
}
