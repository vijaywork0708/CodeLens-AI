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
import org.junit.jupiter.api.Assertions.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class userServiceTest {

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
}

