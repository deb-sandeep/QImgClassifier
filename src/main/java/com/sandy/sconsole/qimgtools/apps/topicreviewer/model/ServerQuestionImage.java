package com.sandy.sconsole.qimgtools.apps.topicreviewer.model;

import lombok.Data;

/**
 * One image of a {@link ServerQuestion}, as QImgExtractor synced it. The
 * image data the server may send along is not read; the local copy in the
 * question bank is used instead.
 */
@Data
public class ServerQuestionImage {

    /** Position in the question; LCT context images come first. */
    private Integer sequence ;
    private Integer pageNumber ;

    /**
     * Short file name, {@code <tag>[(<part>)].png}, e.g. {@code C_SCA_74.png};
     * the local file is {@code <sourceId>.<page>.<fileName>}.
     */
    private String fileName ;

    private Boolean lctCtxImage ;

    /** -1 for an image that is not a part. */
    private Integer partNumber ;
}
