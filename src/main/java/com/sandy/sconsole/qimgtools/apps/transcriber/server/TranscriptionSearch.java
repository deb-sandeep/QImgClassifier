package com.sandy.sconsole.qimgtools.apps.transcriber.server;

import com.sandy.sconsole.qimgtools.core.server.ServerQuestion;

import java.util.List;

/**
 * One page of the server's question search ({@code POST
 * /Master/Question/Search}, the server's
 * {@code doc/feature-enhancements/2026-11-17/transcription-search/}).
 *
 * @param totalResults the questions matching, on every page
 * @param totalPages   the pages there are at this page size
 * @param pageNumber   this page, from 0
 * @param questions    this page's questions, by server id
 */
public record TranscriptionSearch( long totalResults, int totalPages,
                                   int pageNumber,
                                   List<ServerQuestion> questions ) {

    public TranscriptionSearch {
        questions = questions == null ? List.of() : List.copyOf( questions ) ;
    }

    /** True if there are pages after this one. */
    public boolean hasMore() {
        return pageNumber + 1 < totalPages ;
    }
}
