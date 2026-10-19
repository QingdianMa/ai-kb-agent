package com.kbagent.lab;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 站点内容的只读接口，对访客公开。 */
@RestController
@RequestMapping("/api/lab")
public class LabController {

    private final LabRepository repository;

    public LabController(LabRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/members")
    public List<LabMember> members() {
        return repository.listMembers();
    }

    @GetMapping("/content")
    public Map<String, String> content() {
        return repository.loadContent();
    }

    @GetMapping("/publications")
    public List<LabPublication> publications() {
        return repository.listPublications();
    }
}
