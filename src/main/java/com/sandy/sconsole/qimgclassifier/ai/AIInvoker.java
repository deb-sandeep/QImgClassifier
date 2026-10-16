package com.sandy.sconsole.qimgclassifier.ai;

import com.sandy.sconsole.qimgclassifier.model.AIAgent;
import com.sandy.sconsole.qimgclassifier.model.AttemptRequest;
import com.sandy.sconsole.qimgclassifier.model.AttemptTranscript;
import com.sandy.sconsole.qimgclassifier.model.QTask;
import com.sandy.sconsole.qimgclassifier.model.RateLimitNotice;
import com.sandy.sconsole.qimgclassifier.model.Subject;
import com.sandy.sconsole.qimgclassifier.model.TokenUsage;
import com.sandy.sconsole.qimgclassifier.model.Topic;
import com.sandy.sconsole.qimgclassifier.model.TopicRepo;
import com.sandy.sconsole.qimgclassifier.util.ImageCollator;
import com.sandy.sconsole.qimgclassifier.util.QClassifierConfig;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends single classification attempts to the configured
 * {@link AIProcessor}s, and keeps each processor's rate limits and the
 * pauses of models that reported a rate limit of their own. Which
 * processor gets which question, and when to retry, is decided by the caller
 * (the task scheduler).
 * <p>
 * Prompts come from classpath templates - {@code qclassifier.chemistry.prompt}
 * for Chemistry, {@code qclassifier.prompt} otherwise - with
 * {@code {{SUBJECT_SYLLABUS}}} replaced by the subject's syllabus reference
 * and {@code {{TOPIC_LIST_JSON}}} by its topic list. They are built once per
 * subject.
 * Thread-safe.
 */
@Slf4j
public class AIInvoker {

    /**
     * Reads replies and writes topic lists. Fields a reply adds beyond the
     * known ones are ignored: models echo the topic list's {@code branch}
     * (Chemistry), and only {@code id} and {@code match_probability} matter.
     */
    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy( PropertyNamingStrategies.SNAKE_CASE )
            .configure( DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false ) ;

    /**
     * Complete prompt per subject; {@link Subject#UNKNOWN} gets the default
     * template with empty placeholders.
     */
    private final Map<Subject,String> promptBySubject =
            new EnumMap<>( Subject.class ) ;

    private final List<AIProcessor> processors ;

    /**
     * Rate bookkeeping per processor (same index as {@link #processors});
     * guarded by {@code this}.
     */
    private final RateLimiter[] limiters ;

    /**
     * Per processor (same index), the reason given when it was last
     * suspended as a whole; guarded by {@code this}.
     */
    private final String[] suspendReasons ;

    /**
     * Per processor (same index), model -> its pause after a rate limit on it
     * alone; guarded by {@code this}.
     */
    private final List<Map<String,Pause>> modelPauses = new ArrayList<>() ;

    /** A model not asked before {@code until} (epoch ms), and why. */
    private record Pause( long until, String reason ) {}

    /**
     * Where the requests of processors with a per-day limit are kept across
     * restarts; null to keep them in memory only.
     */
    private final RequestHistory history ;

    /**
     * Requests from the history file for processors not configured now, kept
     * so saving does not lose them; guarded by {@code this}.
     */
    private final Map<String,List<Long>> otherHistory = new HashMap<>() ;

    /** Told when an {@link #attempt} starts and ends. */
    @FunctionalInterface
    public interface AttemptListener {

        /**
         * {@code attempt} (1-based, across all processors) is about to be sent
         * to {@code model} on {@code processor}.
         */
        void attemptStarted( AIProcessor processor, String model, int attempt ) ;

        /** The request went out; {@code request} has what was sent. */
        default void requestSent( AttemptRequest request ) {}

        /**
         * An attempt ended; {@code transcript} has what was sent and received.
         */
        default void attemptFinished( AttemptTranscript transcript ) {}
    }

    /** Outcome of an {@link #attempt}. */
    @Value
    @AllArgsConstructor( access = AccessLevel.PRIVATE )
    public static class InvocationResult {

        /**
         * The model's recommendations, best first as returned; null on failure.
         */
        List<Topic> topics ;

        /** The attempt's 1-based number for the question. */
        int attempts ;

        /** Short reason for the failure; null on success. */
        String failureReason ;

        /** True if the model's reply was obtained and parsed. */
        public boolean isSuccess() { return topics != null ; }

