package com.sandy.sconsole.qimgtools.core.ai;

import com.sandy.sconsole.ai.AIRequest;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.model.Subject;
import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.model.TopicRepo;
import com.sandy.sconsole.qimgtools.core.util.ImageCollator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The classification question asked of a model, and reading its answer.
 * <p>
 * Prompts come from classpath templates in the prompt folder the caller
 * names - {@code qclassifier.chemistry.prompt} for Chemistry,
 * {@code qclassifier.prompt} otherwise - with {@code {{SUBJECT_SYLLABUS}}}
 * replaced by the subject's syllabus reference
 * ({@code qclassifier.<subject>.syllabus.txt} in the same folder) and
 * {@code {{TOPIC_LIST_JSON}}} by its topic list. They are built once per
 * subject. Thread-safe.
 */
@Slf4j
public class TopicPrompter {

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

    /** The topics offered to the model, per subject. */
    private final TopicRepo topicRepo ;

    /**
     * @param topicRepo the topics offered to the model in the prompt
     * @param promptDir the classpath folder holding the prompt templates and
     *        syllabus files, e.g. {@code /apps/classifier/}
     */
    public TopicPrompter( TopicRepo topicRepo, String promptDir ) {

        this.topicRepo = topicRepo ;

        String defaultTemplate   = loadResource(
                promptDir + "qclassifier.prompt" ) ;
        String chemistryTemplate = loadResource(
                promptDir + "qclassifier.chemistry.prompt" ) ;

        for( Subject subject : Subject.values() ) {
            String template = subject == Subject.CHEMISTRY ? chemistryTemplate
                                                           : defaultTemplate ;
            promptBySubject.put( subject,
                                 buildPrompt( promptDir, template, subject ) ) ;
        }
    }

    /** The complete prompt for questions of {@code subject}. */
    public String prompt( Subject subject ) {
        return promptBySubject.get( subject ) ;
    }

    /**
     * The question's image; a multi-part question's images are stacked into
     * one so the model sees the whole question.
     *
     * @throws IOException if an image cannot be read or stacked
     */
    public AIRequest.Image prepareImage( QTask task ) throws IOException {

        List<File> files = task.getImgFiles() ;
        return AIRequest.Image.of( ImageCollator.collatedBytes( files ),
                                   ImageCollator.collatedMimeType( files ) ) ;
    }

    /**
     * The topics in a model's reply: a JSON array, possibly in markdown
     * fences, or a lone object. An entry that cannot be read (e.g.
     * {@code "id" : "N/A"} for a topic not in the list) is skipped with a
     * warning; the rest still count.
     *
     * @throws JsonProcessingException if the reply is not a JSON array, or no
     *         entry in it can be read
     */
    public List<Topic> parseTopics( String reply, QTask task )
            throws JsonProcessingException {

        JsonNode root = mapper.readTree( cleanResponse( reply ) ) ;
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

    private String buildPrompt( String promptDir, String template,
                                Subject subject ) {

        String syllabus = subject.hasSyllabus()
                ? loadResource( promptDir + "qclassifier."
                                + subject.name().toLowerCase()
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

        List<Topic> topics = topicRepo.getTopics( subject ) ;
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

        try( InputStream is = TopicPrompter.class.getResourceAsStream( path ) ) {
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
