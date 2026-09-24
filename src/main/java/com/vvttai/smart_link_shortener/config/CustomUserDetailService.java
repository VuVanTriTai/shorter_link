package com.vvttai.smart_link_shortener.config;

import com.vvttai.smart_link_shortener.modules.user.entity.User;
import com.vvttai.smart_link_shortener.modules.user.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collections;
//tra cứu hồ sơ /database
@Service
public class CustomUserDetailService implements UserDetailsService {
    private final UserRepository userRepository;

    public CustomUserDetailService(UserRepository userRepository){
        this.userRepository=userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user =userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("not find :"+username));
        String role = (user.getRoles() != null && !user.getRoles().isBlank()) ? user.getRoles() : "ROLE_USER";
        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                Collections.singletonList(new SimpleGrantedAuthority(role)));
    }
}
