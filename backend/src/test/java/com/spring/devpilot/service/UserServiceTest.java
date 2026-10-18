package com.spring.devpilot.service;


import com.spring.devpilot.entity.User;
import com.spring.devpilot.repository.UserRepository;
import com.spring.devpilot.services.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.encrypt.TextEncryptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TextEncryptor textEncryptor;

    @InjectMocks
    private UserService userService;

    @Test
    void upsertFromGithub_shouldCreateNewUser_whenGithubNotFound(){
        // Arrange
        Map<String, Object> attributes = Map.of(
                "id",12345,
                "login","vijay",
                "name","The Vijay",
                "avatar_url","https://avatars.test/vijay.png"
        );

        when(userRepository.findByGithubId(12345L)).thenReturn(Optional.empty());
        when(textEncryptor.encrypt("plain-token")).thenReturn("encrypted-token");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        User result = userService.upsertFromGithub(attributes,"plain-token","read:user,repo");

        //Assert
        assertEquals(12345L,result.getGithubId());
        assertEquals("vijay",result.getGithubUsername());
        assertEquals("The Vijay", result.getDisplayName());
        assertEquals("https://avatars.test/vijay.png",result.getAvatarUrl());
        assertEquals("encrypted-token",result.getAccessToken());
        assertEquals("read:user,repo",result.getTokenScopes());

        verify(userRepository).save(any(User.class));
    }

    @Test
    void upsertFromGithub_shouldUseLoginAsName_whenNameIsMissing(){
        //Arrange
        Map<String,Object> attributes = Map.of(
                "id",1L,
                "login","vijay"
        );

        when(userRepository.findByGithubId(1L)).thenReturn(Optional.empty());
        when(textEncryptor.encrypt("plain-token")).thenReturn("encrypted-token");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = userService.upsertFromGithub(attributes,"plain-token","repo");

        assertEquals("vijay",result.getDisplayName());
        assertNull(result.getAvatarUrl());
    }

    @Test
    void upsertFromGithub_shouldUpdateExistingUser_whenGithubNotFound(){
        //Arrange
        User existing = new User();
        existing.setGithubId(99L);
        existing.setGithubUsername("old-login");
        existing.setDisplayName("Old Name");

        Map<String,Object> attributes = Map.of(
                "id",99L,
                "login","new-login",
                "name","New Name"
        );

        when(userRepository.findByGithubId(99L)).thenReturn(Optional.of(existing));
        when(textEncryptor.encrypt("new-token")).thenReturn("encrypted-token");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        //Act
        User result = userService.upsertFromGithub(attributes,"new-token","repo");

        assertSame(existing,result);

        assertEquals("new-login",result.getGithubUsername());
        assertEquals("New Name",result.getDisplayName());
        assertEquals("encrypted-token",result.getAccessToken());

    }

    @Test
    void requiredById_shouldReturnUser_whenUserExists(){
        //Arrange
        UUID id = UUID.randomUUID();
        User user = new User();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        //Act
        User result = userService.requiredById(id);
        assertSame(user,result);
    }

    @Test
    void requiredById_shouldThrowException_whenUserDoesNotExist(){
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        // Act + Assert
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> userService.requiredById(id)
        );
        assertEquals("User not found",ex.getMessage());

    }

    @Test
    void decryptAccessToken_shouldReturnDecryptedToken(){
        User user = new User();
        user.setAccessToken("encrypted-token");

        when(textEncryptor.decrypt("encrypted-token")).thenReturn("plain-token");

        String result = userService.decryptAccessToken(user);

        assertEquals("plain-token",result);

    }
}

