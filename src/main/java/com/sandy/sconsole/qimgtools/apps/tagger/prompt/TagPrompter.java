package com.sandy.sconsole.qimgtools.apps.tagger.prompt;

import com.sandy.sconsole.ai.AIRequest;
import com.sandy.sconsole.qimgtools.apps.tagger.check.ReplyValidator;
import com.sandy.sconsole.qimgtools.apps.tagger.model.OfferedSet;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagCatalogue;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagClass;
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
 * What the models are asked, and reading their replies. The prompt is the
 * pass' template ({@code qtagger.<pass>.prompt}) with the scope topic's
 * approved reviewed prompt of the pass in it - in the concept pass also its
 * axis rules ({@code qtagger.concept.axes.prompt}) and the syllabus' other
 * topics - then a line naming the question's type; the image follows. All
 * but that last line is the same for every question of a variant, so prompt
 * caching keeps it for the run. The setup pass asks the model to read the
 * question and give its setup tags; the concept pass to solve it blind and
 * give its concept and math tags and dependencies, in two variants: a
 * question of the scope's topic, and a dependent (no math section, 0-2
 * concept tags, no dependencies asked).
 * <p>
 * The scope's prompt is swapped whole ({@link #use}) when the scope changes
 * or a gap tag joins it; a request already built keeps the prompt it had.
 * Thread-safe.
 */
public final class TagPrompter implements TaskPrompter<TagReply> {

    /** A rough count of characters per token, for estimates. */
    public static final double CHARS_PER_TOKEN = 4.0 ;

    /** The sections of {@code axes.prompt}, one per variant. */
    public static final String OWN_AXES       = "Own" ;
    public static final String DEPENDENT_AXES = "Dependent" ;

    /**
     * Two or more blank lines in a row, left where a placeholder's text
     * ends in a blank line or is empty; the prompt keeps one.
     */
    private static final String BLANK_LINES = "\\n(?:[ \\t]*\\n){2,}" ;