        static InvocationResult success( List<Topic> topics, int attempts ) {
            return new InvocationResult( topics, attempts, null ) ;
        }

        static InvocationResult failure( int attempts, String reason ) {
            return new InvocationResult( null, attempts, reason ) ;
        }
    }

    /**
     * Rate limits are kept in memory only.
     *
     * @throws IllegalArgumentException if {@code processors} is empty
     */
    public AIInvoker( List<AIProcessor> processors ) {
        this( processors, null ) ;
    }

    /**
     * @param historyFile where the request times behind the per-day limits
     *        are kept across restarts (read now, rewritten on every request);
     *        null to keep them in memory only
     * @throws IllegalArgumentException if {@code processors} is empty
     */
    public AIInvoker( List<AIProcessor> processors, File historyFile ) {

        if( processors.isEmpty() ) {
            throw new IllegalArgumentException(
                    "At least one AI processor is required" ) ;
        }
        this.processors = List.copyOf( processors ) ;
        this.limiters   = processors.stream()
                                    .map( p -> new RateLimiter(
                                            p.getSettings().getRateLimitRpm(),
                                            p.getSettings().getRateLimitRpd() ) )
                                    .toArray( RateLimiter[]::new ) ;

        this.suspendReasons = new String[ processors.size() ] ;
        processors.forEach( p -> modelPauses.add( new HashMap<>() ) ) ;
        this.history = historyFile == null ? null
                                           : new RequestHistory( historyFile ) ;
        restoreHistory() ;

        String defaultTemplate   = loadResource( "/qclassifier.prompt" ) ;
        String chemistryTemplate = loadResource( "/qclassifier.chemistry.prompt" ) ;

        for( Subject subject : Subject.values() ) {
            String template = subject == Subject.CHEMISTRY ? chemistryTemplate
                                                           : defaultTemplate ;
            promptBySubject.put( subject, buildPrompt( template, subject ) ) ;
        }
    }

    /** Builds the processors enabled in {@code config}. */
    public static AIInvoker fromConfig( QClassifierConfig config ) {

        List<AIProcessor> ps = new ArrayList<>() ;
        QClassifierConfig.Target gemma      = config.getGemma() ;
        QClassifierConfig.Target openRouter = config.getOpenRouter() ;

        if( gemma.isEnabled() ) {
            ps.add( new OllamaProcessor( config.getGemmaServerUrl(), gemma ) ) ;
            log.info( "AI processor: gemma @ {} - {}",
                      config.getGemmaServerUrl(), gemma ) ;
        }
        if( openRouter.isEnabled() ) {
            String apiKey = config.getOpenRouterApiKey() ;
            if( apiKey == null ) {
                log.error( "OpenRouter is enabled but no API key was found " +
                           "- skipping it" ) ;
            }
            else {
                ps.add( new OpenRouterProcessor( apiKey, openRouter,
                        config.isOpenRouterPromptCaching() ) ) ;
                log.info( "AI processor: openrouter - {}", openRouter ) ;
            }
        }
        return new AIInvoker( ps, config.getRequestHistoryFile() ) ;
    }

    /** A question's image, encoded and ready to send. */
    @Value
    public static class PreparedImage {
        String base64 ;
        String mimeType ;
    }

    /** The configured processors, in a fixed order. */
    public List<AIProcessor> getProcessors() { return processors ; }

    /**
     * The question's image, base64 encoded; a multi-part question's images are
     * stacked into one so the model sees the whole question.
     *
     * @throws IOException if an image cannot be read or stacked
     */
    public PreparedImage prepareImage( QTask task ) throws IOException {

        List<File> files = task.getImgFiles() ;
        byte[]     bytes = ImageCollator.collatedBytes( files ) ;

        return new PreparedImage( Base64.getEncoder().encodeToString( bytes ),
                                  ImageCollator.collatedMimeType( files ) ) ;
    }

    /**
     * Send one request for {@code task} to {@code model} on
     * {@code processor} and parse the reply. {@code onAttempt} is told before
     * the request goes out and gets the transcript afterwards. Does not
     * record the request against the processor's rate limits; see
     * {@link #recordUse}.
     *
     * @param attempt the attempt's 1-based number for the question
     * @return the topics, or the failure reason
     * @throws AIProcessor.RateLimitException if the backend reported a rate
     *         limit; the caller should {@link #suspend} the model
     */
    public InvocationResult attempt( AIProcessor processor, String model,
                                     QTask task, PreparedImage image,
                                     int attempt, AttemptListener onAttempt )
            throws AIProcessor.RateLimitException {

        onAttempt.attemptStarted( processor, model, attempt ) ;
        return attemptInvoke( processor, model, task,
                              promptBySubject.get( task.getSubject() ),
                              image.getBase64(), image.getMimeType(), attempt,
                              onAttempt ) ;
    }

