package com.sandy.sconsole.qimgtools.apps.tagger.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sandy.sconsole.ai.AIRequest;
import com.sandy.sconsole.qimgtools.apps.tagger.model.Axis;
import com.sandy.sconsole.qimgtools.apps.tagger.model.CatalogueTag;
import com.sandy.sconsole.qimgtools.apps.tagger.model.OfferedSet;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagClass;
import com.sandy.sconsole.qimgtools.apps.tagger.model.TagMode;

import java.util.Collection;
import java.util.List;

/**
 * A pass' reply as a strict JSON schema, for models that support
 * structured output: the shape {@code {{TagPrompter::schema}}} describes in
 * the prompt, with each tag list limited to the offered tags of its axis and
 * {@code xdep} to the topics offered, so a constrained model can't name
 * anything else. The tag counts are left to {@code ReplyValidator}: not
 * every provider takes {@code minItems} / {@code maxItems} in a strict
 * schema. Stateless.
 */
final class ReplySchema {

    /** The schema's name, as the request carries it. */
    static final String NAME = "tag_reply" ;

    private static final ObjectMapper MAPPER = new ObjectMapper() ;

    private ReplySchema() {}

    /**
     * The schema of a reply for the offered set's pass and the variant: the
     * setup pass has only {@code setup}; the concept pass {@code ans},
     * {@code concept}, {@code math} and {@code xdep}, a dependent no
     * {@code math} or {@code xdep}; {@code gap} is there only in
     * {@link TagMode#REVIEW_GAPS}.
     */
    static AIRequest.ResponseSchema of( OfferedSet offered, TagMode mode,
                                        Collection<Integer> topicIds,
                                        boolean dependent ) {

        ObjectNode root  = MAPPER.createObjectNode() ;
        ObjectNode props = object( root ) ;

        if( offered.tagClass() == TagClass.SETUP ) {
            props.set( "setup", tagList( offered.tags( Axis.SETUP ) ) ) ;
        }
        else {
            concept( props, offered, topicIds, dependent ) ;
        }
        if( mode == TagMode.REVIEW_GAPS ) {
            ObjectNode gap  = MAPPER.createObjectNode() ;
            ObjectNode gapP = object( gap ) ;
            for( String f : List.of( "tag", "hint", "why" ) ) {
                gapP.putObject( f ).put( "type", "string" ) ;
            }
            ArrayNode any = props.putObject( "gap" ).putArray( "anyOf" ) ;
            any.addObject().put( "type", "null" ) ;
            any.add( gap ) ;
        }
        required( root ) ;
        return new AIRequest.ResponseSchema( NAME, root ) ;
    }

    /** The concept pass' properties: the answer, concept, math, xdep. */
    private static void concept( ObjectNode props, OfferedSet offered,
                                 Collection<Integer> topicIds,
                                 boolean dependent ) {

        props.putObject( "ans" ).putArray( "type" ).add( "string" )
                                                  .add( "null" ) ;

        ObjectNode pick  = MAPPER.createObjectNode() ;
        ObjectNode pickP = object( pick ) ;
        pickP.set( "t", tag( offered.tags( Axis.CONCEPT ) ) ) ;
        pickP.putObject( "why" ).put( "type", "string" ) ;
        props.putObject( "concept" ).put( "type", "array" )
                                    .set( "items", pick ) ;

        if( !dependent ) {
            props.set( "math", tagList( offered.tags( Axis.MATH ) ) ) ;
            ObjectNode id = props.putObject( "xdep" ).put( "type", "array" )
                                 .putObject( "items" ).put( "type", "integer" ) ;
            if( !topicIds.isEmpty() ) {
                ArrayNode ids = id.putArray( "enum" ) ;
                topicIds.stream().sorted().forEach( ids::add ) ;
            }
        }
    }

    /**
     * {@code node} made a closed object; its properties node is returned
     * for filling, and {@link #required} lists them once filled.
     */
    private static ObjectNode object( ObjectNode node ) {
        node.put( "type", "object" ).put( "additionalProperties", false ) ;
        return node.putObject( "properties" ) ;
    }

    /** Every property of {@code node} and its nested objects required. */
    private static void required( ObjectNode node ) {

        if( node.has( "properties" ) ) {
            ArrayNode req = node.putArray( "required" ) ;
            node.get( "properties" ).fieldNames().forEachRemaining( req::add ) ;
        }
        node.elements().forEachRemaining( child -> {
            if( child instanceof ObjectNode o ) required( o ) ;
            else if( child instanceof ArrayNode a ) {
                a.forEach( e -> {
                    if( e instanceof ObjectNode o ) required( o ) ;
                } ) ;
            }
        } ) ;
    }

    private static ObjectNode tagList( List<CatalogueTag> tags ) {
        ObjectNode list = MAPPER.createObjectNode().put( "type", "array" ) ;
        list.set( "items", tag( tags ) ) ;
        return list ;
    }

    /** A tag of {@code tags}; any string if there are none to list. */
    private static ObjectNode tag( List<CatalogueTag> tags ) {

        ObjectNode t = MAPPER.createObjectNode().put( "type", "string" ) ;
        if( !tags.isEmpty() ) {
            ArrayNode values = t.putArray( "enum" ) ;
            tags.forEach( c -> values.add( c.tagText() ) ) ;
        }
        return t ;
    }
}
