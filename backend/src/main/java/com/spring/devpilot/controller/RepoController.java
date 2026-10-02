package com.spring.devpilot.controller;

import com.spring.devpilot.dto.IndexStatusResponse;
import com.spring.devpilot.dto.RepositoryResponse;
import com.spring.devpilot.entity.Repository;
import com.spring.devpilot.security.CurrentUser;
import com.spring.devpilot.services.RepoService;
import com.spring.devpilot.services.indexing.IndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@Slf4j
@RequestMapping("/api/repos")
@RequiredArgsConstructor
public class RepoController {

    private final CurrentUser currentUser;
    private final RepoService repoService;
    private final IndexingService indexingService;

    @GetMapping
    public List<RepositoryResponse> list(
            @RequestParam(name = "refresh", defaultValue = "true") boolean refresh){
        UUID userId = currentUser.require().getId();
        if(refresh){
            return repoService.syncAndListRepos(userId);
        }
        return repoService.listStored(userId);
    }

    @GetMapping("/{id}")
    public RepositoryResponse get(@PathVariable UUID id){
        UUID userId = currentUser.require().getId();
        return repoService.toResponse(repoService.requireOwned(id,userId));
    }

    @PostMapping("/{id}/index")
    public ResponseEntity<RepositoryResponse> index(@PathVariable UUID id) {
        UUID userId = currentUser.require().getId();
        Repository repo = indexingService.startIndexing(id, userId);
        indexingService.indexAsync(id, userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(repoService.toResponse(repo));
    }

    @GetMapping("/{id}/status")
    public IndexStatusResponse status(@PathVariable UUID id){
        UUID userId = currentUser.require().getId();
        return repoService.status(id,userId);
    }
}
