package com.sandy.sconsole.qimgtools.core.sequence;

import com.sandy.sconsole.topicresolver.TopicResolver;
import com.sandy.sconsole.topicresolver.TopicResolver.Recommendation;
import com.sandy.sconsole.topicresolver.TopicResolver.Resolution;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Runs {@link TopicResolver} on the apps' own recommendation types, so both
 * apps choose a question's topic the same way QImgExtractor does.
 */
@Slf4j
@NoArgsConstructor( access = AccessLevel.PRIVATE )
public final class TopicChooser {

    /**
     * The item of {@code items} whose topic {@link TopicResolver} files the
     * question under.
     *
     * @param what       the question, for the log
     * @param id         an item's topic id
     * @param confidence an item's confidence, 0-100
     * @return null if {@code items} is empty
     * @throws IOException if the topic sequence file can't be read
     */
    public static <T> T choose( String what, List<T> items, ToIntFunction<T> id,
                                ToIntFunction<T> confidence ) throws IOException {

        if( items.isEmpty() ) return null ;

        List<Recommendation> recs = items.stream()
                .map( t -> new Recommendation( id.applyAsInt( t ),
                                               confidence.applyAsInt( t ) ) )
                .toList() ;
        Resolution r = TopicResolver.current().resolve( recs ) ;

        log.debug( "[{}] Topic {} - {}", what, r.topicId(), r.reason() ) ;
        for( T item : items ) {
            if( id.applyAsInt( item ) == r.topicId() ) return item ;
        }
        throw new IllegalStateException( "chose topic " + r.topicId() +
                                         ", which was not recommended" ) ;
    }
}
