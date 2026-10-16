package com.sandy.sconsole.qimgclassifier.workspace;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared plumbing for the JSON files this package reads and rewrites: one
 * lock object per file path (so every class touching a file serialises on the
 * same monitor) and write-then-rename saves, so neither a crash nor a
 * concurrent reader such as QImgExtractor ever sees a half-written file.
 */
@NoArgsConstructor( access = AccessLevel.PRIVATE )
final class JsonFiles {

    /** Pretty-printing mapper, matching the layout QImgExtractor writes. */
    static final ObjectMapper MAPPER =
            new ObjectMapper().enable( SerializationFeature.INDENT_OUTPUT ) ;

    private static final Map<String,Object> LOCKS = new ConcurrentHashMap<>() ;

    /** The monitor guarding every read-modify-write of {@code file}. */
    static Object lockFor( File file ) {
        return LOCKS.computeIfAbsent( file.getAbsolutePath(), k -> new Object() ) ;
    }

    /**
     * Parse {@code file} as a JSON object for modification.
     *
     * @return the root object, or a new empty object if the file does not exist
     * @throws IOException if the file exists but cannot be read or is not a
     *         JSON object - callers must not then overwrite it, or its contents
     *         are lost
     */
    static ObjectNode readObject( File file ) throws IOException {
        if( !file.exists() ) return MAPPER.createObjectNode() ;
        JsonNode root = MAPPER.readTree( file ) ;
        if( root instanceof ObjectNode obj ) return obj ;
        throw new IOException( file + " does not contain a JSON object" ) ;
    }

    /**
     * Serialise {@code root} into {@code file} via a sibling temp file and an
     * atomic rename.
     */
    static void writeAtomically( File file, JsonNode root ) throws IOException {
        File tmp = new File( file.getParentFile(), file.getName() + ".tmp" ) ;
        MAPPER.writeValue( tmp, root ) ;
        Files.move( tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE ) ;
    }
}
