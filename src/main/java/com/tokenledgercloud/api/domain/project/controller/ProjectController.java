package com.tokenledgercloud.api.domain.project.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tokenledgercloud.api.domain.project.dto.ProjectCreateRequest;
import com.tokenledgercloud.api.domain.project.service.ProjectService;
import com.tokenledgercloud.api.global.response.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

	private final ProjectService projectService;

	@PostMapping
	public ResponseEntity<ApiResponse<?>> createProject(
		Authentication authentication,
		@Valid @RequestBody ProjectCreateRequest request
	) {
		try {
			return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success("프로젝트 생성 성공", projectService.createProject(authentication, request)));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ApiResponse.error("PROJECT_KEY_ALREADY_EXISTS", "이미 사용 중인 projectKey입니다."));
		}
	}

	@GetMapping
	public ResponseEntity<ApiResponse<?>> getProjects(
		Authentication authentication,
		@RequestParam(required = false) String environment,
		@RequestParam(required = false) String status
	) {
		return ResponseEntity.ok(ApiResponse.success(
			"프로젝트 목록 조회 성공",
			projectService.getProjects(authentication, environment, status)
		));
	}

	@GetMapping("/ranking")
	public ResponseEntity<ApiResponse<?>> getProjectRanking(
		Authentication authentication,
		@RequestParam(required = false) String environment,
		@RequestParam String period,
		@RequestParam(defaultValue = "10") Integer limit
	) {
		try {
			return ResponseEntity.ok(ApiResponse.success(
				"프로젝트 비용 랭킹 조회 성공",
				projectService.getProjectRanking(authentication, environment, period, limit)
			));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest()
				.body(ApiResponse.error("INVALID_LIMIT", "limit 값이 허용 범위를 벗어났습니다."));
		}
	}

	@GetMapping("/{projectId}")
	public ResponseEntity<ApiResponse<?>> getProject(
		Authentication authentication,
		@PathVariable String projectId
	) {
		return ResponseEntity.ok(ApiResponse.success(
			"프로젝트 상세 조회 성공",
			projectService.getProject(authentication, projectId)
		));
	}
}
