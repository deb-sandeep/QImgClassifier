package com.sandy.sconsole.qimgtools.apps.tagger.model;

/**
 * A tag the server holds that the tagger may offer to a model: one with an
 * AI association hint ({@code GET /Master/Tag/Topic/{id}}).
 *
 * @param id          the server's tag id
 * @param tagText     e.g. {@code s.connector.pulley}
 * @param fullName    the tag text with every abbreviation spelled out, e.g.
 *                    {@code setup.connector.pulley}
 * @param description a short definition for people
 * @param hint        the instruction the model gets: when to apply it
 * @param color       {@code #RRGGBB}
 * @param topicId     the topic it belongs to
 */
public record CatalogueTag( int id, String tagText, String fullName,
                            String description, String hint, String color,
                            int topicId ) {

    /** The tag's axis; null if its text has no axis prefix. */
    public Axis axis() {
        return Axis.of( tagText ) ;
    }
}
