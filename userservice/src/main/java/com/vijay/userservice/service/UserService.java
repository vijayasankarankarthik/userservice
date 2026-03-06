package com.vijay.userservice.service;

import com.vijay.userservice.dto.UserRequest;
import com.vijay.userservice.dto.LoginRequest;
import com.vijay.userservice.entity.User;
import com.vijay.userservice.security.JwtUtil;
import com.vijay.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.multipart.MultipartFile;
import jakarta.transaction.Transactional;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Pattern;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    // Constants
    private static final String EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@([A-Za-z0-9.-]+\\.[A-Z|a-z]{2,})$";
    private static final Pattern EMAIL_PATTERN = Pattern.compile(EMAIL_REGEX);
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int CSV_BATCH_SIZE = 1000;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${security.rate-limit.max-attempts-per-email:5}")
    private int maxAttemptsPerEmail;

    @Value("${security.rate-limit.max-attempts-per-ip:10}")
    private int maxAttemptsPerIp;

    @Value("${security.rate-limit.lockout-duration-minutes:15}")
    private int lockoutDurationMinutes;
    /**
     * Creates a new user with validation
     * @param dto User request containing name, email, and password
     * @return Created user entity
     * @throws IllegalArgumentException if input validation fails
     */
    public User createUser(UserRequest dto) {
        validateUserInput(dto);

        if (userRepository.findByEmail(dto.getEmail()).isPresent()) {
            log.warn("Attempt to create user with existing email: {}", dto.getEmail());
            throw new IllegalArgumentException("Email already in use");
        }

        User user = new User();
        user.setName(dto.getName().trim());
        user.setEmail(dto.getEmail().trim().toLowerCase());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));

        log.info("Creating new user with email: {}", user.getEmail());
        return userRepository.save(user);
    }

    /**
     * Validates user input data
     */
    private void validateUserInput(UserRequest dto) {
        if (dto == null || dto.getName() == null || dto.getEmail() == null || dto.getPassword() == null) {
            throw new IllegalArgumentException("Name, email, and password are required");
        }

        String name = dto.getName().trim();
        String email = dto.getEmail().trim();
        String password = dto.getPassword();

        if (name.isEmpty() || name.length() < 2 || name.length() > 100) {
            throw new IllegalArgumentException("Name must be between 2 and 100 characters");
        }

        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("Invalid email format");
        }

        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }
    /**
     * Updates user name and email (password change requires separate method)
     * @param id User ID
     * @param dto Updated user data
     * @return Updated user entity
     * @throws IllegalArgumentException if user not found or validation fails
     */
    public User update(Long id, UserRequest dto) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Invalid user ID");
        }

        validateUserInput(dto);

        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + id));

        user.setName(dto.getName().trim());
        user.setEmail(dto.getEmail().trim().toLowerCase());
        // Note: Password is intentionally not updated here. Use changePassword() method for that.

        log.info("Updating user with ID: {}", id);
        return userRepository.save(user);
    }

    /**
     * Changes user password
     * @param id User ID
     * @param oldPassword Current password
     * @param newPassword New password
     * @throws IllegalArgumentException if password is invalid
     */
    public void changePassword(Long id, String oldPassword, String newPassword) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Invalid user ID");
        }

        if (oldPassword == null || oldPassword.isEmpty() || newPassword == null || newPassword.isEmpty()) {
            throw new IllegalArgumentException("Old and new passwords are required");
        }

        if (newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("New password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }

        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + id));

        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            log.warn("Failed password change attempt for user ID: {}", id);
            throw new IllegalArgumentException("Current password is incorrect");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("Password changed for user ID: {}", id);
    }
    /**
     * Deletes a user by ID
     * @param id User ID to delete
     * @throws IllegalArgumentException if user not found
     */
    public void deleteUser(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Invalid user ID");
        }

        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + id));

        userRepository.deleteById(id);
        log.info("User deleted with ID: {}", id);
    }
    /**
     * Authenticates user with rate limiting protection
     * @param request Login credentials
     * @param httpRequest HTTP request for IP extraction
     * @return JWT token in response body with appropriate HTTP status
     */
    public ResponseEntity<String> login(LoginRequest request, HttpServletRequest httpRequest) {
        if (request == null || request.getEmail() == null || request.getPassword() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Email and password are required");
        }

        String email = request.getEmail().trim().toLowerCase();
        String ip = extractClientIp(httpRequest);
        String ipKey = "loginAttempts:ip:" + ip;
        String emailKey = "loginAttempts:email:" + email;

        // Check IP-based rate limit
        Integer ipAttempts = getLoginAttempts(ipKey);
        if (ipAttempts >= maxAttemptsPerIp) {
            long ttlMs = redisTemplate.getExpire(ipKey);
            long minutesLeft = ttlMs / 60_000;
            log.warn("Too many login attempts from IP: {}. Attempts: {}", ip, ipAttempts);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body("Too many login attempts from this IP. Please try again after " + minutesLeft + " minutes");
        }

        // Check email-based rate limit
        Integer emailAttempts = getLoginAttempts(emailKey);
        if (emailAttempts >= maxAttemptsPerEmail) {
            long ttlMs = redisTemplate.getExpire(emailKey);
            long minutesLeft = ttlMs / 60_000;
            log.warn("Too many login attempts for email: {}. Attempts: {}", email, emailAttempts);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body("Too many login attempts. Please try again after " + minutesLeft + " minutes");
        }

        // Verify user credentials
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            redisTemplate.opsForValue().set(emailKey, String.valueOf(emailAttempts + 1),
                    Duration.ofMinutes(lockoutDurationMinutes));
            redisTemplate.opsForValue().set(ipKey, String.valueOf(ipAttempts + 1),
                    Duration.ofMinutes(lockoutDurationMinutes));
            log.warn("Failed login attempt for email: {} from IP: {}", email, ip);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid email or password");
        }

        // Clear rate limit counters on successful login
        redisTemplate.delete(emailKey);
        redisTemplate.delete(ipKey);

        String token = jwtUtil.generateToken(user.getEmail());
        log.info("User logged in successfully: {}", email);
        return ResponseEntity.status(HttpStatus.OK).body(token);
    }

    /**
     * Extracts client IP address from HTTP request
     */
    private String extractClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // In case of multiple IPs, take the first one
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    /**
     * Gets current login attempt count from Redis
     */
    private Integer getLoginAttempts(String key) {
        String value = redisTemplate.opsForValue().get(key);
        return value == null ? 0 : Integer.parseInt(value);
    }
    /**
     * Uploads users from CSV file with validation and batch processing
     * @param file CSV file containing user data (columns: name, email)
     * @throws IllegalArgumentException if file is invalid or contains bad data
     * @throws IOException if file cannot be read
     */
    @Transactional
    public void uploadCsv(MultipartFile file) throws IOException {
        long startTime = System.currentTimeMillis();
        int rowCount = 0;

        validateCsvFile(file);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()));
             CSVReader csvReader = new CSVReader(reader)) {

            List<String[]> batch = new ArrayList<>();
            String[] nextRecord;
            boolean isFirstRow = true;

            while ((nextRecord = csvReader.readNext()) != null) {
                // Skip header row
                if (isFirstRow) {
                    validateCsvHeader(nextRecord);
                    isFirstRow = false;
                    continue;
                }

                // Validate row data
                validateCsvRow(nextRecord, rowCount + 1);
                batch.add(nextRecord);
                rowCount++;

                // Insert batch when size reached
                if (batch.size() >= CSV_BATCH_SIZE) {
                    insertUserBatch(batch);
                    batch.clear();
                }
            }

            // Insert remaining records
            if (!batch.isEmpty()) {
                insertUserBatch(batch);
            }

            long endTime = System.currentTimeMillis();
            long durationMs = endTime - startTime;
            double throughput = rowCount / (durationMs / 1000.0);

            log.info("CSV upload completed. Rows processed: {}, Duration: {} ms, Throughput: {} rows/sec",
                    rowCount, durationMs, String.format("%.2f", throughput));

        } catch (CsvException e) {
            log.error("CSV parsing error", e);
            throw new IllegalArgumentException("Invalid CSV format: " + e.getMessage(), e);
        }
    }

    /**
     * Validates CSV file before processing
     */
    private void validateCsvFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size exceeds maximum allowed size of 10 MB");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.endsWith(".csv")) {
            throw new IllegalArgumentException("File must be a CSV file");
        }
    }

    /**
     * Validates CSV header row
     */
    private void validateCsvHeader(String[] header) {
        if (header == null || header.length < 2) {
            throw new IllegalArgumentException("CSV must have at least 2 columns (name, email)");
        }

        if (!"name".equalsIgnoreCase(header[0].trim()) || !"email".equalsIgnoreCase(header[1].trim())) {
            throw new IllegalArgumentException("CSV header must be: name, email");
        }
    }

    /**
     * Validates a CSV data row
     */
    private void validateCsvRow(String[] row, int rowNumber) {
        if (row == null || row.length < 2) {
            throw new IllegalArgumentException("Row " + rowNumber + " has insufficient columns");
        }

        String name = row[0].trim();
        String email = row[1].trim();

        if (name.isEmpty() || name.length() < 2 || name.length() > 100) {
            throw new IllegalArgumentException("Row " + rowNumber + ": Name must be between 2 and 100 characters");
        }

        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("Row " + rowNumber + ": Invalid email format: " + email);
        }
    }

    /**
     * Inserts a batch of users into database with optimized performance
     */
    private void insertUserBatch(List<String[]> batch) {
        if (batch.isEmpty()) {
            return;
        }

        // Extract emails for bulk duplicate check
        List<String> emailsToCheck = new ArrayList<>();
        for (String[] record : batch) {
            emailsToCheck.add(record[1].trim().toLowerCase());
        }

        // Single query to check all duplicates at once (much faster!)
        List<String> existingEmails = userRepository.findEmailsByIgnoreCase(emailsToCheck);
        Set<String> existingEmailSet = new HashSet<>(existingEmails);

        // Pre-encode password once (reduces CPU usage dramatically)
        String defaultPassword = passwordEncoder.encode("DefaultPassword123!");

        // Build list of users to insert
        List<User> usersToInsert = new ArrayList<>();
        for (String[] record : batch) {
            String name = record[0].trim();
            String email = record[1].trim().toLowerCase();

            // Skip if email already exists
            if (existingEmailSet.contains(email)) {
                log.debug("Skipping duplicate email: {}", email);
                continue;
            }

            User user = new User();
            user.setName(name);
            user.setEmail(email);
            user.setPassword(defaultPassword);
            usersToInsert.add(user);
        }

        // Bulk insert all users at once (much faster than individual saves!)
        if (!usersToInsert.isEmpty()) {
            userRepository.saveAll(usersToInsert);
            log.debug("Inserted {} users in batch", usersToInsert.size());
        }
    }
}
