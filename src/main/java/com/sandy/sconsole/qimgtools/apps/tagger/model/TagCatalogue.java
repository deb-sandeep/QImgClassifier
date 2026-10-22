package com.sandy.sconsole.qimgtools.apps.tagger.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The tags a topic's prompt may offer: the topic's tags with an AI
 * association hint (setup and concept), then Foundation Mathematics' hinted
 * math tags, as the server holds them now. Legacy tags (no hint) are never
 * part of it. Immutable.
 */
public final class TagCatalogue {

    /** Foundation Mathematics: its tags are every topic's math axis. */
    public static final int FOUNDATION_MATHS = 124 ;

    private final int                       topicId ;
    private final List<CatalogueTag>        tags ;
    private final Map<String,CatalogueTag>  byText     = new LinkedHashMap<>() ;
    private final Map<String,CatalogueTag>  byFullName = new LinkedHashMap<>() ;

    /**
     * @param topicTags the topic's tags, hinted or not, in the server's order
     * @param mathTags  Foundation Mathematics' tags, hinted or not
     */
    public TagCatalogue( int topicId, List<CatalogueTag> topicTags,
                         List<CatalogueTag> mathTags ) {

        List<CatalogueTag> all = new ArrayList<>() ;

        this.topicId = topicId ;
        for( CatalogueTag t : topicTags ) {
            Axis a = t.axis() ;
            if( hinted( t ) && ( a == Axis.SETUP || a == Axis.CONCEPT ) ) {
                all.add( t ) ;
            }
        }
        for( CatalogueTag t : mathTags ) {
            if( hinted( t ) && t.axis() == Axis.MATH ) all.add( t ) ;
        }
        this.tags = List.copyOf( all ) ;
        for( CatalogueTag t : tags ) {
            byText.put( key( t.tagText() ), t ) ;
            if( t.fullName() != null ) byFullName.put( key( t.fullName() ), t ) ;
        }
    }

    private static boolean hinted( CatalogueTag t ) {
        return t.hint() != null && !t.hint().isBlank() ;
    }

    private static String key( String text ) {
        return text.trim().toLowerCase( Locale.ROOT ) ;
    }

    public int topicId() {
        return topicId ;
    }

    /** Every tag, the topic's first. */
    public List<CatalogueTag> tags() {
        return tags ;
    }

    /** The tags of {@code axis}, in order. */
    public List<CatalogueTag> tags( Axis axis ) {
        return tags.stream().filter( t -> t.axis() == axis ).toList() ;
    }

    /** The tag whose text is {@code text}, ignoring case; null if none. */
    public CatalogueTag get( String text ) {
        return text == null ? null : byText.get( key( text ) ) ;
    }

    /** The tag whose full name is {@code name}, ignoring case; null if none. */
    public CatalogueTag byFullName( String name ) {
        return name == null ? null : byFullName.get( key( name ) ) ;
    }

    /**
     * The sentence every hint of {@code axis} starts with (e.g. "Setup tag:
     * apply on presence of the component ..."): the longest prefix the hints
     * share, cut back to the end of a sentence; "" if they share none, or
     * the axis has a single tag.
     */
    public String axisRule( Axis axis ) {

        List<String> hints = tags( axis ).stream()
                                         .map( CatalogueTag::hint ).toList() ;
        if( hints.size() < 2 ) return "" ;

        String prefix = hints.get( 0 ) ;
        for( String h : hints ) {
            int n = 0 ;
            while( n < prefix.length() && n < h.length()
                   && prefix.charAt( n ) == h.charAt( n ) ) n++ ;
            prefix = prefix.substring( 0, n ) ;
        }
        int end = prefix.lastIndexOf( ". " ) ;
        return end < 0 ? "" : prefix.substring( 0, end + 1 ) ;
    }

    /** {@code t}'s hint less its axis' rule sentence. */
    public String ownHint( CatalogueTag t ) {

        String rule = axisRule( t.axis() ) ;
        String hint = t.hint().strip() ;
        return !rule.isEmpty() && hint.startsWith( rule )
               ? hint.substring( rule.length() ).strip() : hint ;
    }

    /**
     * A hash of every tag's id, text and hint: it changes when a tag is
     * added, renamed or re-hinted on the server, which makes an approved
     * prompt stale.
     */
    public String fingerprint() {

        StringBuilder sb = new StringBuilder() ;
        tags.stream()
            .sorted( Comparator.comparingInt( CatalogueTag::id ) )
            .forEach( t -> sb.append( t.id() ).append( '|' )
                             .append( t.tagText() ).append( '|' )
                             .append( t.hint().strip() ).append( '\n' ) ) ;
        return sha256( sb.toString() ).substring( 0, 16 ) ;
    }

    /** The SHA-256 of {@code text}, in hex. */
    public static String sha256( String text ) {
        try {
            MessageDigest md = MessageDigest.getInstance( "SHA-256" ) ;
            return HexFormat.of().formatHex(
                    md.digest( text.getBytes( StandardCharsets.UTF_8 ) ) ) ;
        }
        catch( NoSuchAlgorithmException e ) {
            throw new IllegalStateException( e ) ;
        }
    }
}
