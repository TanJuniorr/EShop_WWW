package com.example.eshop.dto;

import com.example.eshop.entity.PaymentMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class CheckoutForm {

    @NotBlank(message = "Vui lòng nhập họ tên")
    @Size(max = 100)
    private String customerName;

    @NotBlank(message = "Vui lòng nhập số điện thoại")
    @Pattern(regexp = "^0\\d{9}$", message = "Số điện thoại gồm 10 chữ số, bắt đầu bằng 0")
    private String phone;

    @NotBlank(message = "Vui lòng nhập địa chỉ giao hàng")
    @Size(max = 255)
    private String address;

    @Size(max = 255, message = "Ghi chú tối đa 255 ký tự")
    private String note;

    @NotNull(message = "Chọn phương thức thanh toán")
    private PaymentMethod paymentMethod = PaymentMethod.COD;
}
