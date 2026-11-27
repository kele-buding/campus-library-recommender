package com.campus.library.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private boolean experimentalClockEnabled = true;
    private Seed seed = new Seed();
    private Cloud cloud = new Cloud();

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    public boolean isExperimentalClockEnabled() {
        return experimentalClockEnabled;
    }

    public void setExperimentalClockEnabled(boolean experimentalClockEnabled) {
        this.experimentalClockEnabled = experimentalClockEnabled;
    }

    public Seed getSeed() {
        return seed;
    }

    public void setSeed(Seed seed) {
        this.seed = seed;
    }

    public Cloud getCloud() {
        return cloud;
    }

    public void setCloud(Cloud cloud) {
        this.cloud = cloud;
    }

    public static class Seed {
        private boolean autoOnStartup = false;
        private String defaultPassword = "CampusLib#2026";

        public boolean isAutoOnStartup() {
            return autoOnStartup;
        }

        public void setAutoOnStartup(boolean autoOnStartup) {
            this.autoOnStartup = autoOnStartup;
        }

        public String getDefaultPassword() {
            return defaultPassword;
        }

        public void setDefaultPassword(String defaultPassword) {
            this.defaultPassword = defaultPassword;
        }
    }

    public static class Cloud {
        private boolean mockMode = true;
        private String region = "cn-north-4";
        private int monthlyBudgetLimit = 5000;

        public boolean isMockMode() {
            return mockMode;
        }

        public void setMockMode(boolean mockMode) {
            this.mockMode = mockMode;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public int getMonthlyBudgetLimit() {
            return monthlyBudgetLimit;
        }

        public void setMonthlyBudgetLimit(int monthlyBudgetLimit) {
            this.monthlyBudgetLimit = monthlyBudgetLimit;
        }
    }
}
