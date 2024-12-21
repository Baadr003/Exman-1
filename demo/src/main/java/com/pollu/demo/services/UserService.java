// UserService.java
package com.pollu.demo.services;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lombok.extern.slf4j.Slf4j;

import com.pollu.demo.dto.*;
import com.pollu.demo.entities.*;
import com.pollu.demo.repositories.*;

import java.util.Collections;
import java.util.List;
import java.time.LocalDateTime;

@Service
@Transactional
@Slf4j
public class UserService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private PasswordEncoder passwordEncoder;
    
    @Autowired
    private AlertHistoryRepository alertHistoryRepository;

    @Autowired
    private EmailService emailService;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return new org.springframework.security.core.userdetails.User(
            user.getUsername(), 
            user.getPassword(), 
            Collections.singletonList(new SimpleGrantedAuthority(user.getRole().name()))
        );
    }

    public User registerUser(UserDTO userDTO) {
        log.info("Starting user registration process for username: {}", userDTO.getUsername());
        
        if (userRepository.findByUsername(userDTO.getUsername()).isPresent()) {
            log.warn("Username already exists: {}", userDTO.getUsername());
            throw new RuntimeException("Username already exists");
        }

        User user = new User();
        user.setUsername(userDTO.getUsername());
        user.setEmail(userDTO.getEmail());
        user.setPassword(passwordEncoder.encode(userDTO.getPassword()));
        user.setRole(Role.USER);
        user.setVerified(false);
        
        // Generate and set verification code
        String verificationCode = emailService.generateVerificationCode();
        log.info("Generated verification code for user: {}", userDTO.getEmail());
        
        user.setVerificationCode(verificationCode);
        user.setVerificationCodeExpiry(LocalDateTime.now().plusMinutes(15));
        user.setVerificationAttempts(0);

        if (userDTO.getPreferences() != null) {
            user.setAqiThreshold(userDTO.getPreferences().getAqiThreshold());
            user.setEmailNotificationsEnabled(userDTO.getPreferences().getEmailNotificationsEnabled());
            user.setAppNotificationsEnabled(userDTO.getPreferences().getAppNotificationsEnabled());
        }

        try {
            User savedUser = userRepository.save(user);
            log.info("User saved successfully with ID: {}", savedUser.getId());

            try {
                emailService.sendVerificationCode(user.getEmail(), user.getUsername(), verificationCode);
                log.info("Verification email sent successfully to: {}", user.getEmail());
            } catch (Exception e) {
                log.error("Failed to send verification email: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to send verification email: " + e.getMessage());
            }
            
            return savedUser;
        } catch (Exception e) {
            log.error("Error during user registration: {}", e.getMessage(), e);
            throw new RuntimeException("Registration failed: " + e.getMessage());
        }
    }

    public User authenticateUser(AuthRequestDTO authRequest) {
        User user = userRepository.findByUsername(authRequest.getUsername())
                .orElseThrow(() -> new RuntimeException("Utilisateur introuvable"));

        if (!passwordEncoder.matches(authRequest.getPassword(), user.getPassword())) {
            throw new RuntimeException("Mot de passe incorrect");
        }

        if (!user.getVerified()) {
            throw new RuntimeException("Compte non vérifié. Veuillez vérifier votre email.");
        }
        
        return user;
    }

    public void verifyUser(String email, String code) {
        log.info("Attempting to verify user email: {}", email);
        
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> {
                log.error("User not found with email: {}", email);
                return new RuntimeException("Utilisateur non trouvé");
            });

        if (user.getVerified()) {
            log.warn("Account already verified for email: {}", email);
            throw new RuntimeException("Compte déjà vérifié");
        }

        if (user.getVerificationAttempts() >= 3) {
            log.warn("Too many verification attempts for email: {}", email);
            throw new RuntimeException("Trop de tentatives. Veuillez demander un nouveau code");
        }

        if (user.getVerificationCodeExpiry().isBefore(LocalDateTime.now())) {
            log.warn("Verification code expired for email: {}", email);
            throw new RuntimeException("Code expiré. Veuillez demander un nouveau code");
        }

        if (!user.getVerificationCode().equals(code)) {
            user.setVerificationAttempts(user.getVerificationAttempts() + 1);
            userRepository.save(user);
            log.warn("Invalid verification code attempt for email: {}", email);
            throw new RuntimeException("Code incorrect");
        }

        user.setVerified(true);
        user.setVerificationCode(null);
        user.setVerificationCodeExpiry(null);
        user.setVerificationAttempts(0);
        userRepository.save(user);
        log.info("User verified successfully: {}", email);
    }

    public void resendVerificationCode(String email) {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé"));

        if (user.getVerified()) {
            throw new RuntimeException("Compte déjà vérifié");
        }

        String newCode = emailService.generateVerificationCode();
        user.setVerificationCode(newCode);
        user.setVerificationCodeExpiry(LocalDateTime.now().plusMinutes(15));
        user.setVerificationAttempts(0);
        userRepository.save(user);

        emailService.sendVerificationCode(user.getEmail(), user.getUsername(), newCode);
    }

    public User getUserFromAuthentication(Authentication authentication) {
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé"));
    }

    public User updatePreferences(Long userId, UserPreferencesDTO preferencesDTO) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé"));

        user.setAqiThreshold(preferencesDTO.getAqiThreshold());
        user.setEmailNotificationsEnabled(preferencesDTO.getEmailNotificationsEnabled());
        user.setAppNotificationsEnabled(preferencesDTO.getAppNotificationsEnabled());

        return userRepository.save(user);
    }
    public UserDetailsDTO getUserDetails(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé"));
    UserDetailsDTO detailsDTO = new UserDetailsDTO();
    detailsDTO.setUsername(user.getUsername());
    detailsDTO.setEmail(user.getEmail());
    
    UserPreferencesDTO preferencesDTO = new UserPreferencesDTO();
    preferencesDTO.setAqiThreshold(user.getAqiThreshold());
    preferencesDTO.setEmailNotificationsEnabled(user.getEmailNotificationsEnabled());
    preferencesDTO.setAppNotificationsEnabled(user.getAppNotificationsEnabled());
    
    detailsDTO.setPreferences(preferencesDTO);
    return detailsDTO;
}

    public List<AlertHistory> getUserAlertHistory(Long userId) {
        List<AlertHistory> alerts = alertHistoryRepository.findByUserIdOrderByTimestampDesc(userId);
        log.info("Found {} alerts for user {}", alerts.size(), userId);
        alerts.forEach(alert -> log.debug("Alert: {}", alert));
        return alerts;
    }

    
    
}