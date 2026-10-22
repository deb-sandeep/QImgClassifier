package com.sandy.sconsole.qimgtools.apps.tagger.model;

/**
 * A tag to create on the server from an accepted gap proposal, as the user
 * edited it in the gap dialog.
 *
 * @param tagText     e.g. {@code c.friction.limiting}
 * @param fullName    the tag text with every abbreviation spelled out, as
 *                    many dot-separated segments
 * @param description a short definition for people
 * @param hint        when to apply it, less its axis' rule (which the
 *                    import puts before it)
 * @param color       {@code #RRGGBB}
 */
public record NewTag( String tagText, String fullName, String description,
                      String hint, String color ) {

    /** The tag's axis; null if its text has no axis prefix. */
    public Axis axis() {
        return Axis.of( tagText ) ;
    }
}
