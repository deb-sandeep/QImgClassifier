package com.sandy.sconsole.qimgclassifier.ai;

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
 * throughput. A failed attempt is retried once, preferring a different
 * processor.
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

    private static final int MAX_ATTEMPTS = 2 ;

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

    /** Outcome of an {@link #invoke} call. */
    @Value
    @AllArgsConstructor( access = AccessLevel.PRIVATE )
    public static class InvocationResult {

        /**
         * The model's recommendations, best first as returned; null on failure.
         */
        List<Topic> topics ;

        /** Attempts made (0 if the image could not be prepared). */
        int         attempts ;

        /** Short reason for the failure; null on success. */
        String      failureReason ;

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
        if( config.isGemmaEnabled() ) {
            String url      = config.getGemmaServerUrl() ;
            String model    = config.getGemmaModel() ;
            int    parallel = config.getGemmaMaxParallel() ;
            ps.add( new OllamaProcessor( url, model, parallel ) ) ;
            log.info( "AI processor: gemma ({} @ {}), {} parallel",
                      model, url, parallel ) ;
        }
        if( config.isOpenRouterEnabled() ) {
            String apiKey = config.getOpenRouterApiKey() ;
            if( apiKey == null ) {
                log.error( "OpenRouter is enabled but no API key was found " +
                           "- skipping it" ) ;
            }
            else {
                String model    = config.getOpenRouterModel() ;
                int    parallel = config.getOpenRouterMaxParallel() ;
                ps.add( new OpenRouterProcessor( apiKey, model, parallel ) ) ;
                log.info( "AI processor: openrouter ({}), {} parallel",
                          model, parallel ) ;
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
     * request has completed, with one retry on failure.
     *
     * @param keepGoing checked before the retry; when it turns false no retry
     *         is made
     * @return the topics, or the failure reason; never throws
     */
    public InvocationResult invoke( QTask task, BooleanSupplier keepGoing ) {

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
        for( int attempt = 1 ; attempt <= MAX_ATTEMPTS ; attempt++ ) {
            AIProcessor processor ;
            try {
                processor = acquire( lastFailed ) ;
            }
            catch( InterruptedException e ) {
                Thread.currentThread().interrupt() ;
                return InvocationResult.failure( attempt - 1, "interrupted" ) ;
            }

            InvocationResult outcome ;
            try {
                outcome = attemptInvoke( processor, task, prompt, image,
                                         mimeType, attempt ) ;
            }
            finally {
                release( processor ) ;
            }
            if( outcome.isSuccess() ) return outcome ;

            lastFailure = outcome.getFailureReason() ;
            lastFailed  = processor ;
            if( attempt < MAX_ATTEMPTS ) {
                if( Thread.currentThread().isInterrupted()
                    || !keepGoing.getAsBoolean() ) {
                    log.debug( "Stop requested - not retrying {}", task ) ;
                    return InvocationResult.failure( attempt, lastFailure ) ;
                }
                log.warn( "Retrying invocation for {} (attempt {} of {})",
                          task, attempt + 1, MAX_ATTEMPTS ) ;
            }
        }
        return InvocationResult.failure( MAX_ATTEMPTS, lastFailure ) ;
    }

    // ---- processor slots ---------------------------------------------

    /**
     * Claim a slot on the least-loaded processor that has one free, waiting if
     * all are busy. {@code avoid} (the processor that just failed) is only
     * chosen when no other processor has a free slot.
     */
    private AIProcessor acquire( AIProcessor avoid ) throws InterruptedException {

        synchronized( slotLock ) {
            while( true ) {
                int    best     = -1 ;
                double bestLoad = Double.MAX_VALUE ;
                for( int i = 0 ; i < processors.size() ; i++ ) {
                    AIProcessor p = processors.get( i ) ;
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
                    return processors.get( best ) ;
                }
                slotLock.wait() ;
            }
        }
    }

    private void release( AIProcessor processor ) {
        synchronized( slotLock ) {
            busy[ processors.indexOf( processor ) ]-- ;
            slotLock.notifyAll() ;
        }
    }

    // ---- one attempt ---------------------------------------------------

    private InvocationResult attemptInvoke( AIProcessor processor, QTask task,
                                            String prompt, String image,
                                            String mimeType, int attempt ) {

        long startTime = System.currentTimeMillis() ;
        try {
            log.debug( "\t  Sending {} to {} (attempt {}, prompt {} chars)",
                       task, processor.getName(), attempt, prompt.length() ) ;
            String modelResponse =
                    cleanResponse( processor.complete( prompt, image, mimeType ) ) ;
            try {
                List<Topic> topics = mapper.readValue( modelResponse, TOPIC_LIST ) ;
                return InvocationResult.success( topics, attempt ) ;
            }
            catch( JsonProcessingException e ) {
                log.error( "Error parsing {} response (attempt {}): {}",
                           processor.getName(), attempt, modelResponse, e ) ;
                return InvocationResult.failure(
                        attempt, "unparseable " + processor.getName() + " response" ) ;
            }
        }
        catch( AIProcessor.ProcessorException e ) {
            return InvocationResult.failure( attempt, e.getMessage() ) ;
        }
        finally {
            long elapsed = System.currentTimeMillis() - startTime ;
            log.debug( "\t  {} attempt {} on {} took {}.{}s",
                       task, attempt, processor.getName(),
                       elapsed / 1000, elapsed % 1000 ) ;
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
