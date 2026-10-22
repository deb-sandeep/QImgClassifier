package com.sandy.sconsole.qimgtools.apps.tagger.model;

import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;

import java.util.List;

/**
 * One question of a Load, as loaded. Immutable; the question's later state
 * (the tiers' replies, the user's decision, the server's answer) lives in
 * the tagger's table model.
 *
 * @param question    the question as the server sent it
 * @param task        its local images, ready for the models; null if they
 *                    could not be found
 * @param problem     why {@code task} is null; null otherwise
 * @param unsupported why its answer can't be checked (so it is never
 *                    sent); null if it can
 * @param dependent   true if it is another topic's question, loaded for
 *                    its open dependency on the scoped topic
 * @param tags        every tag on it now, explicit or implied
 */
public record TagItem( ServerQuestion question, QTask task, String problem,
                       String unsupported, boolean dependent,
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
        return task != null && unsupported == null ;
    }

    public String type() {
        return question.getProblemType() ;
    }

    public String key() {
        return question.getAnswer() ;
    }

    /**
     * The hand tags: the explicit tags on it that the run could replace
     * (see {@link OfferedSet#replaceable}), in offered order.
     */
    public List<String> existing( OfferedSet offered ) {

        List<String> explicit = tags.stream()
                                    .filter( ServerTag::explicit )
                                    .map( t -> t.tagText().toLowerCase() )
                                    .toList() ;
        return offered.replaceable( dependent ).stream()
                      .map( CatalogueTag::tagText )
                      .filter( t -> explicit.contains( t.toLowerCase() ) )
                      .toList() ;
    }

    @Override
    public String toString() {
        return question.getId() + " " + question.getQuestionId() ;
    }
}
