package com.tokenledgercloud.api.domain.project.entity;

import lombok.Getter;

@Getter
public enum ProjectStatus {
    ACTIVE("활성"),
    ARCHIVED("보관됨"),
    DELETED("삭제됨");

    private final String description;

    ProjectStatus(String description) {
        this.description = description;
    }
}