    // ---- rate limits ---------------------------------------------------

    /**
     * Epoch ms from which {@code processor} may take its next request: now or
     * earlier if it is free to, later while a rate window is full, it is
     * suspended, or every one of its models is paused.
     */
    public synchronized long availableAt( AIProcessor processor ) {

        int  p   = processors.indexOf( processor ) ;
        long now = System.currentTimeMillis() ;
        long modelFreeAt = Long.MAX_VALUE ;

        for( String model : processor.getModels() ) {
            modelFreeAt = Math.min( modelFreeAt, pausedUntil( p, model ) ) ;
        }
        return Math.max( limiters[p].availableAt( now ), modelFreeAt ) ;
    }

    /**
     * Model for a question's {@code n}-th attempt on {@code processor}: the
     * one {@link AIProcessor#modelForAttempt} names, or, while that one is
     * paused after a rate limit, the next one in the list that is not.
     *
     * @return null if every model of the processor is paused
     */
    public synchronized String modelFor( AIProcessor processor, int n ) {

        int          p      = processors.indexOf( processor ) ;
        List<String> models = processor.getModels() ;
        int          first  = models.indexOf( processor.modelForAttempt( n ) ) ;
        long         now    = System.currentTimeMillis() ;

        for( int i = 0 ; i < models.size() ; i++ ) {
            String model = models.get( ( first + i ) % models.size() ) ;
            if( pausedUntil( p, model ) <= now ) return model ;
        }
        return null ;
    }

    /** Epoch ms until which {@code model} is paused; 0 if it is not. */
    private long pausedUntil( int p, String model ) {
        Pause pause = modelPauses.get( p ).get( model ) ;
        return pause != null ? pause.until() : 0 ;
    }

    /**
     * The processors, and models of otherwise free processors, that may not
     * be sent requests right now, with why and until when.
     */
    public synchronized List<RateLimitNotice> rateLimitNotices() {

        long                  now     = System.currentTimeMillis() ;
        List<RateLimitNotice> notices = new ArrayList<>() ;

        for( int p = 0 ; p < processors.size() ; p++ ) {
            AIProcessor processor = processors.get( p ) ;
            AIAgent     agent     = processor.getAgent() ;
            long        until     = availableAt( processor ) ;

            if( until > now ) {
                String reason = limiters[p].isSuspended( now )
                              ? suspendReasons[p]
                              : limiters[p].windowReason( now ) ;
                if( reason == null ) reason = "every model is rate limited" ;
                notices.add( new RateLimitNotice( agent, null, reason, until ) ) ;
                continue ;
            }
            modelPauses.get( p ).forEach( ( model, pause ) -> {
                if( pause.until() > now ) {
                    notices.add( new RateLimitNotice( agent, model,
                                                      pause.reason(),
                                                      pause.until() ) ) ;
                }
            } ) ;
        }
        return notices ;
    }

    /**
     * Count a request sent to {@code processor} against its rate limits, and
     * save it to the history file if the processor has a per-day limit.
     */
    public synchronized void recordUse( AIProcessor processor ) {

        long now = System.currentTimeMillis() ;
        limiters[ processors.indexOf( processor ) ].record( now ) ;
        if( history != null && hasDailyLimit( processor ) ) saveHistory( now ) ;
    }

    private static boolean hasDailyLimit( AIProcessor processor ) {
        return processor.getSettings().getRateLimitRpd() > 0 ;
    }

    /**
     * Load today's (UTC) requests of each processor with a per-day limit
     * from the history file into its rate windows.
     */
    private synchronized void restoreHistory() {

        if( history == null ) return ;
        long now = System.currentTimeMillis() ;
        Map<String,List<Long>> saved = history.load( now ) ;

        for( int p = 0 ; p < processors.size() ; p++ ) {
            AIProcessor processor = processors.get( p ) ;
            List<Long>  times     = saved.remove( processor.getName() ) ;

            if( times == null || !hasDailyLimit( processor ) ) continue ;
            limiters[p].restore( times, now ) ;
            log.info( "{}: {} of {} requests per day already used",
                      processor.getName(), times.size(),
                      processor.getSettings().getRateLimitRpd() ) ;
        }
        otherHistory.putAll( saved ) ;
    }

