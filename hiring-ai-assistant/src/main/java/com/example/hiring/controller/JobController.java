package com.example.hiring.controller;

import com.example.hiring.api.CreateJobRequest;
import com.example.hiring.entity.JobPosition;
import com.example.hiring.service.JobPositionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API 层：职位管理。
 *
 * @author langchain4j-tutorial
 * @since v0.30
 */
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobPositionService jobPositionService;

    @PostMapping
    public JobPosition create(@Valid @RequestBody CreateJobRequest req) {
        return jobPositionService.create(req);
    }

    @GetMapping
    public List<JobPosition> list() {
        return jobPositionService.listOpen();
    }

    @GetMapping("/{id}")
    public JobPosition get(@PathVariable Long id) {
        return jobPositionService.get(id);
    }
}
