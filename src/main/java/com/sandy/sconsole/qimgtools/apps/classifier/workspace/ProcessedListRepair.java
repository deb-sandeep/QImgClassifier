package com.sandy.sconsole.qimgtools.apps.classifier.workspace;

import com.sandy.sconsole.qimgtools.core.util.FileHelper;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Re-syncs the {@link ProcessedList} with the sources' topic maps. Every
 * source in the source list - plus any extra name already in the processed
 * list - is checked: a listed source with a question that is unclassified
 * (missing from its {@code ai-topic-map.json}) or has no topic in its
 * {@code question-info.json} is removed, an unlisted source whose questions
 * are all classified and have a topic is added. A source whose folder cannot
 * be found is left as it is. A source synced to the SConsole server counts
 * as complete before its questions are looked at. The classifier gives a
 * removed source's classified questions their topics when it next loads the
 * source.
 */
@Slf4j
@NoArgsConstructor( access = AccessLevel.PRIVATE )
public final class ProcessedListRepair {

    /**
     * Outcome of one repair run; filled in on the repair thread, read once the
     * run is over.
     */
    public static final class Report {
        int sourcesChecked ;
        int questionsChecked ;
        int imagesChecked ;

        /** Questions unclassified or without a topic. */
        int questionsUnassociated ;

        /** Questions classified but without a topic. */
        int questionsWithoutTopic ;
        final List<String> added = new ArrayList<>() ;

        /**
         * Sources taken off the processed list (they have unclassified
         * questions, or questions without a topic, again).
         */
        @Getter
        final List<String> removed = new ArrayList<>() ;

        /** Synced to the server, so complete without checking its questions. */
        int serverSynced ;

        /** Complete and already listed. */
        final List<String> keptListed = new ArrayList<>() ;

        /** Incomplete and not listed. */
        final List<String> keptUnlisted = new ArrayList<>() ;

        /** Folder missing - entry left untouched. */
        final List<String> notFound = new ArrayList<>() ;

        /** No question images (treated as incomplete). */
        final List<String> noImages         = new ArrayList<>() ;
        String error ;


        /** True if the processed list needed changing. */
        public boolean      hasChanges() {
            return !added.isEmpty() || !removed.isEmpty() ;
        }

        /**
         * Multi-line, fixed-width summary for the log and the report dialog.
         */
        public String toText() {

            StringBuilder sb = new StringBuilder() ;
            if( error != null ) {
                sb.append( "ERROR - processed list NOT updated: " )
                  .append( error ).append( "\n\n" ) ;
            }
            int untouched = keptListed.size() + keptUnlisted.size() ;
            appendStat( sb, "Sources checked", sourcesChecked,
                        serverSynced + " server synced" ) ;
            appendStat( sb, "Questions checked", questionsChecked,
                        questionsUnassociated + " not associated, of them " +
                        questionsWithoutTopic + " classified" ) ;
            appendStat( sb, "Images checked", imagesChecked, null ) ;
            appendStat( sb, "Added to processed list", added.size(), null ) ;
            appendStat( sb, "Removed from list", removed.size(), null ) ;
            appendStat( sb, "Untouched", untouched,
                        keptListed.size() + " complete & listed, " +
                        keptUnlisted.size() + " incomplete & unlisted" ) ;
            appendStat( sb, "Folder not found", notFound.size(), null ) ;
            appendStat( sb, "No question images", noImages.size(), null ) ;
            appendNames( sb, "Added", added ) ;
            appendNames( sb, "Removed", removed ) ;
            appendNames( sb, "Folder not found (entry left as is)", notFound ) ;
            appendNames( sb, "No question images", noImages ) ;
            return sb.toString() ;
        }

        /**
         * One aligned {@code label : value  (note)} line; {@code note} may be
         * null.
         */
        private static void appendStat( StringBuilder sb, String label,
                                        int value, String note ) {
            sb.append( String.format( "%-25s: %d", label, value ) ) ;
            if( note != null ) sb.append( "  (" ).append( note ).append( ')' ) ;
            sb.append( System.lineSeparator() ) ;
        }

        private static void appendNames( StringBuilder sb, String title,
                                         List<String> names ) {
            if( names.isEmpty() ) return ;
            sb.append( '\n' ).append( title ).append( ":\n" ) ;
            names.forEach( n -> sb.append( "  " ).append( n ).append( '\n' ) ) ;
        }
    }

    /**
     * Immutable snapshot of a run in flight, safe to hand to another thread.
     */
    @Getter
    public static final class Progress {
        private final int done ;
        private final int total ;

