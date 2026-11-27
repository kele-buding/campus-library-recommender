package com.campus.library;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.campus.library.mapper")
public class CampusLibraryApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampusLibraryApplication.class, args);
    }
}
