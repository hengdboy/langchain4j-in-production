package com.example.hiring.service;

import com.example.hiring.api.CreateJobRequest;
import com.example.hiring.entity.JobPosition;
import com.example.hiring.mapper.JobPositionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 职位服务：创建/查询职位。
 *
 * @author hengdboy
 * @since v0.30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobPositionService {

    private final JobPositionMapper jobPositionMapper;
    private final ObjectMapper objectMapper;

    public JobPosition create(CreateJobRequest req) {
        JobPosition job = new JobPosition();
        job.setTitle(req.title());
        job.setDepartment(req.department());
        job.setJdText(req.jdText());
        job.setRequiredSkills(toJson(req.requiredSkills() == null ? List.of() : req.requiredSkills()));
        job.setHeadcount(req.headcount());
        job.setStatus("OPEN");
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        jobPositionMapper.insert(job);
        log.info("[Job] created id={} title={}", job.getId(), job.getTitle());
        return job;
    }

    public JobPosition get(Long id) {
        return jobPositionMapper.selectById(id);
    }

    public List<JobPosition> listOpen() {
        return jobPositionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<JobPosition>()
                        .eq("status", "OPEN").orderByDesc("id"));
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}
