package com.sandy.sconsole.qimgtools.apps.topicreviewer.util;

import com.sandy.sconsole.ai.config.AIConfig;
import com.sandy.sconsole.qimgtools.core.util.AIConfigs;
import com.sandy.sconsole.qimgtools.core.util.ConfigProperties;

import lombok.Getter;

import java.io.File;
import java.time.Duration;
import java.util.List;

/**
 * Settings of the topic reviewer, read from the
 * {@code /apps/topicreviewer/qtopicreview.properties} classpath resource (see
 * that file for what each key means). A JVM system property of the same
 * name overrides the file (see {@link ConfigProperties}).
 * <p>
 * Every key must be present ({@code review.topic.ids} may be blank); a
 * missing or malformed value fails {@link #load()} with an
 * {@link IllegalStateException} naming the key. The files the reviewer keeps
 * may be given relative to {@code workspace.dir}. The AI models come from the
 * JSON app config {@link #AI_CONFIG} and its parent (see
 * {@code AIConfigLoader}), which fail the same way.
 */
@Getter
public final class ReviewConfig {

    /** Classpath location of the configuration file. */
    public static final String RESOURCE =
            "/apps/topicreviewer/qtopicreview.properties" ;

    /** Location of the reviewer's AI config (models, roots, fallbacks). */
    public static final String AI_CONFIG =
            "classpath:/apps/topicreviewer/qtopicreview.ai.json" ;

    /** Base URL of the SConsole server, e.g. {@code http://localhost:8080}. */
    private final String   serverUrl ;
    private final Duration serverConnectTimeout ;
    private final Duration serverRequestTimeout ;

    /** Root folder searched (recursively) for the source folders. */
    private final File qBankDir ;

    /** Root of the reviewer's files and per-question transcripts. */
    private final File workspaceDir ;

    /** Model verdicts on questions not yet settled on the server. */
    private final File pendingVerdictsFile ;

    /** Every verdict and decision, appended as JSON lines. */
    private final File reviewAuditFile ;

    /** The search criteria last saved in the reviewer. */
    private final File reviewCriteriaFile ;

    /**
     * Start times of the day's requests, so the per-day limit survives a
     * restart.
     */
    private final File requestHistoryFile ;

    /** One CSV row per AI attempt with the tokens it used. */
    private final File usageLogFile ;

    /** Syllabuses whose questions are reviewed, e.g. "IIT Physics". */
    private final List<String> syllabusNames ;

    /**
     * Topic ids whose questions are reviewed; when not empty, used instead of
     * {@link #syllabusNames}.
     */
    private final List<Integer> topicIds ;

    /** Questions fetched per request to the review queue (1-100). */
    private final int fetchLimit ;

    /**
     * The models the review asks, from {@link #AI_CONFIG} or the file
     * {@code -Dai.app.config} names.
     */
    private final AIConfig ai ;

    private ReviewConfig( ConfigProperties props ) {

        serverUrl            = props.str( "server.url" ) ;
        serverConnectTimeout = props.seconds( "server.connect.timeout.seconds" ) ;
        serverRequestTimeout = props.seconds( "server.request.timeout.seconds" ) ;

        qBankDir            = props.path( "question.bank.dir" ) ;
        workspaceDir        = props.workspaceDir( "workspace.dir" ) ;
        pendingVerdictsFile = props.workspacePath( "pending.verdicts.file" ) ;
        reviewAuditFile     = props.workspacePath( "review.audit.file" ) ;
        reviewCriteriaFile  = props.workspacePath( "review.criteria.file" ) ;
        requestHistoryFile  = props.workspacePath( "request.history.file" ) ;
        usageLogFile        = props.workspacePath( "usage.log.file" ) ;

        syllabusNames = props.list( "review.syllabus.names" ) ;
        topicIds      = props.intList( "review.topic.ids" ) ;
        fetchLimit    = props.wholeNumber( "review.fetch.limit", 1, 100 ) ;

        if( syllabusNames.isEmpty() && topicIds.isEmpty() ) {
            throw props.invalid( "review.syllabus.names",
                                 "is blank, and so is review.topic.ids" ) ;
        }

        ai = AIConfigs.load( AI_CONFIG ) ;
    }

    /**
     * Read the settings from {@link #RESOURCE} and system properties.
     *
     * @throws IllegalStateException if the resource is missing or unreadable,
     *         or a key is missing or malformed
     */
    public static ReviewConfig load() {
        return new ReviewConfig( ConfigProperties.load( RESOURCE ) ) ;
    }
}
