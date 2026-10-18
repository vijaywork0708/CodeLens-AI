package com.spring.devpilot.service;

import com.spring.devpilot.dto.RepositoryResponse;
import com.spring.devpilot.entity.Repository;
import com.spring.devpilot.entity.User;
import com.spring.devpilot.exceptions.NotFoundException;
import com.spring.devpilot.repository.RepoRepository;
import com.spring.devpilot.repository.UserRepository;
import com.spring.devpilot.services.RepoService;
import com.spring.devpilot.services.UserService;
import com.spring.devpilot.services.github.GithubApiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
public class RepoServiceTest {

    @Mock
    private RepoRepository repoRepository;
    @Mock
    private UserService userService;
    @Mock
    private GithubApiClient githubApiClient;

    @InjectMocks
    private RepoService repoService;

    @Test
    void syncAndListRepos_shouldCreateNewRepo_whenNotInDatabase(){
        // Arrange
        UUID userId = UUID.randomUUID();
        User user = new User();

        Map<String,Object> remote = Map.of(
                "id",100,
                "full_name","vijay/hello",
                "name","hello",
                "owner",Map.of("login","vijay"),
                "private",true,
                "default_branch","develop",
                "language","Java",
                "html_url","https://github.com/vijay/hello",
                "description","My first repo"
        );

        when(userService.requiredById(userId)).thenReturn(user);
        when(userService.decryptAccessToken(user)).thenReturn("plain-token");
        when(githubApiClient.listUserRepos("plain-token")).thenReturn(List.of(remote));
        when(repoRepository.findByUserIdAndGithubRepoId(userId,100L)).thenReturn(Optional.empty());
        when(repoRepository.save(any(Repository.class))).thenAnswer(i -> i.getArgument(0));

        //Act
        List<RepositoryResponse> result = repoService.syncAndListRepos(userId);

        //Assert
        ArgumentCaptor<Repository> captor = ArgumentCaptor.forClass(Repository.class);
        verify(repoRepository).save(captor.capture());
        Repository saved = captor.getValue();

        assertEquals(userId, saved.getUserId());
        assertEquals(100L, saved.getGithubRepoId());
        assertEquals("vijay", saved.getOwner());
        assertEquals("hello", saved.getName());
        assertEquals("vijay/hello", saved.getFullName());
        assertTrue(saved.isPrivate());
        assertEquals("develop", saved.getDefaultBranch());
        assertEquals("Java", saved.getLanguage());
        assertEquals("https://github.com/vijay/hello", saved.getHtmlUrl());
        assertEquals("My first repo", saved.getDescription());
        assertNotNull(saved.getUpdatedAt());

        assertEquals(1, result.size());
    }

    @Test
    void syncAndListRepos_shouldUseDefaults_whenOptionalFieldMissing(){
        UUID userId = UUID.randomUUID();
        User user = new User();

        Map<String,Object> remote = Map.of(
                "id",7L,
                "full_name","vijay/minimal",
                "name","minimal"
        );
        when(userService.requiredById(userId)).thenReturn(user);
        when(userService.decryptAccessToken(user)).thenReturn("plain-token");
        when(githubApiClient.listUserRepos("plain-token")).thenReturn(List.of(remote));
        when(repoRepository.findByUserIdAndGithubRepoId(userId,7L)).thenReturn(Optional.empty());
        when(repoRepository.save(any(Repository.class))).thenAnswer(i -> i.getArgument(0));

        repoService.syncAndListRepos(userId);

        ArgumentCaptor<Repository> captor = ArgumentCaptor.forClass(Repository.class);
        verify(repoRepository).save(captor.capture());
        Repository saved = captor.getValue();

        assertEquals("main",saved.getDefaultBranch());
        assertFalse(saved.isPrivate());
        assertNull(saved.getOwner());
        assertNull(saved.getLanguage());
        assertNull( saved.getHtmlUrl());
        assertNull( saved.getDescription());

    }

    @Test
    void syncAndListRepos_shouldUpdateExistingRepo_whenAlreadyInDatabase(){
        // Arrange
        UUID userId = UUID.randomUUID();
        User user = new User();

        Repository existing = new Repository();
        existing.setName("old-name");
        existing.setFullName("vijay/old-name");

        Map<String,Object>  remote = Map.of(
                "id",100L,
                "full_name","vijay/new-name",
                "name","new-name"
        );

        when(userService.requiredById(userId)).thenReturn(user);
        when(userService.decryptAccessToken(user)).thenReturn("plain-token");
        when(githubApiClient.listUserRepos("plain-token")).thenReturn(List.of(remote));
        when(repoRepository.findByUserIdAndGithubRepoId(userId,100L)).thenReturn(Optional.of(existing));
        when(repoRepository.save(any(Repository.class))).thenAnswer(i -> i.getArgument(0));

        //Act
        repoService.syncAndListRepos(userId);

        //Assert
        ArgumentCaptor<Repository> captor = ArgumentCaptor.forClass(Repository.class);
        verify(repoRepository).save(captor.capture());

        assertSame(existing, captor.getValue());
        assertEquals("new-name",existing.getName());
        assertEquals("vijay/new-name", existing.getFullName());

    }

    @Test
    void listStored_shouldReturnStoredRepos(){
        UUID userId = UUID.randomUUID();
        Repository repo = new Repository();
        repo.setFullName("vijay/hello");

        when(repoRepository.findByUserIdOrderByFullNameAsc(userId)).thenReturn(List.of(repo));

        //Act
        List<RepositoryResponse> result = repoService.listStored(userId);

        //Assert
        assertEquals(1, result.size());
        verify(repoRepository).findByUserIdOrderByFullNameAsc(userId);
    }


    @Test
    void requireOwned_shouldThrowNotFound_whenRepoNotOwnedByUser(){
        // Arrange
        UUID userId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();

        when(repoRepository.findByIdAndUserId(repoId,userId)).thenReturn(Optional.empty());

        //Act + Assert
        NotFoundException ex = assertThrows(
                NotFoundException.class,
                () -> repoService.requireOwned(repoId, userId)
        );
        assertEquals("Repository not found", ex.getMessage());
    }


}
