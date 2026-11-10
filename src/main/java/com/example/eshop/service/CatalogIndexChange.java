package com.example.eshop.service;

public interface CatalogIndexChange {
    record ProductChanged(long id) implements CatalogIndexChange {}
    record CategoryRenamed(long id) implements CatalogIndexChange {}
}
