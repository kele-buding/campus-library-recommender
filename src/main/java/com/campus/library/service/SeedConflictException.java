package com.campus.library.service;

/**
 * 目标数据库已存在非同版本业务数据时抛出，防止初始化脚本误覆盖。
 */
public class SeedConflictException extends RuntimeException {
    public SeedConflictException(String message) {
        super(message);
    }
}
