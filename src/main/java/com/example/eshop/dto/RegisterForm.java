package com.example.eshop.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class RegisterForm {

    @NotBlank(message = "Nhập tên đăng nhập")
    @Size(min = 4, max = 50, message = "Từ 4 đến 50 ký tự")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "Chỉ gồm chữ không dấu, số, gạch dưới")
    private String username;

    @NotBlank(message = "Nhập mật khẩu")
    @Size(min = 6, max = 50, message = "Mật khẩu từ 6 ký tự")
    private String password;

    @NotBlank(message = "Nhập lại mật khẩu")
    private String confirmPassword;

    @NotBlank(message = "Nhập họ tên")
    @Size(max = 100, message = "Họ tên tối đa 100 ký tự")
    private String fullName;

    @NotBlank(message = "Nhập email")
    @Email(message = "Email không hợp lệ")
    @Size(max = 100, message = "Email tối đa 100 ký tự")
    private String email;

    @Pattern(regexp = "^$|^0\\d{9}$", message = "SĐT gồm 10 số, bắt đầu bằng 0")
    private String phone;

    @Size(max = 255, message = "Địa chỉ tối đa 255 ký tự")
    private String address;
}
