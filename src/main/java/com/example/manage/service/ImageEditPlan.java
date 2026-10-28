package com.example.manage.service;

import com.example.manage.domain.ManagedImage;
import com.example.manage.dto.ImageEditForm;

import java.util.*;

/** Validated client keys: existing DB ID (e:42) or index in the submitted files (n:0). */
record ImageEditPlan(List<String> order, Set<String> deleted, String representative) {

    static ImageEditPlan validate(ImageEditForm form, List<? extends ManagedImage> current, int uploads) {
        List<String> order = tokens(form.getImageOrder());
        Set<String> deleted = new HashSet<>(tokens(form.getImageDeleted()));
        Set<String> expected = new HashSet<>();
        current.forEach(image -> expected.add("e:" + image.getImageId()));
        if (!expected.containsAll(deleted))
            throw invalid();
        expected.removeAll(deleted);
        for (int i = 0; i < uploads; i++)
            expected.add("n:" + i);
        if (new HashSet<>(order).size() != order.size() || !expected.equals(new HashSet<>(order)))
            throw invalid();
        String representative = form.getImageRepresentative();
        if (representative == null || representative.isBlank() || deleted.contains(representative))
            representative = order.isEmpty() ? null : order.getFirst();
        if (representative != null && !order.contains(representative))
            throw invalid();
        return new ImageEditPlan(order, deleted, representative);
    }

    private static List<String> tokens(String value) {
        return value == null || value.isEmpty() ? List.of() : Arrays.asList(value.split(",", -1));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("이미지 편집 정보가 변경되었거나 올바르지 않습니다. 화면을 새로 열고 다시 시도해 주세요.");
    }
}
