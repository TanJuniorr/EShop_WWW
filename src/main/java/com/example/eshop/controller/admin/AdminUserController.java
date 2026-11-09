package com.example.eshop.controller.admin;

import com.example.eshop.dto.UserForm;
import com.example.eshop.entity.Role;
import com.example.eshop.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

@Controller
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("users", userService.findAll());
        return "admin/user/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        return showForm(model, new UserForm());
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        return showForm(model, UserForm.from(userService.findById(id)));
    }

    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("userForm") UserForm form, BindingResult result,
                       Model model, RedirectAttributes ra) {
        if (result.hasErrors()) return showForm(model, form);
        try {
            userService.saveByAdmin(form);
        } catch (IllegalArgumentException e) {
            result.reject("save", e.getMessage());
            return showForm(model, form);
        } catch (DataIntegrityViolationException e) {
            result.reject("save", "Email đã được sử dụng");
            return showForm(model, form);
        }
        ra.addFlashAttribute("message", "Đã lưu tài khoản " + form.getUsername());
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, Principal principal, RedirectAttributes ra) {
        try {
            userService.delete(id, principal.getName());
            ra.addFlashAttribute("message", "Đã xoá tài khoản");
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }

    private String showForm(Model model, UserForm form) {
        model.addAttribute("userForm", form);
        model.addAttribute("roles", Role.values());
        return "admin/user/form";
    }
}
