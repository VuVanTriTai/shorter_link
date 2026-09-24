package com.vvttai.smart_link_shortener.modules.link.controller;

import com.vvttai.smart_link_shortener.modules.link.dto.CreateLinkRequest;
import com.vvttai.smart_link_shortener.modules.link.dto.LinkResponse;
import com.vvttai.smart_link_shortener.modules.link.dto.UpdateLinkRequest;
import com.vvttai.smart_link_shortener.modules.link.service.LinkService;
import jakarta.validation.Valid;
import org.apache.kafka.common.protocol.types.Field;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/links")
public class LinkController {
    private final LinkService linkService;

    public LinkController(LinkService linkService) {
        this.linkService = linkService;
    }

    @PostMapping
    public ResponseEntity<?> createLink(
            @Valid @RequestBody CreateLinkRequest request,
            Principal connectedUser) {
        try {
            LinkResponse response = linkService.createShortLink(request, connectedUser);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }

    }

    @GetMapping
    public ResponseEntity<?> getMyLink(
            Principal connectedUser) {
        List<LinkResponse> link = linkService.getUserLinks(connectedUser);
        return ResponseEntity.ok(link);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteLink(@PathVariable Long id, Authentication authentication) {
        String currentUsername = authentication.getName();
        linkService.deleteLink(id, currentUsername);
        return ResponseEntity.ok("Xoá link thành công");
    }

    @PutMapping("/{shortCode}")
    public ResponseEntity<LinkResponse> updateLink(
            @PathVariable String shortCode,
            @Valid @RequestBody UpdateLinkRequest request,
            Principal connectedUser) {
        LinkResponse response = linkService.updateShortLink(shortCode, request, connectedUser.getName());
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/expiration")
    public ResponseEntity<LinkResponse> setExpiration(
            @PathVariable Long id,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) LocalDateTime expiresAt,
            Principal connectedUser) {
        LinkResponse response = linkService.setExpirationDate(id, expiresAt, connectedUser.getName());
        return ResponseEntity.ok(response);
    }

}
