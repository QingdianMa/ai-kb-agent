package com.kbagent.web.dto;

import java.util.List;

public record ChatAnswer(String answer, List<SourceRef> sources) {
}
