package com.vijay.userservice.controller;
import com.vijay.userservice.dto.LoginRequest;
import com.vijay.userservice.service.UserService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final UserService userService;
    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestBody LoginRequest dto, HttpServletRequest httprequest){
        return userService.login(dto, httprequest);
    }
}
