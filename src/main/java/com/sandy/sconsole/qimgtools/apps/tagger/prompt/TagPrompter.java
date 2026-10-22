package com.sandy.sconsole.qimgtools.apps.tagger.prompt;

import com.sandy.sconsole.ai.AIRequest;
import com.sandy.sconsole.qimgtools.apps.tagger.check.ReplyValidator;
import com.sandy.sconsole.qimgtools.apps.tagger.model.Axis;
import com.sandy.sconsole.qimgtools.apps.tagger.model.OfferedSet;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagCatalogue;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagItem;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagMode;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagReply;
import com.sandy.sconsole.qimgtools.core.model.QTask;
import com.sandy.sconsole.qimgtools.core.model.Topic;
import com.sandy.sconsole.qimgtools.core.scheduler.TaskPrompter;
import com.sandy.sconsole.qimgtools.core.util.ImagePrep;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The blind solve asked of the models, and reading their replies. The
 * prompt is the shared template ({@code qtagger.prompt}) with the scope
 * topic's approved {@code topic.prompt} and the syllabus' other topics in
 * it, then a line naming the question's type; the image follows. All but
 * that last line is the same for every question of a variant, so prompt
 * caching keeps it for the run. There are two variants: a question of the
 * scope's topic, and a dependent (no math section, 0-2 concept tags, no
 * dependencies asked).
 * <p>
 * The scope's prompt is swapped whole ({@link #use}) when the scope changes
 * or a gap tag joins it; a request already built keeps the prompt it had.
 * Thread-safe.
 */
public final class TagPrompter implements TaskPrompter<TagReply> {

    /** A rough count of characters per token, for estimates. */
    public static final double CHARS_PER_TOKEN = 4.0 ;

    /**
     * A scope's prompt, assembled.
     *
     * @param topic       the scope's topic
     * @param template    the shared template
     * @param topicPrompt the topic's approved prompt
     * @param catalogue   the topic's catalogue
     * @param offered     the tags the prompt offers
     * @param mode        whether gaps may be proposed
     * @param otherTopics the syllabus' other topics, offered as dependencies
     * @param own         the fixed part for the topic's own questions
     * @param dependent   the fixed part for its dependents
     */
    public record Assembly( Topic topic, PromptFile template,
                            PromptFile topicPrompt, TagCatalogue catalogue,
                            OfferedSet offered, TagMode mode,
                            List<Topic> otherTopics, String own,
                            String dependent ) {

        public String templateVersion() {
            return template.get( "templateVersion" ) ;
        }

        public String promptVersion() {
            return topicPrompt.get( "promptVersion" ) ;
        }

        /** The topics a dependency may name. */
        public Set<Integer> topicIds() {
            return otherTopics.stream().map( Topic::getId )
                              .collect( Collectors.toSet() ) ;
        }

        /** What a reply is checked against, for either variant. */
        public ReplyValidator.Context context( boolean dependent ) {
            return new ReplyValidator.Context( catalogue, offered, dependent,
                                               mode, topicIds() ) ;
        }

        /** The fixed part of the prompt for the variant. */
        public String prefix( boolean dependent ) {
            return dependent ? this.dependent : own ;
        }
    }

    private final Function<QTask,TagItem> items ;
    private final ImagePrep               imagePrep ;

    private volatile Assembly assembly ;

    /**
     * @param items     the loaded question each submitted task is
     * @param imagePrep how images are made smaller before they are sent
     */
    public TagPrompter( Function<QTask,TagItem> items, ImagePrep imagePrep ) {
        this.items     = items ;
        this.imagePrep = imagePrep ;
    }

    /** Ask with {@code assembly} from now on. */
    public void use( Assembly assembly ) {
        this.assembly = assembly ;
    }

    /** The prompt in use; null before a scope is set. */
    public Assembly assembly() {
        return assembly ;
    }

    /**
     * Assemble the prompt of {@code topic} from {@code template} and its
     * approved {@code topicPrompt}.
     *
     * @param syllabusTopics every topic of the topic's syllabus
     */
    public static Assembly assemble( Topic topic, PromptFile template,
                                     PromptFile topicPrompt,
                                     TagCatalogue catalogue, TagMode mode,
                                     List<Topic> syllabusTopics ) {

        OfferedSet  offered = OfferedSet.parse( topicPrompt.body(), catalogue ) ;
        List<Topic> others  = syllabusTopics.stream()
                .filter( t -> t.getId() != topic.getId()
                              && t.getId() != TagCatalogue.FOUNDATION_MATHS )
                .toList() ;

        return new Assembly( topic, template, topicPrompt, catalogue, offered,
                             mode, others,
                             fill( topic, template, topicPrompt, mode, others,
                                   false ),
                             fill( topic, template, topicPrompt, mode, others,
                                   true ) ) ;
    }