    /** Under {@code this}: rewrite the history file. */
    private void saveHistory( long now ) {

        Map<String,List<Long>> all = new HashMap<>( otherHistory ) ;
        for( int p = 0 ; p < processors.size() ; p++ ) {
            AIProcessor processor = processors.get( p ) ;
            if( hasDailyLimit( processor ) ) {
                all.put( processor.getName(), limiters[p].starts( now ) ) ;
            }
        }
        history.save( all, now ) ;
    }

    /**
     * Stop asking {@code model} on {@code processor} after it reported a rate
     * limit, or every model of the processor if the limit covers them all
     * ({@link AIProcessor.RateLimitScope}): for the time the backend asked
     * for, else for the processor's configured cooldown.
     */
    public void suspend( AIProcessor processor, String model,
                         AIProcessor.RateLimitException e ) {

        Duration pause = e.getRetryAfter() != null
                       ? e.getRetryAfter()
                       : processor.getSettings().getRateLimitCooldown() ;
        long until = System.currentTimeMillis() + pause.toMillis() ;
        int  p     = processors.indexOf( processor ) ;

        boolean wholeProcessor =
                e.getScope() == AIProcessor.RateLimitScope.PROCESSOR ;
        String  reason = e.getExplanation() != null ? e.getExplanation()
                                                    : e.getMessage() ;

        synchronized( this ) {
            if( wholeProcessor ) {
                limiters[p].suspendUntil( until ) ;
                suspendReasons[p] = reason ;
            }
            else {
                modelPauses.get( p ).merge(
                        model, new Pause( until, reason ),
                        ( a, b ) -> new Pause( Math.max( a.until(), b.until() ),
                                               b.reason() ) ) ;
            }
        }
        log.warn( "{} - not sending to {} for {}s", e.getMessage(),
                  wholeProcessor ? processor.getName() : model,
                  pause.toSeconds() ) ;
    }

    // ---- one attempt ---------------------------------------------------

    /**
     * Send one request and parse the reply. Whatever the outcome, the
     * transcript of the exchange goes to {@code onAttempt}.
     *
     * @throws AIProcessor.RateLimitException if the backend reported a rate
     *         limit
     */
    private InvocationResult attemptInvoke( AIProcessor processor, String model,
                                            QTask task, String prompt,
                                            String image, String mimeType,
                                            int attempt, AttemptListener onAttempt )
            throws AIProcessor.RateLimitException {

        long       startTime   = System.currentTimeMillis() ;
        String     rawResponse = null ;
        String     failure     = null ;
        boolean    rateLimited = false ;
        TokenUsage usage       = null ;

        AttemptRequest request = new AttemptRequest(
                task, processor.getAgent(), model, attempt, mimeType, startTime,
                prompt ) ;
        onAttempt.requestSent( request ) ;

        try {
            log.debug( "\t  Sending {} to {} {} (attempt {}, prompt {} chars)",
                       task, processor.getName(), model, attempt,
                       prompt.length() ) ;
            AIProcessor.Reply reply =
                    processor.complete( model, prompt, image, mimeType ) ;

            rawResponse = reply.text() ;
            usage       = reply.usage() ;
            String cleaned = cleanResponse( rawResponse ) ;

            try {
                List<Topic> topics = parseTopics( cleaned, task ) ;
                return InvocationResult.success( topics, attempt ) ;
            }
            catch( JsonProcessingException e ) {
                log.error( "Error parsing {} response (attempt {}): {}",
                           processor.getName(), attempt, cleaned, e ) ;
                failure = "the model's reply was not a topic list" ;
                return InvocationResult.failure( attempt, failure ) ;
            }
        }
        catch( AIProcessor.RateLimitException e ) {
            rawResponse = e.getDetail() ;
            failure     = e.getMessage() ;
            rateLimited = true ;
            usage       = e.getUsage() ;

            throw e ;
        }
        catch( AIProcessor.ProcessorException e ) {
            rawResponse = e.getDetail() ;
            failure     = e.getMessage() ;
            usage       = e.getUsage() ;

            return InvocationResult.failure( attempt, failure ) ;
        }
        finally {
            long elapsed = System.currentTimeMillis() - startTime ;
            log.info( "{} attempt {} on {} {} took {}.{}s - {}", task, attempt,
                      processor.getName(), model, elapsed / 1000,
                      elapsed % 1000 / 100,
                      usage != null ? usage.describe() : "no token count" ) ;
            onAttempt.attemptFinished( new AttemptTranscript(
                    request, elapsed, rawResponse, failure, rateLimited,
                    usage ) ) ;
        }
    }

