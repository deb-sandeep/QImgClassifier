package com.sandy.sconsole.qimgtools.apps.tagger.model;

/**
 * What the models said about a question: tier 1's reply, and tier 2's when
 * tier 1 missed the key (or why it gave none).
 *
 * @param tier1        tier 1's reply
 * @param tier2        tier 2's reply; null if it wasn't asked or gave none
 * @param tier2Failure why tier 2, asked, gave no reply; null otherwise
 */
public record TagOpinions( TierReply tier1, TierReply tier2,
                           String tier2Failure ) {

    /** Tier 1 alone: its answer matched, so tier 2 wasn't asked. */
    public static TagOpinions of( TierReply tier1 ) {
        return new TagOpinions( tier1, null, null ) ;
    }

    /** True if tier 2 was asked. */
    public boolean escalated() {
        return tier2 != null || tier2Failure != null ;
    }

    /** The reply of tier {@code tier}; null if there is none. */
    public TierReply tier( int tier ) {
        return tier == 1 ? tier1 : tier2 ;
    }
}
