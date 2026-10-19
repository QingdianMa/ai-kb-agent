package com.kbagent.lab;

import java.util.UUID;

/** 论文成果。 */
public record LabPublication(
        UUID id,
        String title,
        String authors,
        String venue,
        Integer year,
        String url,
        int sortOrder) {
}
