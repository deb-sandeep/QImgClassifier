package com.sandy.sconsole.qimgclassifier.workspace;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The processed-sources list: a text file naming, one per line, the source
 * folders whose every question has been classified. Listed sources are
 * skipped by discovery. All access through one instance is serialised, so
 * the batch pass and a repair never interleave writes; share a single
 * instance per file.
 */
@Slf4j
@RequiredArgsConstructor
public final class ProcessedList {

    @Getter
    private final File   file ;
    private final Object lock = new Object() ;

    /**
     * Source names in the file, in file order; empty if the file is missing or
     * unreadable (logged).
     */
    public Set<String> load() {

        synchronized( lock ) {
            Set<String> names = new LinkedHashSet<>() ;
            if( !file.exists() ) return names ;
            try {
                List<String> lines = Files.readAllLines( file.toPath(),
                                                         StandardCharsets.UTF_8 ) ;
                for( String line : lines ) {
                    String trimmed = line.trim() ;
                    if( !trimmed.isEmpty() ) names.add( trimmed ) ;
                }
            }
            catch( IOException e ) {
                log.error( "Error reading processed list file {}", file, e ) ;
            }
            return names ;
        }
    }

    /**
     * Append {@code srcName} unless it is already listed. Errors are logged,
     * not thrown.
     */
    public void add( String srcName ) {

        synchronized( lock ) {
            if( load().contains( srcName ) ) return ;
            try {
                Files.writeString( file.toPath(),
                                   srcName + System.lineSeparator(),
                                   StandardCharsets.UTF_8,
                                   StandardOpenOption.CREATE,
                                   StandardOpenOption.APPEND ) ;
                log.info( "Source {} fully classified - added to the " +
                          "processed list", srcName ) ;
            }
            catch( IOException e ) {
                log.error( "Error appending to processed list file {}", file, e ) ;
            }
        }
    }

    /**
     * Atomically replace the file's contents with {@code srcNames}, one per
     * line.
     */
    public void rewrite( Collection<String> srcNames ) throws IOException {

        synchronized( lock ) {
            Path tmp = new File( file.getAbsolutePath() + ".tmp" ).toPath() ;
            StringBuilder sb = new StringBuilder() ;
            for( String n : srcNames ) {
                sb.append( n ).append( System.lineSeparator() ) ;
            }
            Files.writeString( tmp, sb.toString(), StandardCharsets.UTF_8 ) ;
            Files.move( tmp, file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE ) ;
        }
    }
}
