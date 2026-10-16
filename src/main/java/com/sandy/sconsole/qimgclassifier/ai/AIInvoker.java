package com.sandy.sconsole.qimgclassifier.ai;

import com.sandy.sconsole.qimgclassifier.model.AttemptTranscript;
import com.sandy.sconsole.qimgclassifier.model.QTask;
import com.sandy.sconsole.qimgclassifier.model.Subject;
import com.sandy.sconsole.qimgclassifier.model.Topic;
import com.sandy.sconsole.qimgclassifier.model.TopicRepo;
import com.sandy.sconsole.qimgclassifier.util.ImageCollator;
import com.sandy.sconsole.qimgclassifier.util.QClassifierConfig;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Classifies a question by sending it to one of several {@link AIProcessor}s.
 * Each processor has its own number of parallel slots; a request goes to the
 * least-loaded processor with a free slot, so adding processors adds
 * throughput. A failed attempt is retried, preferring a different processor,
 * until every processor has used up its per-question budget of
 * {@code 1 + getMaxRetries()} attempts.
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
     * Longest single wait in {@link #acquire}, so a stop request is seen
     * promptly.
     */
    private static final long MAX_WAIT_MS = 1000 ;

    private static final TypeReference<List<Topic>> TOPIC_LIST =
            new TypeReference<>() {} ;

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy( PropertyNamingStrategies.SNAKE_CASE ) ;

    /**
     * Complete prompt per subject; {@link Subject#UNKNOWN} gets the default
     * template with empty placeholders.
     */
    private final Map<Subject,String> promptBySubject =
            new EnumMap<>( Subject.class ) ;

    private final List<AIProcessor> processors ;

    /**
     * Requests in flight per processor (same index as {@link #processors});
     * guarded by {@link #slotLock}.
     */
    private final int[]  busy ;
    private final Object slotLock = new Object() ;

    /**
     * Rate bookkeeping per processor (same index); guarded by
     * {@link #slotLock}.
     */
    private final RateLimiter[] limiters ;

    /** Told when an attempt of an {@link #invoke} call starts. */
    @FunctionalInterface
    public interface AttemptListener {

        /**
         * {@code attempt} (1-based, across all processors) is about to be sent
         * to {@code model} on {@code processor}.
         */
        void attemptStarted( AIProcessor processor, String model, int attempt ) ;

        /**
         * An attempt ended; {@code transcript} has what was sent and received.
         */
        default void attemptFinished( AttemptTranscript transcript ) {}
    }

    /** Outcome of an {@link #invoke} call. */
    @Value
    @AllArgsConstructor( access = AccessLevel.PRIVATE )
    public static class InvocationResult {

        /**
         * The model's recommendations, best first as returned; null on failure.
         */
        List<Topic> topics ;

        /** Attempts made (0 if the image could not be prepared). */
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

    /** @throws IllegalArgumentException if {@code processors} is empty */
    public AIInvoker( List<AIProcessor> processors ) {

        if( processors.isEmpty() ) {
            throw new IllegalArgumentException(
                    "At least one AI processor is required" ) ;
        }
        this.processors = List.copyOf( processors ) ;
        this.busy       = new int[ processors.size() ] ;
        this.limiters   = processors.stream()
                                    .map( p -> new RateLimiter(
                                            p.getSettings().getRateLimitRpm(),
                                            p.getSettings().getRateLimitRpd() ) )
                                    .toArray( RateLimiter[]::new ) ;

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
        return new AIInvoker( ps ) ;
    }

    /**
     * Total parallel slots across all processors - the useful size of a worker
     * pool feeding this invoker.
     */
    public int getMaxParallel() {
        return processors.stream().mapToInt( AIProcessor::getMaxParallel ).sum() ;
    }

    /**
     * Classify one question, blocking until a processor slot is free and the
     * request has completed, retrying on failure within each processor's
     * per-question attempt budget.
     *
     * @param keepGoing checked before each retry; when it turns false no retry
     *         is made
     * @param onAttempt told which processor each attempt was given to, just
     *         before the request is sent
     * @return the topics, or the failure reason; never throws
     */
    public InvocationResult invoke( QTask task, BooleanSupplier keepGoing,
                                    AttemptListener onAttempt ) {

        String prompt = promptBySubject.get( task.getSubject() ) ;
        String image ;

        try {
            // Multi-part questions are stacked into one image so the model sees
            // the whole question.
            byte[] bytes = ImageCollator.collatedBytes( task.getImgFiles() ) ;
            image = Base64.getEncoder().encodeToString( bytes ) ;
        }
        catch( IOException e ) {
            log.error( "Error preparing image for {}", task, e ) ;
            return InvocationResult.failure( 0, "image error: " + e.getMessage() ) ;
        }
        String mimeType = ImageCollator.collatedMimeType( task.getImgFiles() ) ;

        String      lastFailure = "unknown error" ;
        AIProcessor lastFailed  = null ;
        int[]       used        = new int[ processors.size() ] ;
        int         attempt     = 0 ;

        while( true ) {
            AIProcessor processor ;
            try {
                processor = acquire( lastFailed, used, keepGoing ) ;
            }
            catch( InterruptedException e ) {
                Thread.currentThread().interrupt() ;
                return InvocationResult.failure( attempt, "interrupted" ) ;
            }
            if( processor == null ) {
                // Every attempt budget is used up, or a stop came while
                // waiting.
                if( !keepGoing.getAsBoolean() && attempt == 0 ) {
                    lastFailure = "stopped while waiting for a free AI target" ;
                }
                break ;
            }

            int    pIndex = processors.indexOf( processor ) ;
            String model  = processor.modelForAttempt( used[ pIndex ] ) ;

            attempt++ ;
            onAttempt.attemptStarted( processor, model, attempt ) ;

            InvocationResult outcome ;
            try {
                outcome = attemptInvoke( processor, model, task, prompt, image,
                                         mimeType, attempt, onAttempt ) ;
            }
            catch( AIProcessor.RateLimitException e ) {
                // Not the question's fault: the attempt is handed back and the
                // processor is taken out of rotation for a while.
                suspend( processor, e ) ;
                used[ pIndex ]-- ;   // so the same model is retried later
                attempt-- ;
                lastFailure = e.getMessage() ;
                continue ;
            }
            finally {
                release( processor ) ;
            }
            if( outcome.isSuccess() ) return outcome ;

            lastFailure = outcome.getFailureReason() ;
            lastFailed  = processor ;

            if( !hasBudget( used ) ) break ;
            if( Thread.currentThread().isInterrupted()
                || !keepGoing.getAsBoolean() ) {
                log.debug( "Stop requested - not retrying {}", task ) ;
                break ;
            }
            log.warn( "Retrying invocation for {} (attempt {})", task, attempt + 1 ) ;
        }
        return InvocationResult.failure( attempt, lastFailure ) ;
    }

    /** True if some processor can still take an attempt, given {@code used}. */
    private boolean hasBudget( int[] used ) {

        for( int i = 0 ; i < processors.size() ; i++ ) {
            if( used[i] <= processors.get( i ).getMaxRetries() ) return true ;
        }
        return false ;
    }

    // ---- processor slots ---------------------------------------------

    /**
     * Claim a slot on the least-loaded processor that has one free and still
     * has attempt budget for this question, waiting if all such are busy.
     * {@code avoid} (the processor that just failed) is only chosen when no
     * other eligible processor has a free slot. Counts the attempt in
     * {@code used}.
     *
     * @param used attempts this question has made per processor (same index
     *        as {@link #processors})
     * @return the processor, or null if none has budget left
     */
    private AIProcessor acquire( AIProcessor avoid, int[] used,
                                 BooleanSupplier keepGoing )
            throws InterruptedException {

        synchronized( slotLock ) {
            while( true ) {
                if( !hasBudget( used ) ) return null ;

                long   now      = System.currentTimeMillis() ;
                long   wakeAt   = Long.MAX_VALUE ;
                int    best     = -1 ;
                double bestLoad = Double.MAX_VALUE ;

                for( int i = 0 ; i < processors.size() ; i++ ) {
                    AIProcessor p = processors.get( i ) ;
                    if( used[i] > p.getMaxRetries() ) continue ;

                    long availableAt = limiters[i].availableAt( now ) ;
                    if( availableAt > now ) {           // rate limited or suspended
                        wakeAt = Math.min( wakeAt, availableAt ) ;
                        continue ;
                    }
                    if( busy[i] >= p.getMaxParallel() ) continue ;
                    double load = (double) busy[i] / p.getMaxParallel()
                                  + ( p == avoid ? 1 : 0 ) ;
                    if( load < bestLoad ) {
                        best     = i ;
                        bestLoad = load ;
                    }
                }
                if( best >= 0 ) {
                    busy[best]++ ;
                    used[best]++ ;
                    limiters[best].record( now ) ;
                    return processors.get( best ) ;
                }
                if( !keepGoing.getAsBoolean() ) return null ;

                // Wake when a slot frees (notify), when a rate window opens,
                // and
                // at least every second to notice a stop request.
                long waitMs = Math.min( MAX_WAIT_MS, wakeAt - now ) ;
                slotLock.wait( Math.max( 1, waitMs ) ) ;
            }
        }
    }

    /**
     * Stop routing to {@code processor} after it reported a rate limit: for
     * the time it asked for, else for its configured cooldown.
     */
    private void suspend( AIProcessor processor, AIProcessor.RateLimitException e ) {

        Duration pause = e.getRetryAfter() != null
                       ? e.getRetryAfter()
                       : processor.getSettings().getRateLimitCooldown() ;
        long until = System.currentTimeMillis() + pause.toMillis() ;

        synchronized( slotLock ) {
            limiters[ processors.indexOf( processor ) ].suspendUntil( until ) ;
            slotLock.notifyAll() ;
        }
        log.warn( "{} hit a rate limit - not routing to it for {}s",
                  processor.getName(), pause.toSeconds() ) ;
    }

    private void release( AIProcessor processor ) {
        synchronized( slotLock ) {
            busy[ processors.indexOf( processor ) ]-- ;
            slotLock.notifyAll() ;
        }
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

        long    startTime   = System.currentTimeMillis() ;
        String  rawResponse = null ;
        String  failure     = null ;
        boolean rateLimited = false ;

        try {
            log.debug( "\t  Sending {} to {} {} (attempt {}, prompt {} chars)",
                       task, processor.getName(), model, attempt,
                       prompt.length() ) ;
            rawResponse = processor.complete( model, prompt, image, mimeType ) ;
            String cleaned = cleanResponse( rawResponse ) ;

            try {
                List<Topic> topics = mapper.readValue( cleaned, TOPIC_LIST ) ;
                return InvocationResult.success( topics, attempt ) ;
            }
            catch( JsonProcessingException e ) {
                log.error( "Error parsing {} response (attempt {}): {}",
                           processor.getName(), attempt, cleaned, e ) ;
                failure = "unparseable " + processor.getName() + " response" ;
                return InvocationResult.failure( attempt, failure ) ;
            }
        }
        catch( AIProcessor.RateLimitException e ) {
            rawResponse = e.getDetail() ;
            failure     = e.getMessage() ;
            rateLimited = true ;

            throw e ;
        }
        catch( AIProcessor.ProcessorException e ) {
            rawResponse = e.getDetail() ;
            failure     = e.getMessage() ;

            return InvocationResult.failure( attempt, failure ) ;
        }
        finally {
            long elapsed = System.currentTimeMillis() - startTime ;
            log.debug( "\t  {} attempt {} on {} took {}.{}s",
                       task, attempt, processor.getName(),
                       elapsed / 1000, elapsed % 1000 ) ;
            onAttempt.attemptFinished( new AttemptTranscript(
                    task, processor.getAgent(), model, attempt, mimeType,
                    startTime, elapsed, prompt, rawResponse, failure,
                    rateLimited ) ) ;
        }
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
