package com.example.eshop.dto;

import com.example.eshop.entity.Role;
import com.example.eshop.entity.User;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

/** DTO để không bao giờ đưa chuỗi hash mật khẩu ra form. */
@Getter @Setter
public class UserForm {

    private Long id;

    @NotBlank(message = "Nhập tên đăng nhập")
    @Size(min = 4, max = 50)
    private String username;

    @Size(max = 50)
    private String password;        // để trống khi sửa = giữ nguyên

    @NotBlank(message = "Nhập họ tên")
    @Size(max = 100, message = "Họ tên tối đa 100 ký tự")
    private String fullName;

    @NotBlank @Email(message = "Email không hợp lệ")
    @Size(max = 100, message = "Email tối đa 100 ký tự")
    private String email;

    private String phone;

    @Size(max = 255, message = "Địa chỉ tối đa 255 ký tự")
    private String address;

    @NotNull
    private Role role = Role.ROLE_CUSTOMER;

    private boolean enabled = true;

    public static UserForm from(User u) {
        UserForm f = new UserForm();
        f.setId(u.getId());
        f.setUsername(u.getUsername());
        f.setFullName(u.getFullName());
        f.setEmail(u.getEmail());
        f.setPhone(u.getPhone());
        f.setAddress(u.getAddress());
        f.setRole(u.getRole());
        f.setEnabled(u.isEnabled());
        return f;
    }
}
