package com.vvttai.smart_link_shortener.modules.admin.controller;

import com.vvttai.smart_link_shortener.modules.admin.dto.AdminDashboardResponse;
import com.vvttai.smart_link_shortener.modules.admin.dto.AdminLinkResponse;
import com.vvttai.smart_link_shortener.modules.admin.dto.AdminUserResponse;
import com.vvttai.smart_link_shortener.modules.admin.service.AdminService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST Controller cho Admin Panel.
 * Tất cả endpoint đều yêu cầu ROLE_ADMIN (đã cấu hình trong SecurityConfig).
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    // ======================== Dashboard ========================

    @GetMapping("/dashboard")
    public ResponseEntity<AdminDashboardResponse> getDashboard() {
        return ResponseEntity.ok(adminService.getDashboardStats());
    }

    // ======================== User Management ========================

    @GetMapping("/users")
    public ResponseEntity<Page<AdminUserResponse>> getUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(adminService.getUsers(page, size, search));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<?> getUserDetail(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(adminService.getUserDetail(id));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PatchMapping("/users/{id}/lock")
    public ResponseEntity<?> toggleLock(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(adminService.toggleLock(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id) {
        try {
            adminService.softDeleteUser(id);
            return ResponseEntity.ok(Map.of("message", "Đã xoá tài khoản thành công (soft-delete)"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // ======================== Link Management ========================

    @GetMapping("/links")
    public ResponseEntity<Page<AdminLinkResponse>> getLinks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long userId) {
        return ResponseEntity.ok(adminService.getLinks(page, size, search, userId));
    }

    @PatchMapping("/links/{id}/active")
    public ResponseEntity<?> toggleLinkActive(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(adminService.toggleLinkActive(id));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/links/{id}")
    public ResponseEntity<?> deleteLink(@PathVariable Long id) {
        try {
            adminService.deleteLink(id);
            return ResponseEntity.ok(Map.of("message", "Đã xoá link thành công"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