    /**
     * The topics in a reply's JSON array. An entry that cannot be read (e.g.
     * {@code "id" : "N/A"} for a topic not in the list) is skipped with a
     * warning; the rest still count.
     *
     * @throws JsonProcessingException if the reply is not a JSON array, or no
     *         entry in it can be read
     */
    List<Topic> parseTopics( String json, QTask task )
            throws JsonProcessingException {

        JsonNode root = mapper.readTree( json ) ;
        if( root == null || !root.isArray() ) {
            throw new JsonMappingException( null, "not a JSON array" ) ;
        }

        List<Topic> topics = new ArrayList<>() ;
        for( JsonNode entry : root ) {
            try {
                topics.add( mapper.treeToValue( entry, Topic.class ) ) ;
            }
            catch( JsonProcessingException e ) {
                log.warn( "[{}] Skipping an unreadable topic in the reply: {}",
                          task, entry ) ;
            }
        }
        if( topics.isEmpty() ) {
            throw new JsonMappingException( null, "no readable topic" ) ;
        }
        return topics ;
    }

    /**
     * Strip markdown fences some models add despite the prompt, and wrap a lone
     * object in an array.
     */
    static String cleanResponse( String response ) {
        String s = response.trim() ;
        if( s.startsWith( "```" ) ) {
            int firstNewline = s.indexOf( '\n' ) ;
            s = firstNewline < 0 ? "" : s.substring( firstNewline + 1 ) ;

            if( s.endsWith( "```" ) ) s = s.substring( 0, s.length() - 3 ) ;
            s = s.trim() ;
        }
        if( s.startsWith( "{" ) && s.endsWith( "}" ) ) s = "[" + s + "]" ;
        return s ;
    }

    // ---- prompt building -----------------------------------------------

    private String buildPrompt( String template, Subject subject ) {

        String syllabus = subject.hasSyllabus()
                ? loadResource( "/qclassifier." + subject.name().toLowerCase()
                                + ".syllabus.txt" )
                : "" ;
        return template
                .replace( "{{SUBJECT_SYLLABUS}}", syllabus )
                .replace( "{{TOPIC_LIST_JSON}}", topicListJson( subject ) ) ;
    }

    /**
     * The subject's topics as a flat JSON array of {@code {topic_name, id}}.
     * The syllabus reference already groups topics by branch; keeping the
     * id-copy task flat matches the format that works reliably for
     * Physics / Maths. Chemistry additionally carries the branch name so the
     * model can reason branch-first.
     */
    private String topicListJson( Subject subject ) {

        List<Topic> topics = TopicRepo.instance().getTopics( subject ) ;
        if( topics.isEmpty() ) return "" ;

        ArrayNode topicsArray = mapper.createArrayNode() ;
        for( Topic topic : topics ) {
            ObjectNode node = topicsArray.addObject()
                                         .put( "topic_name", topic.getTopicName() ) ;
            if( subject == Subject.CHEMISTRY ) {
                node.put( "branch", topic.getSection() ) ;
            }
            node.put( "id", topic.getId() ) ;
        }
        try {
            return mapper.writeValueAsString( topicsArray ) ;
        }
        catch( JsonProcessingException e ) {
            log.error( "Error building topic list JSON for {}", subject, e ) ;
            return "" ;
        }
    }

    /**
     * A UTF-8 classpath resource; "" (and an error logged) if it is missing or
     * unreadable.
     */
    private static String loadResource( String path ) {

        try( InputStream is = AIInvoker.class.getResourceAsStream( path ) ) {
            if( is == null ) {
                log.error( "Resource {} not found", path ) ;
                return "" ;
            }
            return new String( is.readAllBytes(), StandardCharsets.UTF_8 ) ;
        }
        catch( IOException e ) {
            log.error( "Error loading resource {}", path, e ) ;
            return "" ;
        }
    }
}
