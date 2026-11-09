package com.example.eshop.controller;

import com.example.eshop.dto.RegisterForm;
import com.example.eshop.security.JwtService;
import com.example.eshop.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserService userService;

    @GetMapping("/login")
    public String loginPage() {
        return "auth/login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String username, @RequestParam String password,
                        HttpServletResponse response) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));
            UserDetails user = (UserDetails) auth.getPrincipal();

            ResponseCookie cookie = ResponseCookie.from(JwtService.COOKIE_NAME, jwtService.generateToken(user))
                    .httpOnly(true)
                    .path("/")
                    .maxAge(jwtService.getExpirationSeconds())
                    .sameSite("Lax")
                    // .secure(true)   // bật khi chạy HTTPS
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

            boolean isSeller = user.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_SELLER"));
            return isSeller ? "redirect:/admin/products" : "redirect:/";
        } catch (DisabledException e) {
            return "redirect:/login?error=disabled";
        } catch (AuthenticationException e) {
            return "redirect:/login?error=bad";
        }
    }

    @GetMapping("/register")
    public String registerForm(Model model) {
        model.addAttribute("form", new RegisterForm());
        return "auth/register";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("form") RegisterForm form, BindingResult result,
                           RedirectAttributes ra) {
        if (form.getPassword() != null && !form.getPassword().equals(form.getConfirmPassword())) {
            result.rejectValue("confirmPassword", "mismatch", "Mật khẩu nhập lại không khớp");
        }
        if (result.hasErrors()) {
            return "auth/register";
        }
        try {
            userService.register(form);
        } catch (IllegalArgumentException e) {
            result.reject("register", e.getMessage());
            return "auth/register";
        }
        ra.addFlashAttribute("message", "Đăng ký thành công, mời bạn đăng nhập");
        return "redirect:/login";
    }
}
