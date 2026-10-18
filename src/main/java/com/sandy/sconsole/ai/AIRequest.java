package com.sandy.sconsole.ai;

import java.util.Base64;
import java.util.List;

/**
 * What to ask a model: a prompt and the images that go with it. Gateways
 * send the prompt first and the images after it, so a prompt shared by many
 * requests is a stable prefix for prompt caching.
 *
 * @param prompt the complete prompt text
 * @param images the images, in order; may be empty
 */
public record AIRequest( String prompt, List<Image> images ) {

    public AIRequest {
        images = List.copyOf( images ) ;
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
}
