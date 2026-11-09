-- =====================================================================
-- eShop.sql – tạo CSDL eshop cho MariaDB 10.6+
-- Mật khẩu của mọi tài khoản mẫu: 123456 (BCrypt)
-- =====================================================================
DROP DATABASE IF EXISTS eshop;
CREATE DATABASE eshop CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE eshop;

-- ---------------------------------------------------------------------
-- Bảng
-- ---------------------------------------------------------------------
CREATE TABLE category (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(255)
) ENGINE = InnoDB;

CREATE TABLE product (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(150)   NOT NULL,
    description VARCHAR(500),
    price       DECIMAL(15, 2) NOT NULL,
    quantity    INT            NOT NULL DEFAULT 0,
    image_url   VARCHAR(500),
    category_id BIGINT         NOT NULL,
    created_at  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category (id)
) ENGINE = InnoDB;

CREATE TABLE users (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    username   VARCHAR(50)  NOT NULL UNIQUE,
    password   VARCHAR(100) NOT NULL,
    full_name  VARCHAR(100) NOT NULL,
    email      VARCHAR(100) NOT NULL UNIQUE,
    phone      VARCHAR(255),
    address    VARCHAR(255),
    role       VARCHAR(30)  NOT NULL DEFAULT 'ROLE_CUSTOMER',
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB;

CREATE TABLE orders (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_date     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    customer_name  VARCHAR(100)   NOT NULL,
    phone          VARCHAR(20)    NOT NULL,
    address        VARCHAR(255)   NOT NULL,
    note           VARCHAR(255),
    payment_method VARCHAR(20)    NOT NULL,
    status         VARCHAR(20)    NOT NULL DEFAULT 'NEW',
    total_amount   DECIMAL(15, 2) NOT NULL,
    user_id        BIGINT NULL,
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB;

CREATE TABLE order_detail (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id   BIGINT         NOT NULL,
    product_id BIGINT         NOT NULL,
    quantity   INT            NOT NULL,
    unit_price DECIMAL(15, 2) NOT NULL,
    CONSTRAINT fk_detail_order   FOREIGN KEY (order_id)   REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_detail_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------
-- Dữ liệu mẫu: 15 danh mục
-- ---------------------------------------------------------------------
INSERT INTO category (id, name, description) VALUES
(1,  'Điện thoại',          'Điện thoại thông minh các hãng'),
(2,  'Laptop',              'Máy tính xách tay học tập, văn phòng, gaming'),
(3,  'Máy tính bảng',       'Tablet giải trí và làm việc'),
(4,  'Đồng hồ thông minh',  'Smartwatch và vòng đeo tay theo dõi sức khoẻ'),
(5,  'Tai nghe',            'Tai nghe có dây, không dây, chống ồn'),
(6,  'Loa',                 'Loa bluetooth và loa vi tính'),
(7,  'Màn hình',            'Màn hình máy tính văn phòng và gaming'),
(8,  'Bàn phím & chuột',    'Bàn phím cơ, chuột không dây'),
(9,  'Ổ cứng & lưu trữ',    'SSD, HDD, USB, thẻ nhớ'),
(10, 'Camera',              'Camera an ninh và camera hành trình'),
(11, 'Máy chơi game',       'Console và phụ kiện chơi game'),
(12, 'Phụ kiện điện thoại', 'Sạc, cáp, ốp lưng, pin dự phòng'),
(13, 'Mạng & Wifi',         'Router, bộ phát wifi, switch'),
(14, 'Máy in',              'Máy in laser, in phun'),
(15, 'Nhà thông minh',      'Thiết bị gia dụng thông minh');

-- ---------------------------------------------------------------------
-- Dữ liệu mẫu: 50 sản phẩm
-- ---------------------------------------------------------------------
INSERT INTO product (name, description, price, quantity, image_url, category_id) VALUES
-- 1. Điện thoại (4)
('Samsung Galaxy S24',        'Điện thoại cao cấp, màn hình Dynamic AMOLED 2X 6.2 inch',     19990000, 30, 'https://placehold.co/400x300?text=Galaxy+S24', 1),
('iPhone 15 128GB',           'Chip A16 Bionic, camera 48MP, cổng USB-C',                    21990000, 25, 'https://placehold.co/400x300?text=iPhone+15', 1),
('Xiaomi 14T',                'Camera Leica, sạc nhanh 67W, màn hình 144Hz',                 12990000, 40, 'https://placehold.co/400x300?text=Xiaomi+14T', 1),
('OPPO Reno11 5G',            'Thiết kế mỏng nhẹ, camera chân dung 50MP',                    10490000, 35, 'https://placehold.co/400x300?text=Reno11', 1),
-- 2. Laptop (4)
('MacBook Air M2 13 inch',    'Chip Apple M2, RAM 8GB, SSD 256GB, pin 18 giờ',               25990000, 15, 'https://placehold.co/400x300?text=MacBook+Air+M2', 2),
('Dell Inspiron 15 3520',     'Core i5, RAM 16GB, SSD 512GB, màn hình 15.6 inch FHD',        15490000, 20, 'https://placehold.co/400x300?text=Dell+Inspiron', 2),
('Asus TUF Gaming F15',       'Core i7, RTX 4050, RAM 16GB, màn hình 144Hz',                 27990000, 10, 'https://placehold.co/400x300?text=TUF+F15', 2),
('Lenovo ThinkPad E14',       'Laptop doanh nhân bền bỉ, bàn phím tốt, Core i5',             18990000, 12, 'https://placehold.co/400x300?text=ThinkPad+E14', 2),
-- 3. Máy tính bảng (3)
('Samsung Galaxy Tab S9 FE',  'Màn hình 10.9 inch, bút S Pen kèm theo, chống nước IP68',     9490000,  18, 'https://placehold.co/400x300?text=Tab+S9+FE', 3),
('iPad Gen 10 64GB',          'Chip A14, màn hình Liquid Retina 10.9 inch',                  9990000,  22, 'https://placehold.co/400x300?text=iPad+Gen+10', 3),
('Xiaomi Pad 6',              'Màn hình 144Hz 11 inch, Snapdragon 870',                      7490000,  25, 'https://placehold.co/400x300?text=Xiaomi+Pad+6', 3),
-- 4. Đồng hồ thông minh (3)
('Samsung Galaxy Watch6',     'Theo dõi nhịp tim, giấc ngủ, đo điện tâm đồ',                 6490000,  20, 'https://placehold.co/400x300?text=Galaxy+Watch6', 4),
('Apple Watch SE 2',          'GPS, theo dõi sức khoẻ, phát hiện va chạm',                   6990000,  16, 'https://placehold.co/400x300?text=Apple+Watch+SE', 4),
('Xiaomi Smart Band 8',       'Vòng đeo tay pin 16 ngày, chống nước 5ATM',                   990000,   60, 'https://placehold.co/400x300?text=Mi+Band+8', 4),
-- 5. Tai nghe (4)
('Sony WH-1000XM5',           'Tai nghe chống ồn chủ động hàng đầu, pin 30 giờ',             7990000,  14, 'https://placehold.co/400x300?text=WH-1000XM5', 5),
('AirPods Pro 2',             'Chống ồn chủ động, sạc MagSafe USB-C',                        5990000,  28, 'https://placehold.co/400x300?text=AirPods+Pro+2', 5),
('JBL Tune 520BT',            'Tai nghe on-ear bluetooth, pin 57 giờ',                       1090000,  45, 'https://placehold.co/400x300?text=JBL+520BT', 5),
('Logitech H390',             'Tai nghe có dây USB, micro khử ồn',                           790000,   50, 'https://placehold.co/400x300?text=Logitech+H390', 5),
-- 6. Loa (3)
('JBL Flip 6',                'Loa bluetooth chống nước IP67, pin 12 giờ',                   2690000,  30, 'https://placehold.co/400x300?text=JBL+Flip+6', 6),
('Sony SRS-XB100',            'Loa bluetooth nhỏ gọn, âm bass mạnh',                         1290000,  35, 'https://placehold.co/400x300?text=SRS-XB100', 6),
('Logitech Z313',             'Bộ loa vi tính 2.1 công suất 25W',                            1190000,  24, 'https://placehold.co/400x300?text=Logitech+Z313', 6),
-- 7. Màn hình (4)
('Samsung Odyssey G5',        'Màn hình cong gaming 27 inch 2K 144Hz',                       6990000,  12, 'https://placehold.co/400x300?text=Odyssey+G5', 7),
('LG UltraGear 24GN600',      'Màn hình gaming 24 inch IPS 144Hz 1ms',                       3990000,  18, 'https://placehold.co/400x300?text=LG+24GN600', 7),
('Dell P2422H',               'Màn hình văn phòng 24 inch Full HD IPS',                      4290000,  20, 'https://placehold.co/400x300?text=Dell+P2422H', 7),
('ViewSonic VA2432',          'Màn hình 24 inch IPS viền mỏng, chăm sóc mắt',                2990000,  26, 'https://placehold.co/400x300?text=ViewSonic+VA2432', 7),
-- 8. Bàn phím & chuột (4)
('Logitech MX Master 3S',     'Chuột không dây cao cấp, cuộn MagSpeed siêu êm',              2390000,  22, 'https://placehold.co/400x300?text=MX+Master+3S', 8),
('Keychron K2 V2',            'Bàn phím cơ không dây 75%, hot-swap',                         2190000,  17, 'https://placehold.co/400x300?text=Keychron+K2', 8),
('Razer DeathAdder Essential','Chuột gaming 6400 DPI, thiết kế công thái học',               590000,   40, 'https://placehold.co/400x300?text=DeathAdder', 8),
('Akko 3068B Plus',           'Bàn phím cơ 65% ba chế độ kết nối',                           1790000,  19, 'https://placehold.co/400x300?text=Akko+3068B', 8),
-- 9. Ổ cứng & lưu trữ (3)
('Samsung T7 Portable SSD 1TB','SSD di động USB 3.2, tốc độ đọc 1050MB/s',                   2790000,  27, 'https://placehold.co/400x300?text=T7+SSD+1TB', 9),
('WD My Passport 2TB',        'Ổ cứng di động HDD 2TB USB 3.0',                              1990000,  30, 'https://placehold.co/400x300?text=WD+Passport', 9),
('Kingston DataTraveler 64GB','USB 3.2 tốc độ cao, nhỏ gọn',                                 190000,   100,'https://placehold.co/400x300?text=Kingston+64GB', 9),
-- 10. Camera (3)
('Camera Imou Ranger 2',      'Camera Wifi 2MP xoay 360 độ, đàm thoại 2 chiều',              690000,   45, 'https://placehold.co/400x300?text=Imou+Ranger+2', 10),
('Camera EZVIZ C6N',          'Camera trong nhà 1080p, theo dõi chuyển động',                790000,   38, 'https://placehold.co/400x300?text=EZVIZ+C6N', 10),
('Camera hành trình 70mai A400','Quay 2K, cảm biến va chạm, kết nối wifi',                   1590000,  20, 'https://placehold.co/400x300?text=70mai+A400', 10),
-- 11. Máy chơi game (3)
('Nintendo Switch OLED',      'Màn hình OLED 7 inch, 64GB, tay cầm Joy-Con',                 8490000,  11, 'https://placehold.co/400x300?text=Switch+OLED', 11),
('PlayStation 5 Slim',        'Console thế hệ mới, ổ đĩa 1TB',                               13990000, 8,  'https://placehold.co/400x300?text=PS5+Slim', 11),
('Tay cầm Xbox Wireless',     'Tay cầm không dây, tương thích PC và Xbox',                   1490000,  25, 'https://placehold.co/400x300?text=Xbox+Controller', 11),
-- 12. Phụ kiện điện thoại (4)
('Sạc nhanh Anker 65W',       'Củ sạc GaN 3 cổng, sạc được laptop',                          890000,   55, 'https://placehold.co/400x300?text=Anker+65W', 12),
('Pin dự phòng Xiaomi 20000mAh','Sạc nhanh 22.5W, 2 cổng ra',                               690000,   48, 'https://placehold.co/400x300?text=Xiaomi+20000mAh', 12),
('Cáp Baseus USB-C 100W',     'Cáp bọc dù 2m, sạc nhanh 100W',                               190000,   120,'https://placehold.co/400x300?text=Baseus+Cable', 12),
('Ốp lưng iPhone 15 trong suốt','Chống sốc, không ố vàng',                                   250000,   70, 'https://placehold.co/400x300?text=Op+iPhone+15', 12),
-- 13. Mạng & Wifi (3)
('TP-Link Archer AX23',       'Router Wifi 6 AX1800 băng tần kép',                           1290000,  30, 'https://placehold.co/400x300?text=Archer+AX23', 13),
('Asus RT-AX55',              'Router Wifi 6 AX1800 hỗ trợ AiMesh',                          1590000,  21, 'https://placehold.co/400x300?text=RT-AX55', 13),
('Switch TP-Link LS108G',     'Switch 8 cổng Gigabit để bàn',                                490000,   33, 'https://placehold.co/400x300?text=LS108G', 13),
-- 14. Máy in (2)
('Canon LBP2900',             'Máy in laser trắng đen A4 phổ biến',                          3190000,  14, 'https://placehold.co/400x300?text=Canon+LBP2900', 14),
('Brother HL-L2321D',         'Máy in laser đen trắng in hai mặt tự động',                   3490000,  10, 'https://placehold.co/400x300?text=Brother+L2321D', 14),
-- 15. Nhà thông minh (3)
('Ổ cắm thông minh Xiaomi',   'Điều khiển từ xa qua app, đo điện năng',                      390000,   60, 'https://placehold.co/400x300?text=Smart+Plug', 15),
('Bóng đèn Yeelight RGB',     'Bóng đèn LED đổi màu, điều khiển bằng giọng nói',             350000,   55, 'https://placehold.co/400x300?text=Yeelight', 15),
('Robot hút bụi Roborock Q7', 'Lực hút 4200Pa, lau nhà, lập bản đồ',                        7990000,  9,  'https://placehold.co/400x300?text=Roborock+Q7', 15);

-- ---------------------------------------------------------------------
-- Dữ liệu mẫu: 5 tài khoản (mật khẩu 123456)
-- ---------------------------------------------------------------------
INSERT INTO users (username, password, full_name, email, phone, address, role, enabled) VALUES
('seller1',   '$2a$10$uPUNNrC1JUnzBYDZD6AQBe.swyBhdnARmWl.Ctpo737mZZF4hejyS', 'Nguyễn Văn Bán',  'seller1@eshop.vn',   '0901000001', '12 Lê Lợi, Quận 1, TP.HCM',           'ROLE_SELLER',   1),
('seller2',   '$2a$10$uPUNNrC1JUnzBYDZD6AQBe.swyBhdnARmWl.Ctpo737mZZF4hejyS', 'Trần Thị Bán',    'seller2@eshop.vn',   '0901000002', '34 Nguyễn Huệ, Quận 1, TP.HCM',       'ROLE_SELLER',   1),
('customer1', '$2a$10$uPUNNrC1JUnzBYDZD6AQBe.swyBhdnARmWl.Ctpo737mZZF4hejyS', 'Nguyễn Văn Mua',  'customer1@gmail.com','0912000001', '56 Cách Mạng Tháng 8, Quận 3, TP.HCM','ROLE_CUSTOMER', 1),
('customer2', '$2a$10$uPUNNrC1JUnzBYDZD6AQBe.swyBhdnARmWl.Ctpo737mZZF4hejyS', 'Lê Thị Mua',     'customer2@gmail.com','0912000002', '78 Điện Biên Phủ, Bình Thạnh, TP.HCM','ROLE_CUSTOMER', 1),
('locked',    '$2a$10$uPUNNrC1JUnzBYDZD6AQBe.swyBhdnARmWl.Ctpo737mZZF4hejyS', 'Tài Khoản Khoá', 'locked@gmail.com',   '0912000003', '90 Võ Văn Tần, Quận 3, TP.HCM',       'ROLE_CUSTOMER', 0);

-- Kiểm tra: category 15, product 50, users 5
SELECT (SELECT COUNT(*) FROM category) AS category,
       (SELECT COUNT(*) FROM product)  AS product,
       (SELECT COUNT(*) FROM users)    AS users;