    /**
     * A scope's prompt, assembled.
     *
     * @param topic       the scope's topic
     * @param template    the pass' template
     * @param topicPrompt the topic's approved prompt
     * @param catalogue   the topic's catalogue
     * @param offered     the tags the prompt offers
     * @param mode        whether gaps may be proposed
     * @param otherTopics the syllabus' other topics, offered as dependencies
     * @param own         the fixed part for the topic's own questions
     * @param dependent   the fixed part for its dependents; the setup pass,
     *                    which has none, the same as {@code own}
     */
    public record Assembly( Topic topic, PromptFile template,
                            PromptFile topicPrompt, TagCatalogue catalogue,
                            OfferedSet offered, TagMode mode,
                            List<Topic> otherTopics, String own,
                            String dependent ) {

        /** The pass the prompt is for. */
        public TagClass tagClass() {
            return offered.tagClass() ;
        }

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

        /** The reply's JSON schema for the variant (see {@link ReplySchema}). */
        public AIRequest.ResponseSchema schema( boolean dependent ) {
            return ReplySchema.of( offered, mode, topicIds(), dependent ) ;
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
     * Assemble the prompt of pass {@code tagClass} for {@code topic} from
     * {@code template}, filling its {@code {{<pass>-tags.reviewed.prompt}}}
     * from the topic's approved {@code topicPrompt} and, in the concept
     * pass, its {@code {{qtagger.concept.axes.prompt}}} from {@code axes}.
     *
     * @param axes           the concept pass' axis rules; ignored (may be
     *                       null) in the setup pass
     * @param syllabusTopics every topic of the topic's syllabus
     * @throws IllegalStateException if {@code axes} lacks a variant's section
     */
    public static Assembly assemble( TagClass tagClass, Topic topic,
                                     PromptFile template, PromptFile axes,
                                     PromptFile topicPrompt,
                                     TagCatalogue catalogue, TagMode mode,
                                     List<Topic> syllabusTopics ) {

        OfferedSet  offered = OfferedSet.parse( topicPrompt.body(), catalogue,
                                                tagClass ) ;
        List<Topic> others  = !tagClass.hasDependents() ? List.of()
                : syllabusTopics.stream()
                .filter( t -> t.getId() != topic.getId()
                              && t.getId() != TagCatalogue.FOUNDATION_MATHS
                              && t.getId() != TagCatalogue.FOUNDATION_VECTORS )
                .toList() ;

        if( !tagClass.hasDependents() ) {
            String own = fill( tagClass, topic, template, "", topicPrompt,
                               mode, others, false ) ;
            return new Assembly( topic, template, topicPrompt, catalogue,
                                 offered, mode, others, own, own ) ;
        }

        String ownAxes = axesSection( axes, OWN_AXES ) ;
        String depAxes = axesSection( axes, DEPENDENT_AXES ) ;

        return new Assembly( topic, template, topicPrompt, catalogue, offered,
                             mode, others,
                             fill( tagClass, topic, template, ownAxes,
                                   topicPrompt, mode, others, false ),
                             fill( tagClass, topic, template, depAxes,
                                   topicPrompt, mode, others, true ) ) ;
    }

    private static String axesSection( PromptFile axes, String name ) {

        String text = axes == null ? null
                                   : PromptText.section( axes.body(), name ) ;
        if( text == null || text.isBlank() ) {
            throw new IllegalStateException( PromptStore.AXES +
                                             " has no ## " + name +
                                             " section" ) ;
        }
        return text ;
    }

    /** The template's placeholder for the text of prompt file {@code name}. */
    private static String placeholder( String name ) {
        return "{{" + name + "}}" ;
    }

    /**
     * The template with its placeholders filled. A placeholder names where
     * its text comes from: {@code {{<file>}}} a prompt file,
     * {@code {{TagPrompter::<method>}}} the method here that builds it (so
     * renaming one of those methods means renaming its placeholder in the
     * templates), and {@code {{SYLLABUS}}} / {@code {{TOPIC}}} the topic's
     * names. A template may leave out what its pass doesn't use.
     */
    private static String fill( TagClass tagClass, Topic topic,
                                PromptFile template, String axes,
                                PromptFile topicPrompt, TagMode mode,
                                List<Topic> others, boolean dependent ) {

        String tags = dependent
                    ? PromptText.forDependent( topicPrompt.body() )
                    : topicPrompt.body() ;

        return template.body()
                .replace( "{{SYLLABUS}}", topic.getSyllabusName() )
                .replace( "{{TOPIC}}", topic.getTopicName() )
                .replace( placeholder( PromptStore.AXES ), axes )
                .replace( placeholder( PromptStore.reviewedName( tagClass ) ),
                          tags.strip() )
                .replace( "{{TagPrompter::otherTopics}}",
                          dependent || others.isEmpty()
                          ? "" : otherTopics( topic, others ) )
                .replace( "{{TagPrompter::gapRule}}",
                          mode == TagMode.REVIEW_GAPS ? gapRule( tagClass )
                                                      : "" )
                .replace( "{{TagPrompter::schema}}",
                          schema( tagClass, mode, dependent ) )
                .replaceAll( BLANK_LINES, "\n\n" )
                .strip() ;
    }

    private static String otherTopics( Topic topic, List<Topic> others ) {

        StringBuilder sb = new StringBuilder( "Other topics of " )
                .append( topic.getSyllabusName() ).append( " (id: name):\n" ) ;
        others.forEach( t -> sb.append( t.getId() ).append( ": " )
                               .append( t.getTopicName() ).append( '\n' ) ) ;
        return sb.append( "In \"xdep\" give the ids of at most " +
                          ReplyValidator.MAX_DEPENDENCIES + " other " +
                          "topics, most important first, and only those " +
                          "critical to solving the problem: it cannot be " +
                          "solved on the intended efficient path without " +
                          "that topic's own idea. Most problems draw on " +
                          "other topics somewhere; that alone is no reason " +
                          "to list one. A topic used only for a routine " +
                          "step is never a dependency (the routine uses of " +
                          "this topic's problems are listed under " +
                          "Dependencies above, where given), and neither " +
                          "is mathematics (the math tags cover that). Most " +
                          "questions have none: [].\n\n" )
                 .toString() ;
    }

    private static String gapRule( TagClass tagClass ) {

        if( tagClass == TagClass.SETUP ) {
            return "If a component of the figure or stem that shapes the " +
                   "problem has no setup tag in the list, propose one in " +
                   "\"gap\": its tag text in the list's style (starting " +
                   "\"s.\"), a hint saying when to apply it (one or two " +
                   "sentences), and why this question needs it. Propose " +
                   "only when such a component is truly uncovered; " +
                   "otherwise \"gap\" is null.\n\n" ;
        }
        return "If a concept the solution hinges on has no tag in the " +
               "lists, propose one in \"gap\": its tag text in the lists' " +
               "style (starting \"c.\"), a hint saying when to apply it (one " +
               "or two sentences), and why this question needs it. Propose " +
               "only when a dominant concept is truly uncovered; otherwise " +
               "\"gap\" is null. If the dominant concept has no tag, propose " +
               "it as the gap and give only the concept tags that do apply, " +
               "possibly none.\n\n" ;
    }

    private static String schema( TagClass tagClass, TagMode mode,
                                  boolean dependent ) {

        StringBuilder sb = new StringBuilder( "{" ) ;
        if( tagClass == TagClass.SETUP ) {
            sb.append( "\"setup\": [\"<tag>\"]" ) ;
        }
        else {
            sb.append( "\"ans\": \"<answer>\" or null, " )
              .append( "\"concept\": [{\"t\": \"<tag>\", " )
              .append( "\"why\": \"<at most 12 words>\"}]" ) ;
            if( !dependent ) {
                sb.append( ", \"math\": [\"<tag>\"], " )
                  .append( "\"xdep\": [<topic id>]" ) ;
            }
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

    /**
     * The reply's schema for the question's variant. A model that ignores
     * it is still read by {@link #parse}, which strips fences and finds the
     * object among other text.
     */
    @Override
    public AIRequest.ResponseSchema responseSchema( QTask task ) {
        return assembly.schema( items.apply( task ).dependent() ) ;
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
