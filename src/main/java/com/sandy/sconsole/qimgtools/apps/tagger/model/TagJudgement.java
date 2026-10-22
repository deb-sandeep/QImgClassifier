package com.sandy.sconsole.qimgtools.apps.tagger.model;

import com.sandy.sconsole.qimgtools.apps.tagger.check.AnswerChecker;

import java.util.HashSet;
import java.util.List;

/**
 * Where the tiers' replies leave a question: the key decides. A tier whose
 * answer matches the key has its tags accepted, unless it proposes a new
 * tag (in {@link TagMode#REVIEW_GAPS}; the run then pauses for the user) or
 * the question already carries different hand tags. Otherwise the question
 * goes to the user: both tiers gave the same wrong answer (the key is
 * suspect), or neither answered right.
 *
 * @param status   {@link TaggerStatus#ACCEPTED_T1}, {@code ACCEPTED_T2},
 *                 {@code GAP_PROPOSED} or {@code NEEDS_HUMAN}
 * @param reason   why it needs a human; null otherwise
 * @param accepted the tier whose answer matched the key; null if none did
 */
public record TagJudgement( TaggerStatus status, HandOffReason reason,
                            TierReply accepted ) {

    /**
     * Judge {@code item} on {@code opinions}.
     *
     * @param gapSettled true once the user has decided on the accepted
     *        tier's gap proposal, so it pauses nothing again
     */
    public static TagJudgement of( TagItem item, TagOpinions opinions,
                                   TagMode mode, OfferedSet offered,
                                   boolean gapSettled ) {

        String    type = item.type() ;
        String    key  = item.key() ;
        TierReply t1   = opinions.tier1() ;
        TierReply t2   = opinions.tier2() ;
        TierReply ok   = AnswerChecker.matches( type, key, t1.answer() ) ? t1
                       : t2 != null
                         && AnswerChecker.matches( type, key, t2.answer() ) ? t2
                       : null ;

        if( ok == null ) {
            boolean agree = t2 != null
                            && AnswerChecker.same( type, t1.answer(),
                                                   t2.answer() ) ;
            return handOff( agree ? HandOffReason.KEY_SUSPECT
                                  : HandOffReason.UNSOLVED, null ) ;
        }
        if( mode == TagMode.REVIEW_GAPS && !gapSettled
            && ok.reply().gap() != null ) {
            return new TagJudgement( TaggerStatus.GAP_PROPOSED, null, ok ) ;
        }

        List<String> existing = item.existing( offered ) ;
        if( !existing.isEmpty() && !sameTags( existing, ok, offered,
                                              item.dependent() ) ) {
            return handOff( HandOffReason.EXISTING_DIFFERS, ok ) ;
        }
        return new TagJudgement( ok.tier() == 1 ? TaggerStatus.ACCEPTED_T1
                                                : TaggerStatus.ACCEPTED_T2,
                                 null, ok ) ;
    }

    /** Tier 1's replies could not be used at all. */
    public static TagJudgement invalidOutput() {
        return handOff( HandOffReason.INVALID_OUTPUT, null ) ;
    }

    private static TagJudgement handOff( HandOffReason reason,
                                         TierReply accepted ) {
        return new TagJudgement( TaggerStatus.NEEDS_HUMAN, reason, accepted ) ;
    }

    /**
     * True if {@code reply}'s tags that the run may replace are exactly
     * {@code existing}.
     */
    private static boolean sameTags( List<String> existing, TierReply reply,
                                     OfferedSet offered, boolean dependent ) {

        List<String> replaceable = offered.replaceable( dependent ).stream()
                                          .map( CatalogueTag::tagText )
                                          .toList() ;
        return new HashSet<>( existing ).equals( new HashSet<>(
                reply.reply().tags().stream()
                     .filter( replaceable::contains ).toList() ) ) ;
    }
}