    private static String fill( Topic topic, PromptFile template,
                                PromptFile topicPrompt, TagMode mode,
                                List<Topic> others, boolean dependent ) {

        String tags = dependent
                    ? PromptText.withoutSection( topicPrompt.body(), Axis.MATH )
                    : topicPrompt.body() ;

        return template.body()
                .replace( "{{SYLLABUS}}", topic.getSyllabusName() )
                .replace( "{{TOPIC}}", topic.getTopicName() )
                .replace( "{{AXES}}", axes( dependent ) )
                .replace( "{{TAGS}}", tags.strip() )
                .replace( "{{OTHER_TOPICS}}",
                          dependent ? "" : otherTopics( topic, others ) )
                .replace( "{{GAP}}", mode == TagMode.REVIEW_GAPS
                                     ? gapRule() : "" )
                .replace( "{{SCHEMA}}", schema( mode, dependent ) )
                .strip() ;
    }

    private static String axes( boolean dependent ) {

        return dependent
               ? "   - setup: every setup tag whose component is present in " +
                 "the figure or stem (any number, possibly none);\n" +
                 "   - concept: at most 2 concept tags of this topic that " +
                 "dominate the solution (possibly none: the question belongs " +
                 "to another topic and uses this one only in part)."
               : "   - setup: every setup tag whose component is present in " +
                 "the figure or stem (any number, possibly none);\n" +
                 "   - concept: the 1 or 2 concept tags that dominate the " +
                 "solution;\n" +
                 "   - math: at most 2 math tags whose technique is the crux " +
                 "of the solution (usually none)." ;
    }

    private static String otherTopics( Topic topic, List<Topic> others ) {

        StringBuilder sb = new StringBuilder( "Other topics of " )
                .append( topic.getSyllabusName() ).append( " (id: name):\n" ) ;
        others.forEach( t -> sb.append( t.getId() ).append( ": " )
                               .append( t.getTopicName() ).append( '\n' ) ) ;
        return sb.append( "In \"xdep\" give the ids of those other topics " +
                          "whose concepts the solution genuinely needs, " +
                          "beyond mathematics (the math tags cover that); " +
                          "[] if none.\n\n" ).toString() ;
    }

    private static String gapRule() {
        return "If a setup or concept move the solution hinges on has no " +
               "tag in the lists, propose one in \"gap\": its tag text in the " +
               "lists' style, a hint saying when to apply it (one or two " +
               "sentences), and why this question needs it. Propose only " +
               "when a dominant move is truly uncovered; otherwise \"gap\" " +
               "is null.\n\n" ;
    }

    private static String schema( TagMode mode, boolean dependent ) {

        StringBuilder sb = new StringBuilder( "{\"ans\": \"<answer>\", " )
                .append( "\"setup\": [\"<tag>\"], " )
                .append( "\"concept\": [{\"t\": \"<tag>\", " )
                .append( "\"why\": \"<at most 12 words>\"}]" ) ;
        if( !dependent ) {
            sb.append( ", \"math\": [\"<tag>\"], \"xdep\": [<topic id>]" ) ;
        }
        if( mode == TagMode.REVIEW_GAPS ) {
            sb.append( ", \"gap\": null or {\"tag\": \"<tag text>\", " )
              .append( "\"hint\": \"<when to apply>\", \"why\": \"<reason>\"}" ) ;
        }
        return sb.append( '}' ).toString() ;
    }

    /** Estimated tokens of {@code text}. */
    public static int estimateTokens( String text ) {
        return (int)Math.ceil( text.length() / CHARS_PER_TOKEN ) ;
    }

    // ---- TaskPrompter --------------------------------------------------

    @Override
    public String prompt( QTask task ) {

        TagItem item = items.apply( task ) ;
        return assembly.prefix( item.dependent() ) +
               "\n\nQuestion type: " + item.type() ;
    }

    @Override
    public AIRequest.Image prepareImage( QTask task ) throws IOException {
        return AIRequest.Image.of( imagePrep.bytes( task.getImgFiles() ),
                                   imagePrep.mimeType( task.getImgFiles() ) ) ;
    }

    @Override
    public TagReply parse( String reply, QTask task ) throws BadReply {
        TagItem item = items.apply( task ) ;
        return ReplyValidator.read( reply,
                                    assembly.context( item.dependent() ) ) ;
    }
}
