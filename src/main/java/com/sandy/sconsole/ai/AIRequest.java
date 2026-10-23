package com.sandy.sconsole.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Base64;
import java.util.List;

/**
 * What to ask a model: a prompt, the images that go with it, and optionally
 * the JSON schema its reply must follow. Gateways send the prompt first and
 * the images after it, so a prompt shared by many requests is a stable
 * prefix for prompt caching.
 *
 * @param prompt         the complete prompt text
 * @param images         the images, in order; may be empty
 * @param responseSchema the reply's schema; null for free text
 */
public record AIRequest( String prompt, List<Image> images,
                         ResponseSchema responseSchema ) {

    public AIRequest {
        images = List.copyOf( images ) ;
    }

    /** A request whose reply is free text. */
    public AIRequest( String prompt, List<Image> images ) {
        this( prompt, images, null ) ;
    }

    /**
     * An image, base64 encoded once so retries don't encode it again.
     *
     * @param base64   the image bytes, base64 encoded
     * @param mimeType its media type, e.g. {@code image/png}
     */
    public record Image( String base64, String mimeType ) {

        public static Image of( byte[] bytes, String mimeType ) {
            return new Image( Base64.getEncoder().encodeToString( bytes ),
                              mimeType ) ;
        }

        /** Shows the type and size, not the data. */
        @Override
        public String toString() {
            return mimeType + " (" + base64.length() + " base64 chars)" ;
        }
    }

    /**
     * The JSON schema a reply must follow. A gateway applies it only where
     * the model supports structured output (OpenRouter: its catalogue entry
     * lists {@code structured_outputs}), as a strict schema; elsewhere it is
     * ignored, so the prompt should still describe the reply and the app
     * still read it leniently. A provider may also ignore it.
     *
     * @param name   a short name for the schema, e.g. {@code tag_reply}
     *               (letters, digits, {@code _} and {@code -})
     * @param schema the JSON schema; for strict use, every object lists all
     *               its properties as required and sets
     *               {@code additionalProperties} false
     */
    public record ResponseSchema( String name, JsonNode schema ) {}
}
