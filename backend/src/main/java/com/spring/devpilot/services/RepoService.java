package com.spring.devpilot.services;

import com.spring.devpilot.dto.IndexStatusResponse;
import com.spring.devpilot.dto.RepositoryResponse;
import com.spring.devpilot.entity.IndexStatus;
import com.spring.devpilot.entity.Repository;
import com.spring.devpilot.entity.User;
import com.spring.devpilot.exceptions.NotFoundException;
import com.spring.devpilot.repository.RepoRepository;
import com.spring.devpilot.services.github.GithubApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RepoService {

    private final RepoRepository  repoRepository;
    private final UserService userService;
    private final GithubApiClient  githubApiClient;

    @Transactional
    public List<RepositoryResponse> syncAndListRepos(UUID userId){
        User user = userService.requiredById(userId);
        String token = userService.decryptAccessToken(user);
        List<Map<String, Object>> remoteRepos = githubApiClient.listUserRepos(token);
        List<Repository> saved = new ArrayList<>();
        for(Map<String, Object> remote : remoteRepos){
            Long githubRepoId = toLong(remote.get("id"));
            Repository repo = repoRepository
                    .findByUserIdAndGithubRepoId(userId,githubRepoId)
                    .orElseGet(Repository::new);

            String fullName = remote.get("full_name") != null
                    ? String.valueOf(remote.get("full_name"))
                    : null;

            String name = remote.get("name") != null
                    ? String.valueOf(remote.get("name"))
                    : null;

            String owner = null;

            Object ownerObj = remote.get("owner");

            if (ownerObj instanceof Map<?, ?> ownerMap) {
                Object login = ownerMap.get("login");

                if (login != null) {
                    owner = String.valueOf(login);
                }
            }

            repo.setUserId(userId);
            repo.setGithubRepoId(githubRepoId);
            repo.setOwner(owner);
            repo.setName(name);
            repo.setFullName(fullName);
            repo.setPrivate(Boolean.TRUE.equals(remote.get("private")));
            repo.setDefaultBranch(remote.get("default_branch") != null
                    ? String.valueOf(remote.get("default_branch"))
                    : "main");
            repo.setLanguage(remote.get("language") != null ? String.valueOf(remote.get("language")): null);
            repo.setHtmlUrl(remote.get("html_url") != null ? String.valueOf(remote.get("html_url")): null);
            repo.setDescription(remote.get("description") != null ? String.valueOf(remote.get("description")): null);
            repo.setUpdatedAt(Instant.now());
            saved.add(repoRepository.save(repo));
        }
        return saved.stream()
                .sorted((a,b) -> a.getFullName().compareToIgnoreCase(b.getFullName()))
                .map(this::toResponse)
                .toList();

    }

    @Transactional(readOnly = true)
    public List<RepositoryResponse> listStored(UUID userId){
        return repoRepository.findByUserIdOrderByFullNameAsc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Repository requireOwned(UUID repoId, UUID userId){
        return repoRepository.findByIdAndUserId(repoId,userId)
                .orElseThrow(()-> new NotFoundException("Repository not found"));
    }

    @Transactional(readOnly = true)
    public IndexStatusResponse status(UUID repoId, UUID userId){
        Repository repo = requireOwned(repoId,userId);
        return new IndexStatusResponse(
                repo.getId(),
                repo.getIndexStatus(),
                repo.getFilesTotal(),
                repo.getFilesProcessed(),
                repo.getChunkCount(),
                repo.getIndexedAt(),
                repo.getErrorMessage()
        );
    }

    public RepositoryResponse toResponse(Repository repo){
        return new RepositoryResponse(
                repo.getId(),
                repo.getGithubRepoId(),
                repo.getOwner(),
                repo.getName(),
                repo.getFullName(),
                repo.isPrivate(),
                repo.getDefaultBranch(),
                repo.getLanguage(),
                repo.getHtmlUrl(),
                repo.getDescription(),
                repo.getIndexStatus(),
                repo.getIndexedAt(),
                repo.getChunkCount(),
                repo.getFilesTotal(),
                repo.getFilesProcessed(),
                repo.getErrorMessage()

        );
    }


    private static Long toLong(Object value){
        if(value instanceof Number number){
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

}
