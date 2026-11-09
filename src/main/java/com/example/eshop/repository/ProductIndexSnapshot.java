package com.example.eshop.repository;

public record ProductIndexSnapshot(Long id, String name, String description, Long categoryId, String categoryName) {}
