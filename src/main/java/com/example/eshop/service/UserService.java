package com.example.eshop.service;

import com.example.eshop.dto.RegisterForm;
import com.example.eshop.dto.UserForm;
import com.example.eshop.entity.Role;
import com.example.eshop.entity.User;
import com.example.eshop.exception.NotFoundException;
import com.example.eshop.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy tài khoản " + username));
    }

    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy tài khoản id = " + id));
    }

    public List<User> findAll() {
        return userRepository.findAll();
    }

    /** GUEST tự đăng ký → luôn là CUSTOMER. */
    @Transactional
    public User register(RegisterForm f) {
        if (userRepository.existsByUsername(f.getUsername()))
            throw new IllegalArgumentException("Tên đăng nhập đã tồn tại");
        if (userRepository.existsByEmail(f.getEmail()))
            throw new IllegalArgumentException("Email đã được sử dụng");

        User u = new User();
        u.setUsername(f.getUsername());
        u.setPassword(passwordEncoder.encode(f.getPassword()));   // KHÔNG lưu mật khẩu thô
        u.setFullName(f.getFullName());
        u.setEmail(f.getEmail());
        u.setPhone(f.getPhone());
        u.setAddress(f.getAddress());
        u.setRole(Role.ROLE_CUSTOMER);
        u.setEnabled(true);
        return userRepository.save(u);
    }

    /** SELLER tạo / sửa tài khoản bất kỳ. Để trống mật khẩu khi sửa = giữ mật khẩu cũ. */
    @PreAuthorize("hasRole('SELLER')")
    @Transactional
    public User saveByAdmin(UserForm f) {
        User u;
        if (f.getId() == null) {
            if (!StringUtils.hasText(f.getPassword()))
                throw new IllegalArgumentException("Nhập mật khẩu cho tài khoản mới");
            if (userRepository.existsByUsername(f.getUsername()))
                throw new IllegalArgumentException("Tên đăng nhập đã tồn tại");
            u = new User();
            u.setUsername(f.getUsername());
        } else {
            u = findById(f.getId());
        }
        u.setFullName(f.getFullName());
        u.setEmail(f.getEmail());
        u.setPhone(f.getPhone());
        u.setAddress(f.getAddress());
        u.setRole(f.getRole());
        u.setEnabled(f.isEnabled());
        if (StringUtils.hasText(f.getPassword())) {
            u.setPassword(passwordEncoder.encode(f.getPassword()));
        }
        return userRepository.save(u);
    }

    @PreAuthorize("hasRole('SELLER')")
    @Transactional
    public void delete(Long id, String currentUsername) {
        User u = findById(id);
        if (u.getUsername().equals(currentUsername))
            throw new IllegalStateException("Không thể tự xoá tài khoản đang đăng nhập");
        userRepository.delete(u);   // orders.user_id → NULL (ON DELETE SET NULL)
    }
}