        /** Source just checked; null before the first. */
        private final String current ;
        private final int    added ;
        private final int    removed ;
        private final int    notFound ;
        private final int    questionsChecked ;
        private final int    questionsUnassociated ;

        Progress( int done, int total, String current, Report r ) {

            this.done                  = done ;
            this.total                 = total ;
            this.current               = current ;
            this.added                 = r.added.size() ;
            this.removed               = r.removed.size() ;
            this.notFound              = r.notFound.size() ;
            this.questionsChecked      = r.questionsChecked ;
            this.questionsUnassociated = r.questionsUnassociated ;
        }

        /** Sources still to check. */
        public int remaining() { return total - done ; }
    }

    /** Called on the repair thread after each source is checked. */
    public interface ProgressListener {
        void progress( Progress p ) ;
    }

    /**
     * Check every source and rewrite the processed list if anything changed.
     * Blocking; walks every source folder.
     *
     * @param qBankDir       root folder the sources are searched under
     * @param sources        the configured source names
     * @param questionFilter debug question filter (the
     *         {@code question.filter} setting)
     * @param progress       called on this thread before the first and after
     *         each source
     * @return the report; {@code error} is set if the list could not be
     *         rewritten
     */
    public static Report run( File qBankDir, List<String> sources,
                              ProcessedList processedList, String questionFilter,
                              ProgressListener progress ) {

        Report r = new Report() ;
        Set<String> processed = processedList.load() ;

        Set<String> toCheck = new LinkedHashSet<>( sources ) ;
        toCheck.addAll( processed ) ;

        Set<String> updated = new LinkedHashSet<>( processed ) ;
        int total = toCheck.size() ;
        int done  = 0 ;

        progress.progress( new Progress( 0, total, null, r ) ) ;
        for( String srcName : toCheck ) {
            checkSource( qBankDir, questionFilter, srcName, processed, updated, r ) ;
            progress.progress( new Progress( ++done, total, srcName, r ) ) ;
        }

        if( r.hasChanges() ) {
            try {
                processedList.rewrite( updated ) ;
            }
            catch( Exception e ) {
                log.error( "Error rewriting processed list file {}",
                           processedList.getFile(), e ) ;
                r.error = e.getMessage() ;
            }
        }
        log.info( "Processed list repair:\n{}", r.toText() ) ;
        return r ;
    }

    /**
     * Check one source's completeness and record the outcome in {@code r} /
     * {@code updated}.
     */
    private static void checkSource( File qBankDir, String questionFilter,
                                     String srcName, Set<String> processed,
                                     Set<String> updated, Report r ) {

        boolean listed = processed.contains( srcName ) ;
        File srcDir = FileHelper.findDir( qBankDir, srcName ) ;

        if( srcDir == null ) {
            r.notFound.add( srcName ) ;
            return ;
        }
        r.sourcesChecked++ ;

        if( QuestionTopicAssociator.isServerSynced( srcDir ) ) {
            r.serverSynced++ ;
            record( srcName, listed, true, updated, r ) ;
            return ;
        }

        Map<String,List<File>> groups =
                FileHelper.getQuestionImgGroups( srcDir, questionFilter ) ;
        boolean complete ;

        if( groups == null || groups.isEmpty() ) {
            r.noImages.add( srcName ) ;
            complete = false ;
        }
        else {
            Set<String> classified =
                    WorkspaceTopicMap.classifiedQuestionIds( srcDir ) ;
            Set<String> assigned   =
                    QuestionTopicAssociator.assignedTopicIds( srcDir ).keySet() ;
            int unassociated = 0 ;

            for( Map.Entry<String,List<File>> e : groups.entrySet() ) {
                boolean isClassified = classified.contains( e.getKey() ) ;
                boolean hasTopic     = assigned.contains( e.getKey() ) ;

                r.questionsChecked++ ;
                r.imagesChecked += e.getValue().size() ;
                if( !isClassified || !hasTopic ) unassociated++ ;
                if( isClassified && !hasTopic ) r.questionsWithoutTopic++ ;
            }
            r.questionsUnassociated += unassociated ;
            complete = unassociated == 0 ;
        }
        record( srcName, listed, complete, updated, r ) ;
    }

    /** Add or remove the source as its completeness asks, and report it. */
    private static void record( String srcName, boolean listed,
                                boolean complete, Set<String> updated,
                                Report r ) {

        if( complete && !listed ) {
            updated.add( srcName ) ;
            r.added.add( srcName ) ;
        }
        else if( !complete && listed ) {
            updated.remove( srcName ) ;
            r.removed.add( srcName ) ;
        }
        else if( complete ) r.keptListed.add( srcName ) ;
        else                r.keptUnlisted.add( srcName ) ;
    }
}
