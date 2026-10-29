package com.example.manage.dto;

import lombok.Getter;
import lombok.Setter;

/** Null order keeps the ordinary multipart upload flow available without JavaScript. */
@Getter
@Setter
public class ImageEditForm {

    private String imageOrder;
    private String imageSpatial;
    private String imageRepresentative;
    private String imageDeleted;
}
