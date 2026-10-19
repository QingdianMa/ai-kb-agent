package com.kbagent.lab;

import java.util.UUID;

/** 团队成员。 */
public record LabMember(
        UUID id,
        String name,
        String role,
        String title,
        String photoPath,
        String bio,
        String email,
        int sortOrder) {
}
