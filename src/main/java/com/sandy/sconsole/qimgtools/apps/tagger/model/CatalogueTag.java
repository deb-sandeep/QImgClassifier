package com.sandy.sconsole.qimgtools.apps.tagger.model;

/**
 * A tag of the scoped topic, as the server holds it
 * ({@code GET /Master/Tag/Topic/{id}}).
 *
 * @param id      the server's tag id
 * @param tagText e.g. {@code moving-medium}
 * @param hint    when to apply it; null if it has none
 */
public record CatalogueTag( int id, String tagText, String hint ) {}
